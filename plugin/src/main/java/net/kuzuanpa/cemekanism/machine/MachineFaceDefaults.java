package net.kuzuanpa.cemekanism.machine;

import mekcore.item.ItemSlotKind;
import mekcore.machine.Direction;
import mekcore.machine.Machine;
import mekcore.machine.MachineBlueprint;
import mekcore.machine.SideMode;

/**
 * 普通机器的默认面（对齐上游 Mekanism 的 {@code AttachedSideConfig.create()}）：
 * 新机器的能量六面可输入、物品六面可输入。
 *
 * <p>上游机器默认把所有面填成 INPUT，再由发电机/存储/泵等专用默认表覆盖掉输出面、能量面；
 * 核心的 {@link Machine} 构造只把六面初始化成 NONE，所以没有默认表的机器（冶金灌注机等）
 * 贴上线缆也收不到电。这里只补“一个默认面都没有”的机器，已有默认表的机器（发电机、能量立方、
 * 储罐、泵、数字采矿机、激光、加热器等）保持 {@code mek-systems.yml}/{@code StorageRuntime} 的口径。
 *
 * <p>只在控制器构造时调用；玩家在存档里显式配置过的面随后由 {@code loadCustomData} 覆盖，
 * 所以不会把玩家关掉的“关”面又打开。
 */
public final class MachineFaceDefaults {

    private MachineFaceDefaults() {
    }

    public static void apply(Machine machine) {
        if (machine.energyTank().isPresent() && !hasAnyEnergyFace(machine)) {
            for (Direction direction : Direction.values()) {
                machine.defaultSide(MachineBlueprint.ENERGY_PORT, direction, SideMode.INPUT);
            }
        }
        if (hasInputSlot(machine) && !hasAnyItemFace(machine)) {
            for (Direction direction : Direction.values()) {
                machine.defaultItemSide(direction, SideMode.INPUT);
            }
        }
    }

    private static boolean hasAnyEnergyFace(Machine machine) {
        for (Direction direction : Direction.values()) {
            if (machine.side(MachineBlueprint.ENERGY_PORT, direction) != SideMode.NONE) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyItemFace(Machine machine) {
        for (Direction direction : Direction.values()) {
            if (machine.itemSide(direction) != SideMode.NONE) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInputSlot(Machine machine) {
        return machine.items().slots(ItemSlotKind.INPUT).iterator().hasNext();
    }
}
