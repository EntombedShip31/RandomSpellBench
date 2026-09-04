package com.randomspellbench.spell;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 注入目标槽位：决定把法术注入到玩家身上哪一件物品。
 *
 * <p>对应 ISS 原版的两种注入语义（见 {@code ISpellContainer.createImbuedContainer}）：
 * <ul>
 *   <li>主手 / 副手（武器）→ 容器 {@code mustEquip=false}，<b>手持</b>即出现在法术轮盘；</li>
 *   <li>头盔 / 胸甲 / 护腿 / 靴子（盔甲、Curios 饰品）→ 容器 {@code mustEquip=true}，
 *       <b>穿戴</b>后才出现在法术轮盘。</li>
 * </ul>
 */
public enum ImbueTarget {
    MAINHAND(EquipmentSlot.MAINHAND, "mainhand"),
    OFFHAND(EquipmentSlot.OFFHAND, "offhand"),
    HEAD(EquipmentSlot.HEAD, "head"),
    CHEST(EquipmentSlot.CHEST, "chest"),
    LEGS(EquipmentSlot.LEGS, "legs"),
    FEET(EquipmentSlot.FEET, "feet");

    private static final ImbueTarget[] VALUES = values();

    private final EquipmentSlot slot;
    /** 命令参数与语言键后缀，全小写。 */
    private final String key;

    ImbueTarget(EquipmentSlot slot, String key) {
        this.slot = slot;
        this.key = key;
    }

    /** 对应的原版装备槽位。 */
    public EquipmentSlot slot() {
        return slot;
    }

    /** 命令参数名 / 网络包载荷取值。 */
    public String key() {
        return key;
    }

    /** 该槽位当前的物品（不是副本）。 */
    public ItemStack getStack(ServerPlayer player) {
        return player.getItemBySlot(slot);
    }

    /** 把物品写回该槽位。 */
    public void setStack(ServerPlayer player, ItemStack stack) {
        // 手持槽走 setItemInHand（明确无歧义）；盔甲槽走 setItemSlot。
        // Player 内部会把盔甲写进 Inventory#armor，下标由 EquipmentSlot#getIndex 决定。
        switch (slot) {
            case MAINHAND -> player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            case OFFHAND -> player.setItemInHand(InteractionHand.OFF_HAND, stack);
            default -> player.setItemSlot(slot, stack);
        }
    }

    /** 循环下一个目标（GUI 按钮用）。 */
    public ImbueTarget next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /** 槽位显示名的语言键。 */
    public String translationKey() {
        return "screen.randomspellbench.target_" + key;
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
