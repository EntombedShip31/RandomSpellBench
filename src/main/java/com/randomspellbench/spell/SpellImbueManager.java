package com.randomspellbench.spell;

import com.randomspellbench.Config;
import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.equipment.EquipmentManager;
import com.randomspellbench.events.PermissionHelper;
import io.redspace.ironsspellbooks.api.item.IScroll;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.IPresetSpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainerMutable;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 注入 / 拆卷轴（Imbue / Extract）——等效 ISS 奥术铁砧（Arcane Anvil）。
 *
 * <p>写入武器/盔甲与 ISS 官方一致（见 {@code ISpellContainer.createImbuedContainer}）：
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
 * <p>额外支持 {@link ImbueTarget#SPELLBOOK}（书）：写入 = 往 Curios 饰品栏的法术书
 * 里追加法术（重复法术原地替换，满了拒绝）；拆下 = 把书 / 装备上的法术抄成卷轴。
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

        // 书（Curios 饰品栏法术书）走「写入法术书容器」语义，不走武器/盔甲的注入容器
        if (target == ImbueTarget.SPELLBOOK) {
            int maxLevel = Math.max(1, spell.getMaxLevel());
            int lv = Mth.clamp(level > 0 ? level : defaultLevel(player, spell), 1, maxLevel);
            return imbueSpellbook(player, spell, lv);
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
     * 把法术写入 Curios 饰品栏的法术书（GUI 槽位简写「书」）。
     *
     * <p>写入走「往法术书容器追加」语义：书里已有的法术保留，重复法术原地替换；
     * 容量上限 = 服务端配置 {@code maxSpells}（法术书槽位上限，默认 12），
     * 满了（active >= 上限）拒绝写入并明确提示——槽位要先靠「拆下卷轴」腾出来。</p>
     */
    private static Result imbueSpellbook(ServerPlayer player, AbstractSpell spell, int level) {
        ItemStack book = EquipmentManager.getEquippedSpellbook(player);
        if (book.isEmpty() || !SpellbookDismantler.isSpellbook(book)) {
            return Result.fail("command.randomspellbench.error.no_spellbook_curio");
        }
        ItemStack copy = book.copy();
        ISpellContainerMutable mutable;
        if (ISpellContainer.isSpellContainer(copy) && ISpellContainer.get(copy) != null) {
            mutable = ISpellContainer.get(copy).mutableCopy();
        } else {
            // 空白的法术书还没写入过容器：按测试台的默认书初始化（槽位上限 = 配置 maxSpells）
            mutable = ISpellContainer.create(1, true, true).mutableCopy();
        }
        int cap = Math.max(1, Config.SERVER.maxSpells.get());
        int active = mutable.getActiveSpellCount();
        if (active >= cap) {
            return Result.fail("command.randomspellbench.error.imbue_book_full", cap);
        }
        int index = mutable.getNextAvailableIndex();
        if (index < 0 || index >= active) {
            // 容器里没有空槽位（或返回异常值）时接到末尾，末尾槽位允许扩展
            index = active;
        }
        if (index >= cap) {
            return Result.fail("command.randomspellbench.error.imbue_book_full", cap);
        }
        if (index >= mutable.getMaxSpellCount()) {
            mutable.setMaxSpellCount(index + 1);
        }
        if (!mutable.addSpellAtIndex(spell, level, index, false)) {
            // 通常是重复法术：替换掉原条目，保持每本书每个法术只占一个槽位
            int occupied = mutable.getIndexForSpell(spell);
            if (occupied < 0) {
                return Result.fail("command.randomspellbench.error.spell_not_found");
            }
            mutable.removeSpellAtIndex(occupied);
            if (!mutable.addSpellAtIndex(spell, level, occupied, false)) {
                return Result.fail("command.randomspellbench.error.imbue_book_full", cap);
            }
        }
        ISpellContainer.set(copy, mutable.toImmutable());
        EquipmentManager.applySpellbook(player, copy);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return Result.ok("command.randomspellbench.imbue.book_done",
                spell.getDisplayName(player), level, targetName(ImbueTarget.SPELLBOOK));
    }

    /**
     * 拆下指定槽位物品上的法术卷轴：把容器里每个法术抄成 ISS 卷轴放进背包，并从物品上移除。
     *
     * <p>与 GUI 目标槽位联动（选中「主 / 副 / 头 / 胸 / 腿 / 脚 / 书」再点「拆下卷轴」）：
     * <ul>
     *   <li>书 → Curios 饰品栏法术书；拆后书<b>仍留在槽位</b>，只是槽位腾空，可继续注入 / 随机分配；</li>
     *   <li>装备位 → 物品留在原位；若持的是法术书则按书处理，武器 / 盔甲在法术全部拆净后
     *       直接移除整个法术容器（等效忏悔石，避免装备上残留空的 spell_container）。</li>
     * </ul>
     * 安全边界与一键拆书一致：先只读扫描、再预生成全部卷轴，任何一个生成失败就整单取消，
     * 最后才改动物品——不会出现「法术没了但卷轴没拿到」。
     */
    public static Result extractAsScrolls(ServerPlayer player, ImbueTarget target) {
        if (!PermissionHelper.canUse(player)) {
            return Result.fail("command.randomspellbench.error.creative_only",
                    Component.translatable("command.randomspellbench.unlock.hint"));
        }
        if (target == null) {
            return Result.fail("command.randomspellbench.error.imbue_bad_target", "");
        }
        ItemStack stack = target.getStack(player);
        if (stack.isEmpty()) {
            return Result.fail(target == ImbueTarget.SPELLBOOK
                            ? "command.randomspellbench.error.no_spellbook_curio"
                            : "command.randomspellbench.error.extract_no_item",
                    targetName(target));
        }
        if (!ISpellContainer.isSpellContainer(stack)) {
            return Result.fail("command.randomspellbench.error.imbue_not_imbued", targetName(target));
        }
        ISpellContainer container = ISpellContainer.get(stack);
        if (container == null || container.getMaxSpellCount() <= 0) {
            return Result.fail("command.randomspellbench.error.imbue_not_imbued", targetName(target));
        }

        // ---- 1) 只读扫描：哪些槽位可拆（空槽 / none / 锁定的一律跳过，此时不动物品）----
        int populated = 0;
        int locked = 0;
        List<Integer> indexes = new ArrayList<>();
        List<AbstractSpell> spells = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (int i = 0; i < container.getMaxSpellCount(); i++) {
            SpellData data = container.getSpellAtIndex(i);
            if (data == null || data == SpellData.EMPTY) {
                continue;
            }
            populated++;
            AbstractSpell sp = data.getSpell();
            if (AssignedSpell.isNoneSpell(sp)) {
                continue;
            }
            if (!data.canRemove()) {
                locked++;
                continue;
            }
            indexes.add(i);
            spells.add(sp);
            levels.add(Math.max(1, data.getLevel()));
        }
        if (spells.isEmpty()) {
            return Result.fail(locked > 0
                            ? "command.randomspellbench.error.spellbook_all_locked"
                            : "command.randomspellbench.error.imbue_not_imbued",
                    targetName(target));
        }

        // ---- 2) 预生成卷轴：任何一个生成失败就整单取消（此时物品还没被改动）----
        List<Integer> removeIndexes = new ArrayList<>(indexes.size());
        List<ItemStack> scrolls = new ArrayList<>(spells.size());
        for (int i = 0; i < spells.size(); i++) {
            ItemStack scroll = RandomAssignmentEngine.buildScroll(
                    spells.get(i), levels.get(i), player, player.getRandom());
            if (scroll.isEmpty()) {
                return Result.fail("command.randomspellbench.error.no_scroll");
            }
            removeIndexes.add(indexes.get(i));
            scrolls.add(scroll);
        }

        // ---- 3) 回写物品（走到这里才真正改动）----
        if (SpellbookDismantler.isSpellbook(stack)) {
            // 书：拆掉被选中的法术，保留空容器（槽位留给后续注入 / 分配）
            ItemStack book = stack.copy();
            ISpellContainerMutable mutable = ISpellContainer.get(book).mutableCopy();
            for (int idx : removeIndexes) {
                mutable.removeSpellAtIndex(idx);
            }
            ISpellContainer.set(book, mutable.toImmutable());
            if (target == ImbueTarget.SPELLBOOK) {
                EquipmentManager.applySpellbook(player, book);
            } else {
                target.setStack(player, book);
            }
        } else {
            // 武器 / 盔甲：能拆的全部拆完时直接移除整个容器，避免残留空容器
            ItemStack cleared = stack.copy();
            if (locked == 0 && removeIndexes.size() == populated) {
                ISpellContainer.remove(cleared);
            } else {
                ISpellContainerMutable mutable = ISpellContainer.get(cleared).mutableCopy();
                for (int idx : removeIndexes) {
                    mutable.removeSpellAtIndex(idx);
                }
                ISpellContainer.set(cleared, mutable.toImmutable());
            }
            target.setStack(player, cleared);
        }

        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();

        // ---- 4) 卷轴入背包，放不下掉在脚下 ----
        int dropped = 0;
        for (ItemStack scroll : scrolls) {
            if (!player.getInventory().add(scroll)) {
                dropAtFeet(player, scroll);
                dropped++;
            }
        }
        return Result.ok(dropped > 0
                        ? "command.randomspellbench.extract.done_dropped"
                        : "command.randomspellbench.extract.done",
                scrolls.size(), dropped);
    }

    /** 背包满时的兜底：直接丢在玩家脚下（带拾取延迟，避免瞬间被自己吸回去）。 */
    private static void dropAtFeet(ServerPlayer player, ItemStack stack) {
        ItemEntity entity = new ItemEntity(
                player.level(), player.getX(), player.getY() + 0.5D, player.getZ(), stack);
        entity.setPickUpDelay(40);
        player.level().addFreshEntity(entity);
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
