package net.kuzuanpa.cemekanism.machine;

import mekcore.chemical.Chemical;
import mekcore.chemical.Chemicals;
import mekcore.chemical.Radiation;
import mekcore.item.ItemSlotSpec;
import mekcore.item.ItemSlotKind;
import mekcore.machine.Direction;
import mekcore.machine.FactoryTier;
import mekcore.machine.Machine;
import mekcore.machine.MachineBlueprint;
import mekcore.machine.SideMode;
import mekcore.machine.WorkState;
import mekcore.resource.ResourceStack;
import mekcore.resource.ResourceTank;
import mekcore.resource.Transaction;
import mekcore.recipe.Recipe;
import mekcore.sim.Tickable;
import net.kuzuanpa.cemekanism.CeMekanismPlugin;
import net.kuzuanpa.cemekanism.generated.GeneratedContent;
import net.kuzuanpa.cemekanism.gui.GuiComponents;
import net.kuzuanpa.cemekanism.gui.GuiLayout;
import net.kuzuanpa.cemekanism.gui.GuiSessions;
import net.kuzuanpa.cemekanism.gui.MachineGui;
import net.kuzuanpa.cemekanism.gui.GuiText;
import net.kuzuanpa.cemekanism.i18n.I18n;
import net.kuzuanpa.cemekanism.platform.BukkitItems;
import net.kuzuanpa.cemekanism.platform.BukkitItemHandler;
import net.kuzuanpa.cemekanism.platform.CompoundDataTag;
import net.kuzuanpa.cemekanism.platform.CraftContainers;
import net.kuzuanpa.cemekanism.storage.PersonalStorageLogic;
import net.kuzuanpa.cemekanism.storage.QioDashboard;
import net.kuzuanpa.cemekanism.storage.QioFrequencyGui;
import net.kuzuanpa.cemekanism.storage.QioService;
import net.kuzuanpa.cemekanism.storage.StorageContentRenderer;
import net.kuzuanpa.cemekanism.storage.StorageListener;
import net.kuzuanpa.cemekanism.storage.StorageRuntime;
import net.momirealms.craftengine.bukkit.plugin.gui.BukkitInventory;
import net.momirealms.craftengine.bukkit.world.inventory.BukkitWorldlyStorageContainer;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 机器方块实体控制器：核心 {@link Machine} 的宿主。
 * 它只做四件事——建机器与容器、存档读档、把机器挂/摘到调度器、破坏时掉落物品；
 * 逐刻逻辑全在核心里（文档 03 适配层 3：不装平台的方块实体 ticker）。
 */
public final class MachineBlockEntityController extends BlockEntityController {

    /** 一个方块可以挂多个控制器，大家共用同一棵根标签，所以各写各的 key。 */
    private static final String ROOT_KEY = "cemekanism:machine";
    private static final String STATE_KEY = "state";
    private static final String ITEMS_KEY = "items";
    private static final String LAYOUT_KEY = "layout_version";
    private static final int LAYOUT_VERSION = 3;

    private final CeMekanismPlugin plugin;
    private final String blockId;
    private final Machine machine;
    private final BukkitItemHandler handler;
    private final List<Integer> guiIndices;
    private final Inventory inventory;
    private final MachineHolder holder;
    private final Object containerHandle;
    private String taskId;
    private final int infuseSlot;
    /** 发电机（source）：每刻把缓存电量直接发射给相邻机器，复刻上游 TileEntityGenerator。 */
    private final boolean energySource;
    private final WindRotorRenderer windRotor;
    private final EnergyCubeCoreRenderer energyCore;
    private final EnergyCubePortRenderer energyPorts;
    private final StorageContentRenderer storageContents;
    private final MachineActivityRenderer activity;
    private final List<ItemStack> pendingDrops = new ArrayList<>();
    private final List<UpgradeInstallerGui> upgradeInstallers = new ArrayList<>();
    private final List<RecipeBrowserGui> recipeBrowsers = new ArrayList<>();
    private volatile long gridDemand;
    private long observedEnergy;
    public long gridDemand() { return this.gridDemand; }
    private boolean registered;
    private boolean suppressStorageDrops;
    public boolean suppressStorageDrops() { return this.suppressStorageDrops; }
    public void setSuppressStorageDrops(boolean value) { this.suppressStorageDrops = value; }
    private boolean overlayUpdate;

    private final Tickable task = new Tickable() {
        @Override
        public boolean needsTick() {
            refreshRedstone();
            StorageRuntime.sync(MachineBlockEntityController.this);
            // 每趟调度都同步外观：休眠收尾、红石禁用及管道直接修改的储量也需要刷新。
            MachineBlockEntityController.this.activity.refresh();
            var m = MachineBlockEntityController.this.machine;
            // 电网在 CORE 车道充电；待机机器不跑 tick，也要在 MAIN 车道标记电量变化待保存。
            long storedEnergy = m.energyTank().map(tank -> tank.amount()).orElse(0L);
            if (storedEnergy != MachineBlockEntityController.this.observedEnergy) {
                MachineBlockEntityController.this.observedEnergy = storedEnergy;
                MachineBlockEntityController.this.markChanged();
            }
            MachineBlockEntityController.this.gridDemand = m.blueprint().logic() == null ? m.maxEnergyPerTick()
                    : Math.max(0, m.blueprint().logic().energyDemand(m));
            if (MachineBlockEntityController.this.windRotor != null
                    && MachineBlockEntityController.this.windRotor.active()) {
                return true;
            }
            if (MachineBlockEntityController.this.energyCore != null) {
                // 储量变化必须能在休眠后唤醒；核心本身也按游戏刻持续旋转。
                return true;
            }
            if (MachineBlockEntityController.this.energySource
                    && m.energyTank().map(tank -> tank.amount() > 0).orElse(false)) {
                // 发电机缓存里有电就必须继续跑：上游每刻都会 emit 给相邻方块，缓存满了也不能睡，
                // 否则贴在一起的机器永远等不到电。
                return true;
            }
            return MachineBlockEntityController.this.machine.needsTick()
                    || MachineBlockEntityController.this.infusionReady();
        }

        @Override
        public long tick() {
            MachineBlockEntityController.this.feedInfusion();
            boolean dumping = MachineBlockEntityController.this.machine.hasDumpWork();
            long joules = MachineBlockEntityController.this.machine.tick();
            if (MachineBlockEntityController.this.energySource) {
                MachineBlockEntityController.this.pushAdjacentEnergy();
            }
            if (MachineBlockEntityController.this.windRotor != null) {
                MachineBlockEntityController.this.windRotor.tick();
            }
            if (MachineBlockEntityController.this.energyCore != null) {
                MachineBlockEntityController.this.energyCore.tick();
            }
            if (MachineBlockEntityController.this.energyPorts != null) {
                MachineBlockEntityController.this.energyPorts.tick();
            }
            MachineBlockEntityController.this.activity.refresh();
            if (dumping || MachineBlockEntityController.this.machine.state() != WorkState.IDLE) {
                // 进度/能量变了就让区块标记待保存；睡着的机器不会进这里
                MachineBlockEntityController.this.markChanged();
            }
            return joules;
        }

        @Override
        public String describe() {
            return MachineBlockEntityController.this.machine.describe();
        }
    };

    /**
     * 上游 {@code TileEntityGenerator.onUpdateServer} 的邻面直供：发电机每刻把缓存电量
     * 发射给"能量输出面"相邻的机器（不需要线缆）。线缆仍由电网从同一个缓存里拉，两者竞争，
     * 谁先拿走算谁的；缓存被拉空后不再发送。
     */
    private void pushAdjacentEnergy() {
        World world = world();
        if (world == null) {
            return;
        }
        ResourceTank source = this.machine.energyTank().orElse(null);
        if (source == null || source.capacity() <= 0) {
            return;
        }
        for (Direction direction : Direction.values()) {
            if (source.amount() <= 0) {
                return;
            }
            // 只从发电机自己的能量输出面发射（热力发电机默认 front，配置器可改）。
            if (!this.machine.side(MachineBlueprint.ENERGY_PORT, direction).allowsExtract()) {
                continue;
            }
            MachineBlockEntityController neighbour = this.plugin.machineAtQuiet(world,
                    x() + offsetX(direction), y() + offsetY(direction), z() + offsetZ(direction));
            if (neighbour == null || neighbour == this) {
                continue;
            }
            Machine target = neighbour.machine();
            ResourceTank tank = target.energyTank().orElse(null);
            if (tank == null || tank.capacity() <= 0) {
                continue;
            }
            if ("source".equals(GeneratedContent.MACHINE_ROLES.getOrDefault(
                    target.blueprint().id(), "machine"))) {
                continue;                                  // 发电机之间不互相推，避免来回搬电
            }
            if (!target.side(MachineBlueprint.ENERGY_PORT, direction.opposite()).allowsInsert()) {
                continue;                                  // 目标那一面不是"能量输入"
            }
            long extracted = source.extract(source.amount(), Transaction.EXECUTE);
            if (extracted <= 0) {
                return;
            }
            long accepted = tank.insert(ResourceStack.energy(extracted), Transaction.EXECUTE);
            if (accepted < extracted) {
                // 目标只吃下一部分：剩下的还回自己，下一步交给线缆/下一个邻居。
                source.insert(ResourceStack.energy(extracted - accepted), Transaction.EXECUTE);
            }
        }
    }

    private static int offsetX(Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    private static int offsetY(Direction direction) {
        return switch (direction) {
            case UP -> 1;
            case DOWN -> -1;
            default -> 0;
        };
    }

    private static int offsetZ(Direction direction) {
        return switch (direction) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
    }

    public MachineBlockEntityController(BlockEntity blockEntity, CeMekanismPlugin plugin) {
        super(blockEntity);
        this.plugin = plugin;
        this.blockId = blockEntity.blockState().owner().value().id().asString();
        String blueprintId = MachineBlocks.blueprintId(this.blockId);
        FactoryTier tier = MachineBlocks.factoryTier(this.blockId);
        MachineBlueprint blueprint = plugin.core().registrars().machines().require(blueprintId);
        MachineHolder holder = new MachineHolder();
        this.holder = holder;
        int size = GuiLayout.containerSize(this.blockId, tier,
                BukkitItemHandler.containerSize(tier.parallel(), blueprint.itemSlots()));
        // 世界级容器（台账 P13）：CE 的 NMS 容器让原版漏斗/比较器也能看见机器的格子。
        MachineStorageContainer storage = new MachineStorageContainer(holder, size, this);
        CraftContainers.Wrapped wrapped = CraftContainers.wrap(storage);
        this.inventory = wrapped == null ? Bukkit.createInventory(holder, size) : wrapped.inventory();
        this.containerHandle = wrapped == null ? null : wrapped.handle();
        holder.inventory = this.inventory;
        holder.controller = this;
        this.guiIndices = GuiLayout.containerIndices(blueprint, tier, this.blockId);
        this.handler = new BukkitItemHandler(blueprintId, this.inventory, tier.parallel(), this::markChanged,
                this.guiIndices,
                blueprint.itemSlots().toArray(new ItemSlotSpec[0]));
        this.machine = plugin.core().factories().machine(blueprintId, tier, blockEntity.pos().asLong(),
                this.handler);
        this.storageContents = new StorageContentRenderer(this);
        this.activity = new MachineActivityRenderer(this);
        StorageRuntime.resize(this.machine);
        this.windRotor = blueprintId.equals("mek:wind_generator")
                ? new WindRotorRenderer(this) : null;
        this.energyCore = this.blockId.endsWith("_energy_cube")
                ? new EnergyCubeCoreRenderer(this) : null;
        this.energyPorts = this.blockId.endsWith("_energy_cube")
                ? new EnergyCubePortRenderer(this) : null;
        GeneratorDefaults.apply(this.machine, blueprintId, facing());
        StorageRuntime.defaults(this.machine, facing());
        // 普通机器没有默认面时补上能量输入/物品输入（上游 Mekanism 的默认口径），否则贴一起也收不到电。
        MachineFaceDefaults.apply(this.machine);
        this.energySource = "source".equals(GeneratedContent.MACHINE_ROLES.getOrDefault(blueprintId, "machine"));
        this.infuseSlot = this.handler.indexOf(InfusionFeeder.slotName());
        this.taskId = blueprintId + '@' + blockEntity.pos.x() + ',' + blockEntity.pos.y()
                + ',' + blockEntity.pos.z();
    }

    public Machine machine() {
        return this.machine;
    }

    public String blockId() {
        return this.blockId;
    }

    public CeMekanismPlugin plugin() {
        return this.plugin;
    }

    /** 方块状态的 facing 属性；太阳能这类没有朝向的返回 NORTH（它只用底面，不影响结果）。 */
    public mekcore.machine.Direction facing() {
        String properties = this.blockEntity.blockState().getPropertiesAsString();
        for (String part : properties.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || !"facing".equals(pair[0])) {
                continue;
            }
            for (mekcore.machine.Direction direction : mekcore.machine.Direction.values()) {
                if (direction.name().equalsIgnoreCase(pair[1])) {
                    return direction;
                }
            }
        }
        return mekcore.machine.Direction.NORTH;
    }

    public boolean canFace(mekcore.machine.Direction direction) {
        return MachineOrientation.supports(this.blockEntity.blockState(), direction);
    }

    /** Update the existing CE state, retaining the controller, inventory, energy and other properties. */
    public boolean face(mekcore.machine.Direction direction) {
        World world = world();
        if (world == null) return false;
        var state = this.blockEntity.blockState();
        var rotated = MachineOrientation.facing(state, direction);
        if (rotated == state) return false;
        net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(world).storageWorld().world()
                .setBlockState(x(), y(), z(), rotated,
                        net.momirealms.craftengine.core.block.UpdateFlags.UPDATE_ALL);
        markChanged();
        return true;
    }

    /**
     * 按当前 facing 与 {@code mek-systems.yml} 的 generator.defaults 重落默认面。
     * 已放置的机器不会因为配置改了默认面就自动变，调试/换默认值时显式调这一条。
     */
    public void resetGeneratorDefaults() {
        GeneratorDefaults.apply(this.machine, this.machine.blueprint().id(), facing());
        markChanged();
    }

    public int x() {
        return this.blockEntity.pos.x();
    }

    public int y() {
        return this.blockEntity.pos.y();
    }

    public int z() {
        return this.blockEntity.pos.z();
    }

    public Inventory inventory() {
        return this.inventory;
    }

    public MachineHolder holder() {
        return this.holder;
    }

    /** 给 CE 的 WorldlyContainerHolder 用：原版漏斗拿到的就是这份 NMS 容器。 */
    public Object containerHandle() {
        return this.containerHandle;
    }

    public BukkitItemHandler handler() {
        return this.handler;
    }

    /** 调试用：按槽位名往容器里放东西（等价于玩家把物品摆进那个格子）。 */
    public boolean putInSlot(String slotName, ItemStack stack) {
        int index = this.handler.indexOf(slotName);
        if (index < 0) {
            return false;
        }
        this.inventory.setItem(index, stack);
        markChanged();
        return true;
    }

    public ItemStack slotContent(String slotName) {
        int index = this.handler.indexOf(slotName);
        return index < 0 ? null : this.inventory.getItem(index);
    }

    public String taskId() {
        return this.taskId;
    }

    public boolean registered() {
        return this.registered;
    }

    /** 放置时认领：已有 owner 的机器不覆盖。 */
    public boolean claim(String playerId) {
        String current = this.machine.owner();
        if (current == null || current.isEmpty()) {
            this.machine.setOwner(playerId);
            markChanged();
            return true;
        }
        return current.equals(playerId);
    }

    /** 事件侧改完状态就完事：调度器每趟都重新问 needsTick()，只有被节流的任务才要 wake。 */
    public void setPowered(boolean powered) {
        this.machine.setPowered(powered);
        this.activity.refresh();
    }

    public void markChanged() {
        if (this.overlayUpdate) {
            return;
        }
        this.storageContents.refresh();
        this.activity.refresh();
        if (this.blockEntity.world != null) {
            this.blockEntity.world.blockEntityChanged(this.blockEntity.pos);
        }
    }

    /** 隐形 tooltip 锚点每 5 刻重写一次，不该把整块存档标脏。 */
    public void beginOverlayUpdate() {
        this.overlayUpdate = true;
    }

    public void endOverlayUpdate() {
        this.overlayUpdate = false;
    }

    /**
     * 红石真值只在这里刷新：这个任务挂 MAIN 车道，needsTick()/tick() 都在主线程泵里跑，
     * 所以读世界是安全的；调度器每趟都问一次，红石拉高拉低都漏不掉（不需要唤醒点）。
     */
    private void refreshRedstone() {
        World world = world();
        if (world == null) {
            return;
        }
        int bx = this.blockEntity.pos.x();
        int by = this.blockEntity.pos.y();
        int bz = this.blockEntity.pos.z();
        // isBlockPowered() 会向六个方向读红石信号，隔壁区块没装载时 CraftBlock 会同步把区块拉起来。
        // 在区块加载回调（onLoad → refreshRedstone）里踩到这一步，就会 加载→查邻居→再加载 级联下去。
        // 所以只在本区块与 3×3 邻区块都就绪时才读真值；没就绪就跳过，下一趟 tick 还会再问。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!world.isChunkLoaded((bx >> 4) + dx, (bz >> 4) + dz)) {
                    return;
                }
            }
        }
        this.machine.setPowered(world.getBlockAt(bx, by, bz).isBlockPowered());
    }

    /** 开界面：皮肤字形 + 槽位摆位 + 能量/流体/进度条；刷新由 GuiSessions 统一节流。 */
    public boolean openFor(Player player) {
        if (!(player.platformPlayer() instanceof org.bukkit.entity.Player bukkit)) {
            return false;
        }
        if (this.machine.owner() == null || this.machine.owner().isEmpty()) {
            claim(bukkit.getUniqueId().toString());
        }
        if (!this.machine.canAccess(bukkit.getUniqueId().toString())) {
            I18n.send(bukkit, "machine.not_owner",
                    I18n.enumName(this.machine.security(), this.machine.security().zhName()));
            return false;
        }
        World world = world();
        if (world != null) {
            this.plugin.radiation(world).containerOpened(bukkit, sourceMagnitude());
        }
        if (PersonalStorageLogic.isStorage(this.blockId)) {
            bukkit.openInventory(this.inventory);
            return true;
        }
        if (this.blockId.equals("mek:qio_dashboard")) {
            if (QioService.state(this).frequency.isEmpty())
                QioFrequencyGui.open(this.plugin, this, bukkit, 0);
            else QioDashboard.open(this, bukkit, 0);
            return true;
        }
        MachineGui.updateAnchors(this);
        new BukkitInventory(this.inventory).open(player,
                GuiComponents.toCe(MachineGui.title(this), player));
        this.plugin.guiSessions().open(bukkit, guiView());
        return true;
    }

    public void report(org.bukkit.entity.Player player) {
        if (!this.machine.canAccess(player.getUniqueId().toString())) {
            player.sendActionBar(I18n.component(player, "machine.no_view_permission"));
            return;
        }
        switch (this.machine.state()) {
            case RUNNING, IDLE -> { }
            default -> player.sendActionBar(I18n.component(player, "machine.state",
                    I18n.enumName(this.machine.state(), this.machine.state().zhName())));
        }
    }

    private GuiSessions.View guiView() {
        return new GuiSessions.View(this.holder, this.inventory, new Location(world(),
                this.blockEntity.pos.x() + 0.5, this.blockEntity.pos.y() + 0.5,
                this.blockEntity.pos.z() + 0.5),
                () -> MachineGui.title(this),
                () -> MachineGui.updateAnchors(this),
                () -> Integer.toHexString(Arrays.hashCode(this.inventory.getContents())),
                () -> { });
    }

    /**
     * 独立升级界面：先验证机器权限，再按蓝图允许项生成槽位。
     * 界面是临时视图，不进入机器的世界容器，也不参与自动化。
     */
    public boolean openUpgradeInstaller(org.bukkit.entity.Player bukkit) {
        if (this.machine.owner() == null || this.machine.owner().isEmpty()) {
            claim(bukkit.getUniqueId().toString());
        }
        if (!this.machine.canAccess(bukkit.getUniqueId().toString())) {
            I18n.send(bukkit, "machine.not_owner",
                    I18n.enumName(this.machine.security(), this.machine.security().zhName()));
            return false;
        }
        if (this.machine.upgrades().allowed().isEmpty()) {
            I18n.send(bukkit, "machine.upgrade_unsupported",
                    I18n.machineName(I18n.locale(bukkit), this.machine.blueprint()));
            return false;
        }
        UpgradeInstallerGui gui = UpgradeInstallerGui.create(this);
        this.upgradeInstallers.add(gui);
        gui.open(bukkit);
        return true;
    }

    public List<Recipe> supportedRecipes() {
        return this.plugin.core().registrars().recipes().byUsage(this.machine.blueprint().id());
    }

    /** 进度锚点的只读配方页；与升级安装器一样，不进入机器世界容器。 */
    public boolean openRecipeBrowser(org.bukkit.entity.Player bukkit) {
        if (!this.machine.canAccess(bukkit.getUniqueId().toString())) {
            I18n.send(bukkit, "machine.not_owner",
                    I18n.enumName(this.machine.security(), this.machine.security().zhName()));
            return false;
        }
        if (supportedRecipes().isEmpty()) {
            bukkit.sendActionBar(I18n.component(bukkit, "machine.no_recipes"));
            return false;
        }
        RecipeBrowserGui gui = RecipeBrowserGui.create(this);
        this.recipeBrowsers.add(gui);
        gui.open(bukkit);
        return true;
    }

    public void forgetRecipeBrowser(RecipeBrowserGui gui) {
        this.recipeBrowsers.remove(gui);
    }

    public int installUpgrade(String upgradeId, int amount) {
        int installed = this.machine.installUpgrade(upgradeId, amount);
        if (installed > 0) {
            markChanged();
            refreshUpgradeInstallers();
        }
        return installed;
    }

    public int removeUpgrade(String upgradeId, int amount) {
        int removed = this.machine.removeUpgrade(upgradeId, amount);
        if (removed > 0) {
            markChanged();
            refreshUpgradeInstallers();
        }
        return removed;
    }

    public void forgetUpgradeInstaller(UpgradeInstallerGui gui) {
        this.upgradeInstallers.remove(gui);
    }

    private void refreshUpgradeInstallers() {
        for (UpgradeInstallerGui gui : this.upgradeInstallers) {
            gui.refresh();
        }
    }

    private void closeUpgradeInstallers() {
        List<UpgradeInstallerGui> open = List.copyOf(this.upgradeInstallers);
        this.upgradeInstallers.clear();
        open.forEach(UpgradeInstallerGui::closeViewers);
    }

    private void closeRecipeBrowsers() {
        List<RecipeBrowserGui> open = List.copyOf(this.recipeBrowsers);
        this.recipeBrowsers.clear();
        open.forEach(RecipeBrowserGui::closeViewers);
    }

    /** 调试命令卸下升级：核心减计数，物品落到机器上方。 */
    public int uninstallUpgrade(String upgradeId, int amount) {
        int removed = removeUpgrade(upgradeId, amount);
        if (removed <= 0) {
            return 0;
        }
        ItemStack refund = BukkitItems.create(upgradeId);
        if (refund != null) {
            refund.setAmount(removed);
            World world = world();
            if (world != null) {
                world.dropItemNaturally(new Location(world, this.blockEntity.pos.x() + 0.5,
                        this.blockEntity.pos.y() + 1.0, this.blockEntity.pos.z() + 0.5), refund);
            }
        }
        return removed;
    }

    @Override
    public void onLoad() {
        World world = world();
        if (world != null) {
            this.taskId = this.machine.blueprint().id() + '@' + world.getUID() + ':' + x() + ',' + y() + ',' + z();
            CraftContainers.rememberLevel(this.blockEntity.world.world().minecraftWorld(), world);
            MachineContexts.attach(this.machine, new MachineContexts.Place(world,
                    this.blockEntity.pos.x(), this.blockEntity.pos.y(), this.blockEntity.pos.z()));
            // 机器挨着的管道要重绑端点、它的热量节点要进热网（文档 06 第十节）
            StorageRuntime.attach(this);
            this.plugin.logistics(world).attachMachine(this);
            this.plugin.radiation(world).register(this.taskId, x(), y(), z(), this::sourceMagnitude);
        }
        if (!this.registered) {
            this.plugin.scheduler().register(this.taskId, this.task);
            this.registered = true;
        }
        if (this.windRotor != null) {
            this.windRotor.spawn();
        }
        if (this.energyCore != null) {
            this.energyCore.spawn();
        }
        if (this.energyPorts != null) {
            this.energyPorts.spawn();
        }
        this.storageContents.spawn();
        this.activity.refresh();
        dropPendingItems();
        refreshRedstone();
    }

    /** 蓝图删掉旧通用槽后，读档时无处安放的物品在这里落回机器脚下，避免静默丢失。 */
    private void dropPendingItems() {
        if (this.pendingDrops.isEmpty()) {
            return;
        }
        World world = world();
        if (world == null) {
            return;
        }
        Location at = new Location(world, this.blockEntity.pos.x() + 0.5,
                this.blockEntity.pos.y() + 0.6, this.blockEntity.pos.z() + 0.5);
        for (ItemStack stack : this.pendingDrops) {
            world.dropItemNaturally(at, stack);
        }
        this.pendingDrops.clear();
    }

    /**
     * 只移除本机器的显示实体（风力叶片/能量核心/端口/内容），不动存档与容器。
     * 方块被玩家挖掉时 CE 不一定会走到 onRemove，所以由破坏事件兜底调用；幂等。
     */
    public void removeDisplays() {
        safeUnload("wind_rotor", () -> {
            if (this.windRotor != null) {
                this.windRotor.remove();
            }
        });
        safeUnload("energy_core", () -> {
            if (this.energyCore != null) {
                this.energyCore.remove();
            }
        });
        safeUnload("energy_ports", () -> {
            if (this.energyPorts != null) {
                this.energyPorts.remove();
            }
        });
        safeUnload("storage_contents", () -> this.storageContents.remove());
    }

    @Override
    public void onUnload() {
        // 显示实体先清：后面任何一步抛异常（CE 卸载失败重试）都不能把模型留在世界里。
        removeDisplays();
        World world;
        try {
            world = world();
        } catch (RuntimeException e) {
            warnUnload("world", e);
            world = null;
        }
        if (world != null) {
            final World w = world;
            safeUnload("storage", () -> StorageRuntime.detach(this));
            safeUnload("logistics", () -> this.plugin.logistics(w).detachMachine(this));
            safeUnload("radiation", () -> this.plugin.radiation(w).unregister(this.taskId));
        }
        closeUpgradeInstallers();
        closeRecipeBrowsers();
        safeUnload("gui", () -> this.plugin.guiSessions().close(this.holder));
        for (var viewer : List.copyOf(this.inventory.getViewers())) viewer.closeInventory();
        unregister();
        MachineContexts.detach(this.machine);
    }

    @Override
    public void onRemove() {
        removeDisplays();
        World world;
        try {
            world = world();
        } catch (RuntimeException e) {
            warnUnload("world", e);
            world = null;
        }
        if (world != null) {
            final World w = world;
            safeUnload("storage", () -> StorageRuntime.detach(this));
            safeUnload("logistics", () -> this.plugin.logistics(w).detachMachine(this));
            safeUnload("radiation", () -> this.plugin.radiation(w).unregister(this.taskId));
        }
        closeUpgradeInstallers();
        closeRecipeBrowsers();
        safeUnload("refund", () -> StorageListener.refundBuffer(this));
        safeUnload("gui", () -> this.plugin.guiSessions().close(this.holder));
        for (var viewer : List.copyOf(this.inventory.getViewers())) viewer.closeInventory();
        unregister();
        MachineContexts.detach(this.machine);
        if (world != null) {
            final World w = world;
            safeUnload("drops", () -> {
                Location at = new Location(w, this.blockEntity.pos.x() + 0.5,
                        this.blockEntity.pos.y() + 0.5, this.blockEntity.pos.z() + 0.5);
                for (ItemStack stack : this.inventory.getContents()) {
                    if (!this.suppressStorageDrops && stack != null && !stack.getType().isAir()
                            && !GuiText.isOverlay(stack)) {
                        w.dropItemNaturally(at, stack);
                    }
                }
            });
        }
        this.inventory.clear();
    }

    /** 卸载/销毁时每一步都独立兜底：CE 卸载失败会反复重试，绝不能让单点异常拖住整个卸载。 */
    private void safeUnload(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            warnUnload(step, e);
        }
    }

    private void warnUnload(String step, RuntimeException error) {
        this.plugin.getLogger().log(java.util.logging.Level.WARNING,
                "machine " + this.taskId + " unload step '" + step + "' failed", error);
    }

    @Override
    public void saveCustomData(CompoundTag root) {
        CompoundTag mine = new CompoundTag();
        CompoundTag state = new CompoundTag();
        this.machine.save(new CompoundDataTag(state));
        mine.put(STATE_KEY, state);
        ListTag items = new ListTag();
        for (int index = 0; index < this.inventory.getSize(); index++) {
            ItemStack stack = this.inventory.getItem(index);
            if (stack == null || stack.getType().isAir() || GuiText.isOverlay(stack)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", index);
            String name = this.handler.nameAt(index);
            if (name != null) {
                entry.putString("name", name);
            }
            entry.putString("data", Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
            items.add(entry);
        }
        mine.put(ITEMS_KEY, items);
        // v2 = 槽位按 gui-layout.yml 的格子索引存；v1（缺失）= 旧版按声明顺序连续编号。
        mine.putInt(LAYOUT_KEY, LAYOUT_VERSION);
        root.put(ROOT_KEY, mine);
    }

    @Override
    public void loadCustomData(CompoundTag root) {
        CompoundTag mine = root.getCompound(ROOT_KEY);
        if (mine == null) {
            return;                                      // 刚放下的机器：还没有存档
        }
        CompoundTag state = mine.getCompound(STATE_KEY);
        if (state != null) {
            try {
                this.machine.load(new CompoundDataTag(state));
            } catch (RuntimeException e) {
                this.plugin.getLogger().warning(I18n.tr("machine.load_failed",
                        this.taskId, e.getMessage()));
            }
        }
        // 旧版本存档里的机器六面全是「关」：读档后补一次默认输入面，否则老存档的机器永远收不到电。
        // apply 只在整组面全 NONE 时动手，玩家配置过任意一面的机器不会被覆盖。
        MachineFaceDefaults.apply(this.machine);
        this.inventory.clear();
        ListTag items = mine.getList(ITEMS_KEY);
        int version = mine.getInt(LAYOUT_KEY, 1);
        for (int index = 0; items != null && index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            if (entry == null) {
                continue;
            }
            int oldSlot = entry.getInt("slot");
            ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.getString("data")));
            if (GuiText.isOverlay(stack)) {
                continue;
            }
            String savedName = entry.getString("name");
            String slotName = savedName == null || savedName.isEmpty() ? null : savedName;
            if (slotName == null && version < 3) {
                // v2 及更早只存格号：先按旧 27/36 格规则反查名字；v1 再退回声明顺序迁移。
                slotName = GuiLayout.legacySlotName(this.machine.snapshot(), this.machine.factoryTier(), oldSlot);
            }
            int target = -1;
            if (slotName != null) {
                target = this.handler.indexOf(slotName);
            } else if (version < 2 && this.guiIndices != null
                    && oldSlot >= 0 && oldSlot < this.guiIndices.size()) {
                target = this.guiIndices.get(oldSlot);
            } else if (oldSlot >= 0 && oldSlot < this.inventory.getSize()
                    && this.handler.kindAt(oldSlot) != null) {
                target = oldSlot;
            }
            if (target >= 0 && target < this.inventory.getSize()) {
                this.inventory.setItem(target, stack);
            } else {
                // 旧蓝图有、现蓝图已删除的槽（例如风力发电机的通用 in/out）：落回机器脚下。
                this.pendingDrops.add(stack);
            }
        }
        this.storageContents.refresh();
    }

    private void unregister() {
        if (this.registered) {
            this.plugin.scheduler().unregister(this.taskId);
            this.registered = false;
        }
    }

    private boolean infusionReady() {
        return this.infuseSlot >= 0
                && InfusionFeeder.canFeed(this.machine, this.inventory, this.infuseSlot);
    }

    private void feedInfusion() {
        if (this.infuseSlot >= 0
                && InfusionFeeder.feed(this.machine, this.inventory, this.infuseSlot)) {
            markChanged();
        }
    }

    /** 化学罐里的放射性总和（μSv/h）；上游 dumpRadiation 的口径，源强度现算。 */
    public double sourceMagnitude() {
        double total = 0.0;
        for (mekcore.machine.TankSpec spec : this.machine.blueprint().tanks()) {
            if (!spec.type().equals(mekcore.resource.ResourceTypes.CHEMICAL)) {
                continue;
            }
            mekcore.resource.ResourceTank tank = this.machine.tank(spec.name()).orElse(null);
            if (tank == null || tank.storedId() == null || tank.amount() <= 0) {
                continue;
            }
            Chemical chemical = Chemicals.registry().find(tank.storedId()).orElse(null);
            if (chemical != null) {
                total += Radiation.dose(chemical, tank.amount());
            }
        }
        return total;
    }

    public World world() {
        if (this.blockEntity.world == null) {
            return null;
        }
        try {
            var ceWorld = this.blockEntity.world.world();
            return ceWorld == null ? null : (World) ceWorld.platformWorld();
        } catch (RuntimeException e) {
            // 区块卸载过程中 CE 可能已经断开 world 引用；这里返回 null，卸载流程继续走清理。
            return null;
        }
    }

    /** 标记本机器自己的容器（点击/拖动要拦，以后 GUI 也要认），与别的插件的容器区分开。 */
    public static final class MachineHolder implements InventoryHolder {
        private Inventory inventory;
        private MachineBlockEntityController controller;

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }

        /** 这个容器是哪台机器的（事件里反查用）。 */
        public MachineBlockEntityController controller() {
            return this.controller;
        }
    }

    /**
     * 机器的世界级容器：槽位规则与核心一致——从这面能进就只露输入槽，
     * 能出就只露输出槽，升级槽永远不给自动化。`changed` 由 CE 的容器桥在物品变动时回调。
     */
    private static final class MachineStorageContainer extends BukkitWorldlyStorageContainer {
        private final MachineBlockEntityController controller;

        private MachineStorageContainer(MachineHolder holder, int size,
                                        MachineBlockEntityController controller) {
            super(holder, size, true, true);
            this.controller = controller;
        }

        @Override
        public int[] getSlotsForFace(net.momirealms.craftengine.core.util.Direction direction) {
            SideMode mode = side(direction);
            List<Integer> slots = new ArrayList<>();
            for (int index = 0; index < this.controller.inventory.getSize(); index++) {
                ItemSlotKind kind = this.controller.handler.kindAt(index);
                if (kind == ItemSlotKind.INPUT && mode.allowsInsert()
                        || (kind == ItemSlotKind.OUTPUT || PersonalStorageLogic.isStorage(this.controller.blockId)) && mode.allowsExtract()) {
                    slots.add(index);
                }
            }
            return slots.stream().mapToInt(Integer::intValue).toArray();
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, net.momirealms.craftengine.core.item.Item stack,
                                               net.momirealms.craftengine.core.util.Direction direction) {
            return this.controller.handler.kindAt(slot) == ItemSlotKind.INPUT
                    && side(direction).allowsInsert();
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, net.momirealms.craftengine.core.item.Item stack,
                                              net.momirealms.craftengine.core.util.Direction direction) {
            return (this.controller.handler.kindAt(slot) == ItemSlotKind.OUTPUT
                    || PersonalStorageLogic.isStorage(this.controller.blockId))
                    && side(direction).allowsExtract();
        }

        @Override
        public void setChanged() {
            this.controller.markChanged();
        }

        @Override
        public boolean isEmpty() {
            // 空白格的隐形占位物品不算内容；比较器/漏斗看到的是真正的机器物品。
            for (int index = 0; index < this.controller.inventory.getSize(); index++) {
                ItemStack stack = this.controller.inventory.getItem(index);
                if (stack != null && !stack.getType().isAir() && !GuiText.isOverlay(stack)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean stillValid(Player player) {
            return player.canInteractPoint(Vec3d.atCenterOf(this.controller.blockEntity.pos),
                    player.getCachedInteractionRange());
        }

        private SideMode side(net.momirealms.craftengine.core.util.Direction direction) {
            return this.controller.machine.itemSide(mekcore.machine.Direction.valueOf(direction.name()));
        }
    }
}
