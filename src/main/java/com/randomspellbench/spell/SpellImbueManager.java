package com.randomspellbench.spell;

import com.randomspellbench.Config;
import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.events.PermissionHelper;
import io.redspace.ironsspellbooks.api.item.IScroll;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.IPresetSpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainerMutable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import javax.annotation.Nullable;

/**
 * 注入法术（Imbue）——等效于 ISS 奥术铁砧（Arcane Anvil）把卷轴注入武器/盔甲。
 *
 * <p>写入方式与 ISS 官方一致（见 {@code ISpellContainer.createImbuedContainer}）：
 * <pre>
 *   ISpellContainer.create(maxSpells=1, isSpellWheel=true, mustEquip)
 *     .mutableCopy()
 *     .addSpellAtIndex(spell, level, 0, locked=true)
 *     .toImmutable()
 *   ISpellContainer.set(stack, container)
 * </pre>
 * {@code mustEquip} 由 ISS 自己判定：物品是 {@link ArmorItem} 或 Curios 的
 * {@link ICurioItem} 时为 true（必须穿戴才生效），武器为 false（手持即生效）。
 *
 * <p>本类只在<b>服务端主线程</b>调用。
 */
public final class SpellImbueManager {

    private SpellImbueManager() {
    }

    /**
     * 注入结果。
     *
     * @param messageKey 语言键（成功 / 失败都用 Component.translatable 渲染）
     * @param args       语言键参数
     */
    public record Result(boolean success, String messageKey, Object[] args) {
        public static Result ok(String messageKey, Object... args) {
            return new Result(true, messageKey, args);
        }

        public static Result fail(String messageKey, Object... args) {
            return new Result(false, messageKey, args);
        }
    }

    // ---------------- 注入 ----------------

    /**
     * 把法术注入到目标槽位的物品上。
     *
     * @param level 法术等级；{@code <=0} 时取该玩家「等级规则」的下限（固定等级模式即为固定等级）
     */
    public static Result imbue(ServerPlayer player, @Nullable AbstractSpell spell, int level, ImbueTarget target) {
        if (!PermissionHelper.canUse(player)) {
            return Result.fail("command.randomspellbench.error.creative_only",
                    Component.translatable("command.randomspellbench.unlock.hint"));
        }
        if (AssignedSpell.isNoneSpell(spell)) {
            return Result.fail("command.randomspellbench.error.spell_not_found");
        }
        if (target == null) {
            return Result.fail("command.randomspellbench.error.imbue_bad_target", "");
        }

        ItemStack current = target.getStack(player);
        if (current.isEmpty()) {
            return Result.fail("command.randomspellbench.error.imbue_no_item",
                    targetName(target));
        }
        if (!isImbueable(current)) {
            return Result.fail("command.randomspellbench.error.imbue_not_imbueable",
                    current.getHoverName());
        }

        int maxLevel = Math.max(1, spell.getMaxLevel());
        int lv = Mth.clamp(level > 0 ? level : defaultLevel(player, spell), 1, maxLevel);

        ItemStack result = applyImbue(current, spell, lv);
        if (result == null) {
            return Result.fail("command.randomspellbench.error.imbue_full",
                    Math.max(1, Config.SERVER.imbueMaxSpells.get()));
        }

        target.setStack(player, result);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return Result.ok("command.randomspellbench.imbue.done",
                spell.getDisplayName(player), lv, targetName(target));
    }

    /** 清除目标槽位物品上的注入法术（等效原版忏悔石）。 */
    public static Result clear(ServerPlayer player, ImbueTarget target) {
        if (!PermissionHelper.canUse(player)) {
            return Result.fail("command.randomspellbench.error.creative_only",
                    Component.translatable("command.randomspellbench.unlock.hint"));
        }
        if (target == null) {
            return Result.fail("command.randomspellbench.error.imbue_bad_target", "");
        }
        ItemStack current = target.getStack(player);
        if (current.isEmpty()) {
            return Result.fail("command.randomspellbench.error.imbue_no_item",
                    targetName(target));
        }
        if (!ISpellContainer.isSpellContainer(current)) {
            return Result.fail("command.randomspellbench.error.imbue_not_imbued",
                    targetName(target));
        }
        ItemStack cleared = current.copy();
        ISpellContainer.remove(cleared);
        target.setStack(player, cleared);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return Result.ok("command.randomspellbench.unimbue.done", targetName(target));
    }

    /**
     * 写出注入后的物品栈。
     *
     * @return 注入结果；容器已满且不允许继续扩展时返回 null
     */
    @Nullable
    private static ItemStack applyImbue(ItemStack source, AbstractSpell spell, int level) {
        ItemStack stack = source.copy();
        int maxPerItem = Math.max(1, Config.SERVER.imbueMaxSpells.get());

        boolean append = Config.SERVER.imbueAppend.get() && ISpellContainer.isSpellContainer(stack);
        if (!append) {
            // 全新注入：交给 ISS 官方入口，mustEquip / isSpellWheel 全部与奥术铁砧一致
            ISpellContainer container = ISpellContainer.createImbuedContainer(spell, level, stack);
            // createImbuedContainer 内部多半已写入，这里再 set 一次保证幂等（无副作用）
            ISpellContainer.set(stack, container);
            return stack;
        }

        ISpellContainer existing = ISpellContainer.get(stack);
        if (existing == null) {
            // 理论上不会发生（前面已判定是容器），兜底退化为「全新注入」而不是 NPE
            ISpellContainer container = ISpellContainer.createImbuedContainer(spell, level, stack);
            ISpellContainer.set(stack, container);
            return stack;
        }

        ISpellContainerMutable mutable = existing.mutableCopy();
        int index = mutable.getNextAvailableIndex();
        if (index < 0) {
            index = mutable.getActiveSpellCount();
        }
        if (index >= maxPerItem) {
            return null; // 槽位已满
        }
        if (index >= mutable.getMaxSpellCount()) {
            // 扩一格：法术书/武器的槽位数由容器自己维护，扩展后才能写入新下标
            mutable.setMaxSpellCount(index + 1);
        }
        if (!mutable.addSpellAtIndex(spell, level, index, true)) {
            // 通常是重复法术：替换掉原来那一条，保持"只占一个槽位"
            int occupied = mutable.getIndexForSpell(spell);
            if (occupied < 0) {
                return null;
            }
            mutable.removeSpellAtIndex(occupied);
            if (!mutable.addSpellAtIndex(spell, level, occupied, true)) {
                return null;
            }
        }
        ISpellContainer.set(stack, mutable.toImmutable());
        return stack;
    }

    // ---------------- 判定 ----------------

    /**
     * 该物品能否被注入。
     *
     * <p>默认只认 ISS 奥术铁砧接受的几类：剑类武器、盔甲、Curios 饰品、
     * 以及实现了 {@link IPresetSpellContainer} 的 ISS 物品。
     * 服务端配置 {@code imbue.allowAnyItem=true} 时可放开到任意物品。
     */
    public static boolean isImbueable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item == null || item == Items.AIR) {
            return false;
        }
        // 卷轴是一次性消耗品，不做注入目标（防止把法术写进不可回收的消耗品）
        if (item instanceof IScroll) {
            return false;
        }
        if (Config.SERVER.imbueAllowAnyItem.get()) {
            return true;
        }
        if (item instanceof SwordItem || item instanceof ArmorItem) {
            return true;
        }
        if (item instanceof ICurioItem) {
            return true;
        }
        if (item instanceof IPresetSpellContainer) {
            return true;
        }
        return isIssWeaponItem(item);
    }

    /**
     * ISS 的法杖/魔杖等武器不受 {@link SwordItem} 约束（如 {@code StaffItem extends CastingItem extends Item}），
     * 且位于非 API 包无法编译期引用，这里按包名兜底识别。
     */
    private static boolean isIssWeaponItem(Item item) {
        String name = item.getClass().getName();
        return name.startsWith("io.redspace.ironsspellbooks.item.weapons.");
    }

    /** 未指定等级时的默认等级：取玩家等级规则的下限（固定等级模式下即固定等级）。 */
    private static int defaultLevel(ServerPlayer player, AbstractSpell spell) {
        return PlayerConfigStore.get(player).effectiveRange(spell).getMinLevel();
    }

    // ---------------- 反馈 ----------------

    /** 把结果发给玩家：成功进 actionbar（绿色），失败进聊天栏（红色）。 */
    public static void report(ServerPlayer player, Result result) {
        Component message = Component.translatable(result.messageKey(), result.args());
        if (result.success()) {
            player.displayClientMessage(message.copy().withStyle(ChatFormatting.GREEN), true);
        } else {
            player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        }
    }

    private static Component targetName(ImbueTarget target) {
        return Component.translatable(target.translationKey());
    }
}
