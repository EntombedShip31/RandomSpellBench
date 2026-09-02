package com.randomspellbench.equipment;

import com.randomspellbench.Config;
import com.randomspellbench.RandomSpellPVP;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.Optional;

/**
 * 装备管理：通过 Curios 新 API（ICuriosItemHandler）把法术书装到 "spellbook" 槽位。
 *
 * 已移除：恢复战斗状态（用户认为破坏 PVP 公平性）、清空法术书。
 */
public final class EquipmentManager {
    private static final String SPELLBOOK_SLOT = "spellbook";

    private EquipmentManager() {
    }

    /**
     * 为玩家装备法术书。是否清空背包由 {@code clearInventory} 配置决定（默认不清空）。
     * 必须在服务端主线程调用。
     */
    public static void equip(ServerPlayer player, ItemStack spellbook) {
        if (Config.SERVER.clearInventory.get()) {
            player.getInventory().clearContent();
        }
        applySpellbook(player, spellbook);
    }

    /** 写入法术书（不清背包）。空物品栈会被忽略。 */
    public static void applySpellbook(ServerPlayer player, ItemStack spellbook) {
        if (spellbook.isEmpty()) {
            return;
        }
        boolean ok = setCurio(player, SPELLBOOK_SLOT, 0, spellbook);
        if (!ok) {
            // 兜底：curios 失败则把法术书塞进背包，玩家至少能用法术
            player.getInventory().add(spellbook.copy());
            RandomSpellPVP.LOGGER.warn("Failed to put spellbook into Curios slot, fallback to inventory for {}",
                    player.getName().getString());
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    /** 读取当前装备在 curios 槽位的法术书；没有则返回 EMPTY。 */
    public static ItemStack getEquippedSpellbook(ServerPlayer player) {
        try {
            Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
            if (inv.isPresent()) {
                Optional<SlotResult> res = inv.get().findCurio(SPELLBOOK_SLOT, 0);
                if (res.isPresent()) {
                    return res.get().stack();
                }
            }
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.debug("Failed to read curios slot: {}", t.toString());
        }
        return ItemStack.EMPTY;
    }

    /** 写入 Curios 槽位。成功装备返回 true。 */
    private static boolean setCurio(ServerPlayer player, String slot, int index, ItemStack stack) {
        try {
            Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
            if (inv.isEmpty()) {
                return false;
            }
            ICuriosItemHandler handler = inv.get();
            handler.setEquippedCurio(slot, index, stack);
            Optional<SlotResult> res = handler.findCurio(slot, index);
            return res.isPresent() && ItemStack.matches(res.get().stack(), stack);
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.warn("Failed to set curios slot '{}' for {}: {}", slot, player.getName().getString(), t.toString());
            return false;
        }
    }

    /**
     * 检测玩家是否具备「能装备法术书」的前置条件：已安装 Curios 且存在 spellbook 槽位。
     * 用于命令/GUI 入口给玩家明确提示，避免把法术书塞进背包后玩家"拿不到法术"。
     */
    public static boolean hasSpellbookSlot(ServerPlayer player) {
        try {
            Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
            if (inv.isEmpty()) {
                return false;
            }
            ICuriosItemHandler handler = inv.get();
            // 检查「spellbook 槽位是否存在」而不是「槽位是否有物品」：
            // 槽位未装备任何东西时 findCurio 返回空，但槽位本身已注册，不应误报缺失。
            if (handler.getCurios().containsKey(SPELLBOOK_SLOT)) {
                return true;
            }
            // 兜底：旧版 Curios 若没有 getSlotNames，仍尝试 findCurio 探测
            return !handler.findCurio(SPELLBOOK_SLOT, 0).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}