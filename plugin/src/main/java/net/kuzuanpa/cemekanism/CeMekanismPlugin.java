package net.kuzuanpa.cemekanism;

import mekcore.api.MekCore;
import mekcore.sim.SchedulerConfig;
import mekcore.sim.TickScheduler;
import net.kuzuanpa.cemekanism.generated.GeneratedContent;
import net.kuzuanpa.cemekanism.gui.GuiSessions;
import net.kuzuanpa.cemekanism.gui.TankDumpGui;
import net.kuzuanpa.cemekanism.i18n.I18n;
import net.kuzuanpa.cemekanism.machine.ConfiguratorOverlay;
import net.kuzuanpa.cemekanism.machine.MachineBlockBehavior;
import net.kuzuanpa.cemekanism.machine.MachineBlockEntityController;
import net.kuzuanpa.cemekanism.machine.MachineContainerListener;
import net.kuzuanpa.cemekanism.machine.RecipeBrowserGui;
import net.kuzuanpa.cemekanism.machine.RecipeBrowserListener;
import net.kuzuanpa.cemekanism.machine.ConfiguratorListener;
import net.kuzuanpa.cemekanism.machine.MachineSecurityListener;
import net.kuzuanpa.cemekanism.machine.UpgradeInstallerListener;
import net.kuzuanpa.cemekanism.multiblock.MultiblockBlockBehavior;
import net.kuzuanpa.cemekanism.multiblock.MultiblockBlockEntityController;
import net.kuzuanpa.cemekanism.multiblock.MultiblockConfig;
import net.kuzuanpa.cemekanism.multiblock.MultiblockDiagnosticParticles;
import net.kuzuanpa.cemekanism.multiblock.MultiblockHosts;
import net.kuzuanpa.cemekanism.multiblock.MultiblockListener;
import net.kuzuanpa.cemekanism.multiblock.TeleporterFrequencyGui;
import net.kuzuanpa.cemekanism.nuclear.WasteBarrelListener;
import net.kuzuanpa.cemekanism.platform.GeneratorConfig;
import net.kuzuanpa.cemekanism.platform.MekSystems;
import net.kuzuanpa.cemekanism.radiation.RadiationConfig;
import net.kuzuanpa.cemekanism.radiation.RadiationListener;
import net.kuzuanpa.cemekanism.radiation.RadiationStore;
import net.kuzuanpa.cemekanism.radiation.RadiationWorld;
import net.kuzuanpa.cemekanism.radiation.RadiationWorlds;
import net.kuzuanpa.cemekanism.storage.CardboardBox;
import net.kuzuanpa.cemekanism.storage.QioService;
import net.kuzuanpa.cemekanism.storage.StorageCrafting;
import net.kuzuanpa.cemekanism.storage.StorageListener;
import net.kuzuanpa.cemekanism.transmitter.Logistics;
import net.kuzuanpa.cemekanism.transmitter.LogisticsConfig;
import net.kuzuanpa.cemekanism.transmitter.LogisticsWorlds;
import net.kuzuanpa.cemekanism.transmitter.TransmitterBlockBehavior;
import net.kuzuanpa.cemekanism.transmitter.TransmitterBlockEntityController;
import net.kuzuanpa.cemekanism.transmitter.TransmitterListener;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.EmptyBlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 平台侧接线（文档 03）：核心实例 + 刻调度器 + 三个适配器（存档/物品/刻）。
 * 内容（物品/方块/配方/机器数值）全在资源包里，机器行为挂在 {@link MachineBlockBehavior} 上。
 */
public final class CeMekanismPlugin extends JavaPlugin {

    // 内容表登记进核心（GeneratedContent 由 tools/mek.py 生成）：物品名 / 化学品 / 机器蓝图
    private final MekCore core = MekCore.create(GeneratedContent::register);
    private final TickScheduler scheduler = this.core.factories().scheduler(SchedulerConfig.defaults());
    private final MultiblockHosts multiblocks = new MultiblockHosts(this);
    private final LogisticsWorlds logistics = new LogisticsWorlds(this);
    private final RadiationWorlds radiation = new RadiationWorlds(this);
    private final GuiSessions guiSessions = new GuiSessions(this);
    private final Set<RecipeBrowserGui> recipeBrowsers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public void trackRecipeBrowser(RecipeBrowserGui gui) { this.recipeBrowsers.add(gui); }
    public void forgetRecipeBrowser(RecipeBrowserGui gui) { this.recipeBrowsers.remove(gui); }
    private final ConfiguratorOverlay configuratorOverlay =
            new ConfiguratorOverlay(this);

    private final QioService qio = new QioService(this);
    public QioService qio() { return qio; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        I18n.install(this);
        // 系统参数（管道/储罐/储能/蒸汽/热量）先装：机器读的就是这份表
        MekSystems.install(this);
        this.qio.install();
        GeneratorConfig.install(this);
        RadiationConfig.install(this);
        RadiationStore.install(this);
        MultiblockConfig.install(this);
        LogisticsConfig.install(this);
        // 机器方块的行为：必须在 CE 载包之前注册（delay-configuration-load 默认为 true，所以来得及）
        BlockBehaviors.register(MachineBlockBehavior.KEY, MachineBlockBehavior.factory(this));
        BlockBehaviors.register(MultiblockBlockBehavior.KEY, MultiblockBlockBehavior.factory(this));
        BlockBehaviors.register(TransmitterBlockBehavior.KEY, TransmitterBlockBehavior.factory(this));
        Bukkit.getPluginManager().registerEvents(new StorageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CardboardBox(this), this);
        Bukkit.getPluginManager().registerEvents(new StorageCrafting(), this);
        Bukkit.getPluginManager().registerEvents(new WasteBarrelListener(this), this);
        this.scheduler.start();
        Bukkit.getScheduler().runTaskTimer(this, this.guiSessions::tick, 1L, 1L);
        // 主线程泵：每个服务端刻一次，MAIN 车道的任务由它推
        Bukkit.getScheduler().runTaskTimer(this, this.scheduler::pumpMainThread, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this.multiblocks::tickEffects, 1L, 2L);
        Bukkit.getPluginManager().registerEvents(new RegistrationCheck(this), this);
        // 机器容器、独立升级安装器与配置器的交互接管
        Bukkit.getPluginManager().registerEvents(new MachineContainerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TankDumpGui.Controls(), this);
        Bukkit.getPluginManager().registerEvents(new RecipeBrowserListener(), this);
        Bukkit.getPluginManager().registerEvents(new UpgradeInstallerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ConfiguratorListener(this), this);
        Bukkit.getPluginManager().registerEvents(this.configuratorOverlay, this);
        Bukkit.getScheduler().runTaskTimer(this, this.configuratorOverlay::tick, 1L, 2L);
        Bukkit.getPluginManager().registerEvents(new MachineSecurityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MultiblockListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TeleporterFrequencyGui.Events(), this);
        Bukkit.getPluginManager().registerEvents(new TransmitterListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DisplayCleanupListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RadiationListener(this), this);
        PluginCommand command = getCommand("mek");
        if (command != null) {
            command.setExecutor(new MekCommand(this));
        }
        PluginCommand recipeCommand = getCommand("mekrecipes");
        if (recipeCommand != null) recipeCommand.setExecutor(new MekCommand(this));
        getLogger().info(I18n.tr("plugin.enabled", this.scheduler.config().threads()));
        getLogger().info(this.core.summary());
    }

    @Override
    public void onDisable() {
        MultiblockDiagnosticParticles.close();
        Bukkit.getScheduler().cancelTasks(this);
        for (var browser : List.copyOf(this.recipeBrowsers)) browser.closeViewers();
        this.recipeBrowsers.clear();
        this.guiSessions.closeAll();
        this.configuratorOverlay.close();
        this.multiblocks.closeEffects();
        // 先停线程再摘任务：反过来的话，主线程在改注册表，CORE 线程可能正遍历它（见 10 的 P24）
        this.scheduler.stop();
        this.logistics.stopAll();
        this.radiation.stopAll();
        RadiationStore.flush();
    }

    public MekCore core() {
        return this.core;
    }

    public GuiSessions guiSessions() {
        return this.guiSessions;
    }

    public TickScheduler scheduler() {
        return this.scheduler;
    }

    public MultiblockHosts multiblocks() {
        return this.multiblocks;
    }

    /** 一个世界一份物流（文档 06）：管道、端点、热网、电网都挂在它上面。 */
    public Logistics logistics(World world) {
        return this.logistics.of(world);
    }

    /** 一个世界一份辐射账（源登记、玩家剂量、四档效果）。 */
    public RadiationWorld radiation(World world) {
        return this.radiation.of(world);
    }

    public RadiationWorlds radiationAll() {
        return this.radiation;
    }

    /** 按坐标找回多方块的锚点控制器（命令与自检用）。 */
    public MultiblockBlockEntityController multiblockAt(World world, int x, int y, int z) {
        CEWorld ceWorld = BukkitAdaptor.adapt(world).storageWorld();
        // 先把世界真值同步进 CE 的区块缓存：CraftEngineBlocks.place 只写世界，
        // 不刷新的话 createBlockEntity 会按旧状态（hasBlockEntity=false）建实体（台账 P29）。
        syncCeState(ceWorld, world, x, y, z);
        BlockEntity entity = ceWorld.getBlockEntityAtIfLoaded(new BlockPos(x, y, z));
        return entity == null ? null : entity.controller.get(MultiblockBlockEntityController.class, 0);
    }

    private static void syncCeState(CEWorld ceWorld, World world, int x, int y, int z) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(world.getBlockAt(x, y, z));
        ceWorld.setBlockStateAtIfLoaded(new BlockPos(x, y, z),
                state == null ? EmptyBlockDefinition.STATE : state);
    }

    /**
     * 世界侧记账 2：按坐标找回机器控制器（命令、事件都用它）。
     * 方块带 facing 这类属性时 CE 会再挂一个硬编码属性行为，控制器因此有两个（我们在第 2 位），
     * 所以这里按类型搜，不写死 index 0。
     */
    public MachineBlockEntityController machineAt(World world, int x, int y, int z) {
        return machineAt(world, x, y, z, false);
    }

    /** 邻居扫描用的安静版本：不是机器、方块实体还没建起来都只回 null，不刷日志。 */
    public MachineBlockEntityController machineAtQuiet(World world, int x, int y, int z) {
        return machineAt(world, x, y, z, true);
    }

    private MachineBlockEntityController machineAt(World world, int x, int y, int z, boolean quiet) {
        // 未装载的区块一律不探测。world.getBlockAt() 内部走 Level#getChunk，
        // 在区块卸载回调（CEChunk.deactivateAllBlockEntities → MachineBlockEntityController.onUnload
        // → Logistics.detachMachine → refreshAround → reconcile → machineAtQuiet）里调用，
        // 会把刚卸载的区块同步拉回来，形成 卸载→加载→卸载 的抖动，主线程整段挂在 chunk 系统上。
        // 与 Logistics#transmitterAt / #heatPartnerAt 的 chunkLoaded 守卫保持同一口径。
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        // 区块确认已装载后，再摸一下这个方块：CE 的区块数据是按需装载的，直接查 loadedChunkMap 会扑空（方块实体还没建起来）
        CraftEngineBlocks.getCustomBlockState(world.getBlockAt(x, y, z));
        CEWorld ceWorld = BukkitAdaptor.adapt(world).storageWorld();
        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity entity = ceWorld.getBlockEntityAtIfLoaded(pos);
        if (entity == null) {
            if (quiet) {
                return null;
            }
            CEChunk chunk = ceWorld.getChunkAtIfLoaded(x >> 4, z >> 4);
            getLogger().info(I18n.tr("plugin.entity.waiting", x, y, z,
                    chunk == null ? I18n.tr("plugin.entity.chunk_absent")
                            : I18n.tr("plugin.entity.chunk_state",
                            chunk.isActivated(), chunk.blockEntities().size())));
            return null;
        }
        MachineBlockEntityController controller =
                entity.controller.get(MachineBlockEntityController.class, 0);
        if (controller == null && !quiet) {
            getLogger().warning(I18n.tr("plugin.controller.not_machine",
                    x, y, z, entity.controller.getClass().getName()));
        }
        return controller;
    }

    /** 按坐标找回元件控制器（管道、线缆、物流管道；命令与邻居扫描都用它）。 */
    public TransmitterBlockEntityController transmitterAt(World world, int x, int y, int z) {
        CraftEngineBlocks.getCustomBlockState(world.getBlockAt(x, y, z));
        CEWorld ceWorld = BukkitAdaptor.adapt(world).storageWorld();
        BlockEntity entity = ceWorld.getBlockEntityAtIfLoaded(new BlockPos(x, y, z));
        return entity == null ? null : entity.controller.get(TransmitterBlockEntityController.class, 0);
    }

    /** 排错一张纸：这个坐标上 Bukkit 看到什么、CE 看到什么、方块实体在不在、调度器认不认识。 */
    public String describePos(World world, int x, int y, int z) {
        org.bukkit.block.Block bukkit = world.getBlockAt(x, y, z);
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(bukkit);
        CEWorld ceWorld = BukkitAdaptor.adapt(world).storageWorld();
        syncCeState(ceWorld, world, x, y, z);
        CEChunk chunk = ceWorld.getChunkAtIfLoaded(x >> 4, z >> 4);
        BlockEntity entity = ceWorld.getBlockEntityAtIfLoaded(new BlockPos(x, y, z));
        MachineBlockEntityController controller = machineAt(world, x, y, z);
        String chunkText = chunk == null ? I18n.tr("plugin.position.chunk_unloaded")
                : I18n.tr("plugin.position.chunk_loaded", chunk.isActivated(),
                chunk.blockEntities().size());
        String entityText = entity == null ? I18n.tr("state.none")
                : I18n.tr("plugin.position.entity", entity.isValid(),
                entity.controller.getClass().getSimpleName());
        String controllerText = controller == null ? I18n.tr("plugin.position.controller_absent")
                : I18n.tr(controller.registered() ? "machine.registered" : "machine.unregistered",
                controller.taskId());
        return I18n.tr("plugin.position.describe", bukkit.getType(),
                state == null ? I18n.tr("plugin.position.not_custom")
                        : I18n.tr("plugin.position.custom",
                        state.owner().value().id(), state.hasBlockEntity()),
                chunkText, entityText, controllerText);
    }
}
