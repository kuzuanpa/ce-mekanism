package net.kuzuanpa.cemekanism;

import net.kuzuanpa.cemekanism.machine.MachineBlockEntityController;
import net.kuzuanpa.cemekanism.transmitter.TransmitterBlockEntityController;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * 方块被拆掉时兜底清理显示实体。
 *
 * <p>CraftEngine 只在区块卸载路径里稳定回调 {@code onUnload}；玩家挖掉方块时
 * 机器/管道的 ItemDisplay（风力叶片、能量核心、管道手臂等）不一定会跟着消失。
 * 这里在破坏/爆炸事件里先拿到控制器，把显示实体清掉，避免“方块没了模型还在”。
 *
 * <p>只清显示实体，不改存档/容器；和 {@code onUnload}/{@code onRemove} 里的清理幂等。
 */
public final class DisplayCleanupListener implements Listener {

    private final CeMekanismPlugin plugin;

    public DisplayCleanupListener(CeMekanismPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBreak(CustomBlockBreakEvent event) {
        cleanup(event.bukkitBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::cleanup);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::cleanup);
    }

    private void cleanup(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        TransmitterBlockEntityController transmitter = this.plugin.transmitterAt(block.getWorld(),
                block.getX(), block.getY(), block.getZ());
        if (transmitter != null) {
            transmitter.removeDisplays();
            return;
        }
        MachineBlockEntityController machine = this.plugin.machineAtQuiet(block.getWorld(),
                block.getX(), block.getY(), block.getZ());
        if (machine != null) {
            machine.removeDisplays();
        }
    }
}
