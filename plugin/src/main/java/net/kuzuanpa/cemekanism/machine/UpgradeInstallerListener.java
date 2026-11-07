package net.kuzuanpa.cemekanism.machine;

import mekcore.upgrade.UpgradeDefinition;
import net.kuzuanpa.cemekanism.CeMekanismPlugin;
import net.kuzuanpa.cemekanism.platform.BukkitItems;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** 升级安装器物品与独立升级界面的点击/拖动接管。 */
public final class UpgradeInstallerListener implements Listener {

    private static final String UPGRADE_INSTALLER = "mek:upgrade_installer";

    private final CeMekanismPlugin plugin;

    public UpgradeInstallerListener(CeMekanismPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || !UPGRADE_INSTALLER.equals(BukkitItems.idOf(held))) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        MachineBlockEntityController controller = this.plugin.machineAt(block.getWorld(),
                block.getX(), block.getY(), block.getZ());
        if (controller == null) {
            return;
        }
        event.setCancelled(true);
        controller.openUpgradeInstaller(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        UpgradeInstallerGui gui = UpgradeInstallerGui.of(event.getInventory());
        if (gui == null) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!gui.controller().machine().canAccess(player.getUniqueId().toString())) {
            player.closeInventory();
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (raw < 0 || raw >= topSize) {
            // 玩家背包：保留原版点击语义，否则左键拿不起升级物品，没法再点到升级槽里。
            // 只拦截“Shift 点击升级物品”这一种，直接安装。
            if (event.isShiftClick()
                    && event.getClickedInventory() instanceof org.bukkit.inventory.PlayerInventory) {
                // Shift 点击背包：升级物品直接安装；其他物品也拦掉，避免被原版塞进只读的升级界面。
                event.setCancelled(true);
                ItemStack source = event.getCurrentItem();
                if (gui.definitionOf(source) != null) {
                    gui.installFromPlayerSlot(player, event.getSlot());
                }
            }
            return;
        }
        event.setCancelled(true);
        if (raw >= 0 && raw < topSize) {
            UpgradeDefinition definition = gui.definitionAt(raw);
            if (definition == null) {
                return;
            }
            if (event.isShiftClick()) {
                gui.withdrawAllToInventory(player, definition);
                return;
            }
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
                return;
            }
            ItemStack cursor = player.getItemOnCursor();
            if (cursor == null || cursor.getType().isAir()) {
                gui.withdrawToCursor(player, definition, event.getClick() == ClickType.RIGHT ? 1
                        : gui.controller().machine().upgrades().count(definition.id()));
            } else {
                gui.installFromCursor(player, definition);
            }
            return;
        }

    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        UpgradeInstallerGui gui = UpgradeInstallerGui.of(event.getInventory());
        if (gui == null) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < top.getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UpgradeInstallerGui gui = UpgradeInstallerGui.of(event.getInventory());
        if (gui != null) {
            gui.controller().forgetUpgradeInstaller(gui);
        }
    }
}
