package net.kuzuanpa.cemekanism.transmitter;

import mekcore.chemical.Chemical;
import mekcore.chemical.Chemicals;
import mekcore.heat.HeatConfig;
import mekcore.heat.HeatNode;
import mekcore.heat.HeatTier;
import mekcore.item.ItemNode;
import mekcore.logistics.PortMode;
import mekcore.logistics.TransmitterNode;
import mekcore.logistics.TransmitterNetwork;
import mekcore.logistics.TransmitterTier;
import mekcore.logistics.topology.TransmitterTopology;
import mekcore.machine.Direction;
import mekcore.resource.ResourceTypes;
import mekcore.spec.V10Tables;
import net.kuzuanpa.cemekanism.CeMekanismPlugin;
import net.kuzuanpa.cemekanism.i18n.I18n;
import net.kuzuanpa.cemekanism.nuclear.WasteBarrelLogic;
import net.kuzuanpa.cemekanism.platform.CompoundDataTag;
import net.kuzuanpa.cemekanism.storage.StoredItems;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Locale;

/**
 * 一根元件的方块实体（文档 06 第二节）：只做三件事——存自己的元件状态、把加载/卸载/拆除告诉
 * {@link Logistics}、把玩家改的面模式写进元件。
 * 逐刻搬运全在核心的网络里（CORE 车道），这里没有 ticker。
 */
public final class TransmitterBlockEntityController extends BlockEntityController {

    private static final String ROOT_KEY = "cemekanism:transmitter";
    private static final String STATE_KEY = "state";
    private static final String HEAT_KEY = "temperature_k";

    private final CeMekanismPlugin plugin;
    private final TransmitterDef def;
    private final String key;
    private final boolean itemPipe;
    private final boolean heatConductor;
    private final boolean energy;

    private TransmitterNode node;
    private ItemNode itemNode;
    private HeatNode heatNode;
    private boolean placed;
    private TransmitterArmRenderer armRenderer;

    public TransmitterBlockEntityController(BlockEntity blockEntity, CeMekanismPlugin plugin) {
        super(blockEntity);
        this.plugin = plugin;
        this.def = TransmitterBlocks.require(blockEntity.blockState().owner().value().id().asString());
        this.key = blockEntity.pos.x() + "," + blockEntity.pos.y() + "," + blockEntity.pos.z();
        this.itemPipe = this.def.itemPipe();
        this.heatConductor = this.def.heat();
        if (this.itemPipe) {
            this.itemNode = plugin.core().factories().itemNode(this.def.blockId(), TransmitterBlocks.tier(this.def));
            this.energy = false;
        } else {
            TransmitterTier tier = TransmitterBlocks.transmitterTier(this.def);
            this.node = plugin.core().factories().node(this.def.blockId(), tier.family(), tier.tier());
            this.energy = tier.family().resource().equals(ResourceTypes.ENERGY);
            this.heatNode = this.heatConductor
                    ? HeatNode.atAmbient(this.key, LogisticsConfig.heatConductorCapacity()) : null;
        }
    }

    public TransmitterDef def() {
        return this.def;
    }

    public String key() {
        return this.key;
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

    public boolean itemPipe() {
        return this.itemPipe;
    }

    public boolean heat() {
        return this.heatConductor;
    }

    public boolean isEnergy() {
        return this.energy;
    }

    public TransmitterNode node() {
        return this.node;
    }

    public ItemNode itemNode() {
        return this.itemNode;
    }

    public HeatNode heatNode() {
        return this.heatNode;
    }

    public HeatTier heatTier() {
        return V10Tables.heatTier(TransmitterBlocks.tier(this.def));
    }

    /** 元件所属的资源族；物品管道没有族（它单开一条管线）。 */
    public mekcore.logistics.TransmitterFamily family() {
        return this.itemPipe ? null : this.node.tier().family();
    }

    /** 资源族与能量族的拓扑（物品与热量不在这里）。 */
    public TransmitterTopology topology() {
        Logistics logistics = logistics();
        if (logistics == null) {
            throw new IllegalStateException(I18n.tr("transmitter.error.no_world", this.key));
        }
        return this.energy ? logistics.energyTopology() : logistics.resourceTopology();
    }

    // ────────────────────────────── 六个面与状态

    public PortMode port(Direction direction) {
        return this.itemPipe ? this.itemNode.port(direction) : this.node.port(direction);
    }

    /**
     * 这一个面当前该怎么画：没有连接/面模式为关就不画；否则按 NORMAL/PUSH/PULL 选单臂模型。
     * 模型来自上游 transmitter_small.obj 的三个臂（normal / push / pull）。
     */
    String armPart(Direction direction) {
        PortMode mode = port(direction);
        if (mode == PortMode.NONE || !hasNeighbour(direction)) {
            return null;
        }
        return switch (mode) {
            case NORMAL -> "normal";
            case PUSH -> "push";
            case PULL -> "pull";
            case NONE -> null;
        };
    }

    String renderItemId(String part, boolean withContents) {
        String suffix = withContents || this.itemPipe ? "" : "_empty";
        return "mek:render/" + this.def.family() + '_' + this.def.tier().toLowerCase(Locale.ROOT)
                + '_' + part + suffix;
    }

    /** 内容层的颜色/亮度只看网络与温度；物流管道按性能口径永远不画内容。 */
    public TransmitterVisual visual() {
        if (this.itemPipe || !LogisticsConfig.transmitterContentsEnabled()) {
            return TransmitterVisual.NONE;
        }
        if (this.heatConductor) {
            return heatVisual();
        }
        TransmitterNetwork network = topology().networkOf(this.key);
        if (network == null || network.stored() <= 0) {
            return TransmitterVisual.NONE;
        }
        String resourceId = networkResourceId(network);
        if (resourceId == null) {
            resourceId = family().resource().id();
        }
        int color;
        if (family().resource().equals(ResourceTypes.ENERGY)) {
            color = LogisticsConfig.energyContentsColor();
        } else if (family().resource().equals(ResourceTypes.FLUID)) {
            color = LogisticsConfig.fluidContentsColor(resourceId);
        } else if (family().resource().equals(ResourceTypes.CHEMICAL)) {
            color = chemicalColor(resourceId);
        } else {
            color = 0xFFFFFF;
        }
        return visual(color, network.stored(), network.capacity(), resourceId);
    }

    private TransmitterVisual heatVisual() {
        double ambient = HeatConfig.current().ambientK();
        double temperature = this.heatNode.temperatureK();
        if (temperature <= ambient + 0.01) {
            return TransmitterVisual.NONE;
        }
        return visual(LogisticsConfig.heatContentsColor(), 1, 1, "heat");
    }

    private static TransmitterVisual visual(int rgb, double amount, double capacity, String resourceId) {
        double ratio = capacity <= 0 ? 0.0 : Math.clamp(amount / capacity, 0.0, 1.0);
        double brightness = LogisticsConfig.transmitterMinBrightness()
                + (1.0 - LogisticsConfig.transmitterMinBrightness()) * ratio;
        return visualAtBrightness(rgb, brightness, resourceId);
    }

    private static TransmitterVisual visualAtBrightness(int rgb, double brightness, String resourceId) {
        int steps = LogisticsConfig.transmitterBrightnessSteps();
        brightness = Math.round(brightness * steps) / (double) steps;
        int factor = (int) Math.round(brightness * 255.0);
        int red = ((rgb >> 16) & 0xFF) * factor / 255;
        int green = ((rgb >> 8) & 0xFF) * factor / 255;
        int blue = (rgb & 0xFF) * factor / 255;
        int scaled = (red << 16) | (green << 8) | blue;
        return new TransmitterVisual(true, scaled, (int) Math.round(brightness * 100.0), resourceId);
    }

    private static String networkResourceId(TransmitterNetwork network) {
        for (TransmitterNode member : network.nodes()) {
            String id = member.buffer().resourceId();
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static int chemicalColor(String resourceId) {
        Chemical chemical = resourceId == null ? null
                : Chemicals.registry().find(resourceId).orElse(null);
        return chemical == null || chemical.colorArgb() == Chemical.NO_COLOR
                ? Chemical.NO_COLOR : chemical.colorArgb() & 0xFFFFFF;
    }

    private boolean hasNeighbour(Direction direction) {
        World world = world();
        if (world == null) {
            return false;
        }
        int[] offset = switch (direction) {
            case DOWN -> new int[] {0, -1, 0};
            case UP -> new int[] {0, 1, 0};
            case NORTH -> new int[] {0, 0, -1};
            case SOUTH -> new int[] {0, 0, 1};
            case WEST -> new int[] {-1, 0, 0};
            case EAST -> new int[] {1, 0, 0};
        };
        int nx = x() + offset[0];
        int ny = y() + offset[1];
        int nz = z() + offset[2];
        if (world.getBlockAt(nx, ny, nz).getType().isAir()) {
            return false;
        }
        TransmitterBlockEntityController neighbour = this.plugin.transmitterAt(world, nx, ny, nz);
        if (neighbour != null) {
            if (!channelMatches(neighbour)) {
                return false;
            }
            if (this.itemPipe) {
                return neighbour.itemPipe();
            }
            if (this.heatConductor) {
                return neighbour.heat();
            }
            return neighbour.family() != null && family() != null
                    && neighbour.family().id().equals(family().id());
        }
        var machine = this.plugin.machineAtQuiet(world, nx, ny, nz);
        if (machine != null) {
            // direction 是"从这根线缆指向机器"；机器面对线缆的那一面是它的反向。
            // 端点绑定时用的也是这个反向（Logistics.bindMachine*），这里必须同一口径，
            // 否则手臂会画在端点对面（表现为"线缆连不上"或"方向相反"）。
            Direction machineSide = direction.opposite();
            if (WasteBarrelLogic.ID.equals(machine.blockId())
                    && machineSide != Direction.UP && machineSide != Direction.DOWN) {
                return false;
            }
            if (this.itemPipe) {
                return machine.machine().itemSide(machineSide) != mekcore.machine.SideMode.NONE;
            }
            if (this.heatConductor) {
                return machine.machine().heatNode() != null;
            }
            if (this.isEnergy()) {
                return machine.machine().energyTank().isPresent()
                        && Logistics.energySideAllowed(machine.machine().blueprint().id(),
                        machine.machine().side("energy", machineSide));
            }
            var wanted = family().resource();
            for (String port : machine.machine().portNames()) {
                if (machine.machine().tank(port).filter(tank -> tank.type().equals(wanted)).isPresent()
                        && machine.machine().side(port, machineSide) != mekcore.machine.SideMode.NONE) {
                    return true;
                }
            }
            return false;
        }
        var host = this.plugin.multiblocks().at(world, nx, ny, nz);
        if (host != null && host.formed()) {
            if (this.itemPipe) {
                return false;
            }
            if (this.heatConductor) return host.isHeatPortAt(nx, ny, nz);
            for (String port : host.def().ports()) {
                if (!host.isPortAt(port, nx, ny, nz)) {
                    continue;
                }
                if (this.heatConductor) {
                    if (host.instance().heatNode() != null) {
                        return true;
                    }
                } else if (host.instance().tank(port).type().equals(family().resource())) {
                    String flow = host.portFlowAt(port, nx, ny, nz);
                    if (this.isEnergy()) {
                        // 聚焦矩阵只吃激光束；能量线缆只接真正的多方块能量输入口（SPS 线圈）。
                        if (("out".equals(flow) || "in".equals(flow) || "store".equals(flow))
                                && !"laser_focus".equals(host.def().portRole(port))) {
                            return true;
                        }
                    } else if (!Logistics.flowDirections(flow, this.port(direction)).isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void refreshRenderer() {
        if (this.armRenderer == null && world() != null) {
            // 有些载体（scaffolding）的 onLoad 可能早于 world 绑定；第一次 reconcile 时补建。
            this.armRenderer = new TransmitterArmRenderer(this);
        }
        if (this.armRenderer != null) {
            this.armRenderer.refresh();
        }
    }

    void refreshContents() {
        if (this.armRenderer != null) {
            this.armRenderer.refreshContents();
        }
    }

    /** 颜色频道是否合得来：未设色与任何颜色都能连。 */
    public boolean channelMatches(TransmitterBlockEntityController other) {
        // Rapid bulk placement can expose the adjacent CE entity before its node has been
        // installed. Treat that half-initialized controller as disconnected for this render
        // pass; the later reconcile will draw the arm once both nodes exist.
        if (this.itemPipe) {
            if (this.itemNode == null || other.itemNode == null) {
                return false;
            }
            return channelMatches(this.itemNode.channel(), other.itemNode.channel());
        }
        if (this.node == null || other.node == null) {
            return false;
        }
        return this.node.channelMatches(other.node.channel());
    }

    private static boolean channelMatches(int self, int other) {
        return self == ItemNode.CHANNEL_UNSET || other == ItemNode.CHANNEL_UNSET || self == other;
    }

    /** 配置器右击：循环这个面的模式，然后把这个面重算一遍（连边与端点方向都跟着变）。 */
    public PortMode cyclePort(Direction direction) {
        PortMode mode;
        if (this.itemPipe) {
            mode = this.itemNode.port(direction).next();
            this.itemNode.setPort(direction, mode);
        } else {
            mode = this.node.cyclePort(direction);
        }
        markChanged();
        syncSelf();
        return mode;
    }

    /** 配置器红石功能右击：红石响应开关（开着的时候这个元件会被那条节流任务扫）。 */
    public boolean toggleRedstone() {
        boolean enable = this.itemPipe ? !this.itemNode.redstoneResponsive() : !this.node.redstoneControlEnabled();
        if (this.itemPipe) {
            this.itemNode.setRedstoneResponsive(enable);
        } else {
            this.node.setRedstoneControlEnabled(enable);
        }
        Logistics logistics = logistics();
        if (logistics != null) {
            logistics.watchRedstone(this, enable);
            logistics.wake();
        }
        markChanged();
        return enable;
    }

    public boolean redstoneWatched() {
        return this.itemPipe ? this.itemNode.redstoneResponsive() : this.node.redstoneControlEnabled();
    }

    /** 红石真值：由物流的那条 MAIN 节流任务来问（元件自己没有刻任务）。 */
    void refreshRedstone() {
        World world = world();
        if (world == null) {
            return;
        }
        boolean powered = world.getBlockAt(x(), y(), z()).isBlockPowered();
        if (this.itemPipe) {
            this.itemNode.setPowered(powered);
        } else {
            this.node.setPowered(powered);
        }
    }

    public String nodeSummary() {
        if (this.itemPipe) {
            int channel = this.itemNode.channel();
            return I18n.tr("transmitter.node.item", this.itemNode.tier(),
                    this.itemNode.bufferedAmount(), this.itemNode.bufferCapacity(),
                    channelName(channel),
                    I18n.tr(this.itemNode.redstoneResponsive() ? "state.on" : "state.off"));
        }
        if (this.heatConductor) {
            return I18n.tr("transmitter.node.heat",
                    I18n.enumName(heatTier(), heatTier().zhName()),
                    String.format("%.2f", heatTier().lossPerFourBlocks() * 100),
                    String.format("%.2f", this.heatNode.temperatureK()),
                    this.heatNode.usableJoules());
        }
        return I18n.tr("transmitter.node.resource", this.node.tier(),
                this.node.buffer().amount(), this.node.buffer().capacity(),
                channelName(this.node.channel()),
                I18n.tr(this.node.redstoneControlEnabled()
                        ? "transmitter.redstone_high" : "transmitter.redstone_low"));
    }

    private static String channelName(int channel) {
        return channel < 0 ? I18n.tr("transmitter.channel.unset")
                : I18n.tr("transmitter.channel.number", channel + 1);
    }

    private Logistics logistics() {
        World world = world();
        return world == null ? null : this.plugin.logistics(world);
    }

    World world() {
        return this.blockEntity.world == null ? null
                : (World) this.blockEntity.world.world().platformWorld();
    }

    public void markChanged() {
        if (this.blockEntity.world != null) {
            this.blockEntity.world.blockEntityChanged(this.blockEntity.pos);
        }
    }

    /** 六个面重算（放置、邻居变化、配置器改完、读档之后都走它）。 */
    public void syncSelf() {
        Logistics logistics = logistics();
        if (logistics == null || !this.placed) {
            return;
        }
        logistics.reconcile(this);
        refreshRenderer();
    }

    // ────────────────────────────── 生命周期

    @Override
    public void onLoad() {
        Logistics logistics = logistics();
        if (logistics == null) {
            return;
        }
        this.placed = true;
        logistics.place(this);
        if (redstoneWatched()) {
            logistics.watchRedstone(this, true);
        }
        if (this.armRenderer == null) {
            this.armRenderer = new TransmitterArmRenderer(this);
        }
        this.armRenderer.spawn();
        // onLoad 可能早于 CE 完成世界绑定；下一拍再刷一次，确保 ItemDisplay 真的生成。
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (this.placed && this.armRenderer != null) {
                this.armRenderer.spawn();
            }
        });
    }

    /** 只移除手臂/中心显示实体；幂等，供方块破坏事件兜底调用。 */
    public void removeDisplays() {
        if (this.armRenderer != null) {
            this.armRenderer.remove();
            this.armRenderer = null;
        }
    }

    @Override
    public void onUnload() {
        // 显示实体先清掉：logistics 不可用时也不能把模型留在世界里。
        removeDisplays();
        Logistics logistics = logistics();
        if (logistics == null) {
            return;
        }
        logistics.setLoaded(this, false);
        logistics.watchRedstone(this, false);
        this.placed = false;
    }

    @Override
    public void onRemove() {
        removeDisplays();
        Logistics logistics = logistics();
        if (logistics == null) {
            return;
        }
        logistics.remove(this);
        this.placed = false;
    }

    // ────────────────────────────── 存档

    @Override
    public void saveCustomData(CompoundTag root) {
        CompoundTag mine = new CompoundTag();
        CompoundTag state = new CompoundTag();
        if (this.itemPipe) {
            this.itemNode.save(new CompoundDataTag(state));
            var saved = state.getList("buffer");
            var buffer = this.itemNode.buffer();
            for (int i = 0; i < buffer.size(); i++) {
                if (buffer.get(i).payload() instanceof org.bukkit.inventory.ItemStack stack) {
                    saved.getCompound(i).putString("payload", StoredItems.key(stack));
                }
            }
        } else {
            this.node.save(new CompoundDataTag(state));
        }
        mine.put(STATE_KEY, state);
        if (this.heatNode != null) {
            mine.putDouble(HEAT_KEY, this.heatNode.temperatureK());
        }
        root.put(ROOT_KEY, mine);
    }

    @Override
    public void loadCustomData(CompoundTag root) {
        CompoundTag mine = root.getCompound(ROOT_KEY);
        if (mine == null) {
            return;                                       // 刚放下的元件：还没有存档
        }
        CompoundTag state = mine.getCompound(STATE_KEY);
        if (state != null) {
            try {
                if (this.itemPipe) {
                    this.itemNode = ItemNode.load(new CompoundDataTag(state));
                    var saved = state.getList("buffer");
                    if (saved != null) {
                        this.itemNode.clearBuffer();
                        for (int i = 0; i < saved.size(); i++) {
                            var row = saved.getCompound(i); String payload = row.getString("payload");
                            this.itemNode.insert(mekcore.item.ItemRef.withPayload(row.getString("id"), row.getLong("amount"),
                                    payload == null ? null : StoredItems.item(payload, 1)));
                        }
                    }
                } else {
                    this.node = TransmitterNode.load(new CompoundDataTag(state));
                }
            } catch (RuntimeException e) {
                this.plugin.getLogger().warning(I18n.tr("transmitter.log.load_failed",
                        this.key, e.getMessage()));
            }
        }
        if (this.heatNode != null) {
            this.heatNode.setTemperatureK(mine.getDouble(HEAT_KEY, this.heatNode.temperatureK()));
        }
    }
}
