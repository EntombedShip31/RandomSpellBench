package com.randomspellbench.spell;

import com.randomspellbench.equipment.EquipmentManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 注入目标槽位：决定把法术写入 / 拆下自玩家身上哪一件物品。
 *
 * <p>对应 ISS 原版的两种注入语义（见 {@code ISpellContainer.createImbuedContainer}）：
 * <ul>
 *   <li>主手 / 副手（武器）→ 容器 {@code mustEquip=false}，<b>手持</b>即出现在法术轮盘；</li>
 *   <li>头盔 / 胸甲 / 护腿 / 靴子（盔甲、Curios 饰品）→ 容器 {@code mustEquip=true}，
 *       <b>穿戴</b>后才出现在法术轮盘；</li>
 *   <li>{@link #SPELLBOOK}（书）→ 不是原版装备槽，而是 Curios 饰品栏的 spellbook 槽位，
 *       GUI 里简写为「书」，写入 = 往饰品栏那本法术书里追加法术。</li>
 * </ul>
 */
public enum ImbueTarget {
    MAINHAND(EquipmentSlot.MAINHAND, "mainhand"),
    OFFHAND(EquipmentSlot.OFFHAND, "offhand"),
    HEAD(EquipmentSlot.HEAD, "head"),
    CHEST(EquipmentSlot.CHEST, "chest"),
    LEGS(EquipmentSlot.LEGS, "legs"),
    FEET(EquipmentSlot.FEET, "feet"),
    SPELLBOOK(null, "spellbook");

    private static final ImbueTarget[] VALUES = values();

    /** 原版装备槽；{@link #SPELLBOOK} 没有对应的原版槽位，为 null。 */
    @Nullable
    private final EquipmentSlot slot;
    /** 命令参数与语言键后缀，全小写。 */
    private final String key;

    ImbueTarget(@Nullable EquipmentSlot slot, String key) {
        this.slot = slot;
        this.key = key;
    }

    /** 对应的原版装备槽位；{@link #SPELLBOOK} 返回 null。 */
    @Nullable
    public EquipmentSlot slot() {
        return slot;
    }

    /** 命令参数名 / 网络包载荷取值。 */
    public String key() {
        return key;
    }

    /** 该槽位当前的物品（不是副本）。书 = Curios 饰品栏 spellbook 槽的当前法术书。 */
    public ItemStack getStack(ServerPlayer player) {
        if (this == SPELLBOOK) {
            return EquipmentManager.getEquippedSpellbook(player);
        }
        return player.getItemBySlot(slot);
    }

    /** 把物品写回该槽位。书 = 写回 Curios 饰品栏 spellbook 槽。 */
    public void setStack(ServerPlayer player, ItemStack stack) {
        if (this == SPELLBOOK) {
            EquipmentManager.applySpellbook(player, stack);
            return;
        }
        // 手持槽走 setItemInHand（明确无歧义）；盔甲槽走 setItemSlot。
        // Player 内部会把盔甲写进 Inventory#armor，下标由 EquipmentSlot#getIndex 决定。
        switch (slot) {
            case MAINHAND -> player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            case OFFHAND -> player.setItemInHand(InteractionHand.OFF_HAND, stack);
            default -> player.setItemSlot(slot, stack);
        }
    }

    /** 槽位完整名的语言键（用于 tooltip、命令反馈）。 */
    public String translationKey() {
        return "screen.randomspellbench.target_" + key;
    }

    /**
     * 槽位短标签的语言键（用于 GUI 里并排的部位按钮：主 / 副 / 头 / 胸 / 腿 / 脚 / 书）。
     * 多个按钮要挤在同一行，放不下「胸甲 / Chestplate」这类完整名，
     * 完整名改由 tooltip 和命令反馈承载。
     */
    public String shortTranslationKey() {
        return "screen.randomspellbench.target_short_" + key;
    }

    /** 按命令参数解析目标；无法识别时返回 null。 */
    @Nullable
    public static ImbueTarget byKey(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (ImbueTarget target : VALUES) {
            if (target.key.equalsIgnoreCase(key)) {
                return target;
            }
        }
        return null;
    }
}
