package net.kuzuanpa.cemekanism.machine;

import net.kuzuanpa.cemekanism.platform.BukkitItems;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 风力发电机的独立叶片层。方块模型只保留塔身/机舱，叶片由 ItemDisplay 绕轮毂旋转。
 */
final class WindRotorRenderer {

    /** 塔顶轮毂：原版模型轮毂在塔身 4.5 格处，底座贴方块底面。 */
    private static final double HUB_HEIGHT = 4.5;
    /**
     * 轮毂相对方块中心的前移。塔模型机舱中心在 z≈8.2（模型单位），相对方块中心只偏 ~0.03 格；
     * 之前按"方块前缘内侧 2px"取的 0.375 格会把叶片整体往前推，视觉上就是叶片错位。
     */
    private static final double HUB_FORWARD = 0.0;
    private static final float SPIN_DEGREES_PER_TICK = 6.0f;

    private final MachineBlockEntityController controller;
    private ItemDisplay display;
    private float angle;

    WindRotorRenderer(MachineBlockEntityController controller) {
        this.controller = controller;
    }

    void spawn() {
        World world = controller.world();
        if (world == null) {
            return;
        }
        if (this.display != null && this.display.isValid()) {
            // 重复 onLoad 不重建实体：只刷新变换，避免旧实体没删干净时越叠越多。
            applyTransform();
            return;
        }
        remove();
        float yaw = yaw();
        // 与塔身同一条旋转链：外观 yaw + item_display 自带的 180°。
        Vector3f hub = new Quaternionf().rotationY((float) Math.toRadians(yaw + 180.0f))
                .transform(new Vector3f(0.0f, 0.0f, (float) -HUB_FORWARD));
        double x = controller.x() + 0.5 + hub.x;
        double z = controller.z() + 0.5 + hub.z;
        Location at = new Location(world, x, controller.y() + HUB_HEIGHT, z);
        this.display = world.spawn(at, ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setGravity(false);
            entity.setBillboard(ItemDisplay.Billboard.FIXED);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setInterpolationDuration(0);
            entity.setTeleportDuration(0);
            entity.setViewRange(1.0f);
            ItemStack stack = BukkitItems.create("mek:render/wind_blades");
            if (stack != null) {
                entity.setItemStack(stack);
            }
        });
        applyTransform();
    }

    void tick() {
        if (this.display == null || !this.display.isValid()) {
            spawn();
            return;
        }
        if (!active()) {
            return;
        }
        this.angle = (this.angle + SPIN_DEGREES_PER_TICK) % 360.0f;
        applyTransform();
    }

    boolean active() {
        World world = controller.world();
        return world != null
                && !GeneratorEnvironment.noWindDimension(world)
                && GeneratorEnvironment.windTopOpen(world, controller.x(), controller.y(), controller.z());
    }

    private void applyTransform() {
        if (this.display == null || !this.display.isValid()) {
            return;
        }
        Quaternionf rotation = new Quaternionf()
                .rotationY((float) Math.toRadians(yaw()))
                .mul(new Quaternionf().rotationZ((float) Math.toRadians(angle)));
        this.display.setTransformation(new Transformation(
                new Vector3f(), rotation, new Vector3f(2.3594f, 2.3594f, 2.3594f), new Quaternionf()));
    }

    /** 与方块外观的 rotation 保持一致；item_display 自身还会再转 180°。 */
    private float yaw() {
        return switch (controller.facing()) {
            case NORTH -> 180.0f;
            case SOUTH -> 0.0f;
            case EAST -> 90.0f;
            case WEST -> 270.0f;
            default -> 0.0f;
        };
    }

    void remove() {
        ItemDisplay current = this.display;
        this.display = null;
        if (current != null) {
            try {
                // 不再判断 isValid()：卸载/重载竞态下它会返回 false，跳过删除就会漏实体。
                current.remove();
            } catch (RuntimeException ignored) {
                // 实体已经不在世界（区块卸载/被清理）时不阻塞后续清理。
            }
        }
    }
}
