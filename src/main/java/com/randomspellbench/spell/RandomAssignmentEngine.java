package com.randomspellbench.spell;

import com.randomspellbench.Config;
import com.randomspellbench.capability.AssignMode;
import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.capability.SpellLevelRange;
import com.randomspellbench.equipment.EquipmentManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainerMutable;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 随机分配引擎（创造模式测试台）。
 * 流程：
 * 1. 从「启用」的法术池中，按当前分配模式挑选若干法术；
 *    - RANDOM：按权重随机抽取 N 个互不重复的法术
 *    - ALL：一次性写入整个池子（受 maxSpells 限制）
 *    - SEQUENTIAL：按列表顺序依次取 N 个，游标自动前进，可翻页遍历
 * 2. 为每个法术按等级模式决定等级（RANGE 在范围内随机 / FIXED 固定等级）；
 * 3. 从 ISS 法术书中挑一本写入（支持追加到现有法术书），并以玩家名命名；
 * 4. 装备法术书。
 */
public final class RandomAssignmentEngine {
    private RandomAssignmentEngine() {
    }

    /** 分配结果。 */
    public record Result(boolean success, String message, List<AssignedSpell> spells) {
        public static Result ok(List<AssignedSpell> spells) {
            return new Result(true, "", spells);
        }

        public static Result fail(String message) {
            return new Result(false, message, List.of());
        }
    }

    // ---------------- 主入口 ----------------

    /** 按玩家配置执行一次分配。 */
    public static Result assign(ServerPlayer player, PlayerSpellConfig config) {
        List<AbstractSpell> enabled = collectEnabled(config);
        if (enabled.isEmpty()) {
            return Result.fail("command.randomspellbench.error.pool_empty");
        }

        int maxSpells = Math.max(1, Config.SERVER.maxSpells.get());
        int count = Math.max(1, Math.min(config.getSpellCount(), maxSpells));
        RandomSource random = player.getRandom();

        List<AbstractSpell> picked;
        if (config.getMode() == AssignMode.ALL) {
            // 保留「全部」分支兼容旧配置，但按钮与命令已隐藏
            picked = new ArrayList<>(enabled.subList(0, Math.min(enabled.size(), maxSpells)));
        } else {
            // 唯一面向用户的分配模式：随机抽取
            picked = config.isMinOnePerSchool()
                    ? pickBalanced(enabled, count, random)
                    : pickWeighted(enabled, count, random);
        }
        if (picked.isEmpty()) {
            return Result.fail("command.randomspellbench.error.pool_empty");
        }

        config.pushHistory();
        List<AssignedSpell> assigned = levelsFor(picked, config, random);

        // 尽量不抽到与上一批完全相同的组合（仅随机模式有意义）
        if (Config.SERVER.avoidRepeatLast.get() && config.getMode() == AssignMode.RANDOM
                && enabled.size() > count) {
            for (int attempt = 0; attempt < 8; attempt++) {
                if (!sameIds(assigned, config.getPreviousSpells())) {
                    break;
                }
                picked = pickWeighted(enabled, count, random);
                assigned = levelsFor(picked, config, random);
            }
        }

        ItemStack existing = Config.SERVER.appendToSpellbook.get()
                ? EquipmentManager.getEquippedSpellbook(player)
                : ItemStack.EMPTY;
        ItemStack book = buildSpellbook(assigned, existing, player, random);
        if (book.isEmpty()) {
            return Result.fail("command.randomspellbench.error.no_spellbook");
        }
        EquipmentManager.equip(player, book);
        return Result.ok(assigned);
    }

    /** 只给一个指定法术（已停用，保留空壳防止外部误用）。 */
    @Deprecated
    @SuppressWarnings("unused")
    public static Result assignSingle(ServerPlayer player, PlayerSpellConfig config,
                                      AbstractSpell spell, int level) {
        return Result.fail("command.randomspellbench.error.spell_not_found");
    }

    /** 复现上一次结果：把指定法术列表按当前等级规则重新写入法术书（不重抽）。 */
    public static Result assignExact(ServerPlayer player, PlayerSpellConfig config, List<AssignedSpell> spells) {
        if (spells == null || spells.isEmpty()) {
            return Result.fail("command.randomspellbench.error.no_last");
        }
        config.pushHistory();
        ItemStack existing = Config.SERVER.appendToSpellbook.get()
                ? EquipmentManager.getEquippedSpellbook(player)
                : ItemStack.EMPTY;
        ItemStack book = buildSpellbook(spells, existing, player, player.getRandom());
        if (book.isEmpty()) {
            return Result.fail("command.randomspellbench.error.no_spellbook");
        }
        EquipmentManager.equip(player, book);
        return Result.ok(new ArrayList<>(spells));
    }

    /** 撤销：恢复上一套分配结果（替换当前法术书内容）。 */
    public static Result undo(ServerPlayer player, PlayerSpellConfig config) {
        List<AssignedSpell> previous = config.getPreviousSpells();
        if (previous.isEmpty()) {
            return Result.fail("command.randomspellbench.error.no_history");
        }
        List<AssignedSpell> current = new ArrayList<>(config.getAssignedSpells());
        // 撤销以「替换」语义写回，避免追加模式下重复叠加
        ItemStack book = buildSpellbook(previous, ItemStack.EMPTY, player, player.getRandom());
        if (book.isEmpty()) {
            return Result.fail("command.randomspellbench.error.no_spellbook");
        }
        EquipmentManager.equip(player, book);

        config.setPreviousSpells(current);
        config.setAssignedSpells(previous);
        return Result.ok(previous);
    }

    /**
     * 构造一个 ISS 法术卷轴（irons_spellbooks:scroll），内含单个法术。
     * 卷轴与法术书一样通过 ISpellContainer 存储法术数据。
     */
    public static ItemStack buildScroll(AbstractSpell spell, int level, ServerPlayer player, RandomSource random) {
        Item scrollItem = resolveItem(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "scroll"));
        if (scrollItem == null || spell == null) {
            return ItemStack.EMPTY;
        }
        int lv = Mth.clamp(level, 1, Math.max(1, spell.getMaxLevel()));
        ItemStack stack = new ItemStack(scrollItem);
        ISpellContainerMutable mutable = ISpellContainer.create(1, false, false).mutableCopy();
        mutable.setMaxSpellCount(1);
        mutable.addSpellAtIndex(spell, lv, 0, false);
        ISpellContainer.set(stack, mutable.toImmutable());
        return stack;
    }

    // ---------------- 挑选策略 ----------------

    /** 按权重随机挑选 count 个互不重复的法术。 */
    public static List<AbstractSpell> pickWeighted(List<AbstractSpell> pool, int count, RandomSource random) {
        if (count >= pool.size()) {
            List<AbstractSpell> copy = new ArrayList<>(pool);
            // Fisher-Yates（RandomSource 不是 java.util.Random，无法用 Collections.shuffle）
            for (int i = copy.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                Collections.swap(copy, i, j);
            }
            return copy;
        }
        List<AbstractSpell> result = new ArrayList<>(count);
        int total = 0;
        int[] weights = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            weights[i] = Math.max(1, Config.getSpellWeight(pool.get(i).getSpellId()));
            total += weights[i];
        }

        // remaining 用数组承载，避免每次抽取都装箱/移除元素
        int remainingCount = pool.size();
        int[] remaining = new int[remainingCount];
        for (int i = 0; i < remainingCount; i++) {
            remaining[i] = i;
        }

        while (result.size() < count && remainingCount > 0) {
            int roll = random.nextInt(Math.max(1, total));
            int acc = 0;
            int pickSlot = remainingCount - 1;
            for (int i = 0; i < remainingCount; i++) {
                acc += weights[remaining[i]];
                if (roll < acc) {
                    pickSlot = i;
                    break;
                }
            }
            int pick = remaining[pickSlot];
            result.add(pool.get(pick));
            total -= weights[pick];
            remaining[pickSlot] = remaining[remainingCount - 1];
            remainingCount--;
        }
        return result;
    }

    /**
     * 学派均衡抽取：每个学派先各抽 1 个，剩余名额再按权重从整个池子抽。
     * 学派数大于 count 时退化为普通权重抽取。
     */
    public static List<AbstractSpell> pickBalanced(List<AbstractSpell> pool, int count, RandomSource random) {
        int n = Math.max(1, Math.min(count, pool.size()));
        if (n <= 1) {
            return pickWeighted(pool, n, random);
        }

        Map<SchoolType, List<AbstractSpell>> bySchool = new LinkedHashMap<>();
        for (AbstractSpell spell : pool) {
            bySchool.computeIfAbsent(spell.getSchoolType(), k -> new ArrayList<>()).add(spell);
        }
        if (bySchool.size() <= 1) {
            return pickWeighted(pool, n, random);
        }

        List<List<AbstractSpell>> buckets = new ArrayList<>(bySchool.values());
        // 打乱学派顺序，避免每次固定先抽前排学派
        for (int i = buckets.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(buckets, i, j);
        }

        List<AbstractSpell> result = new ArrayList<>(n);
        Set<AbstractSpell> used = new HashSet<>();
        for (List<AbstractSpell> bucket : buckets) {
            if (result.size() >= n) {
                break;
            }
            // 该学派候选（跳过已用，理论上每个学派只取一次，不会重复）
            AbstractSpell pick = pickWeighted(bucket, 1, random).get(0);
            result.add(pick);
            used.add(pick);
        }

        if (result.size() < n) {
            List<AbstractSpell> rest = new ArrayList<>();
            for (AbstractSpell spell : pool) {
                if (!used.contains(spell)) {
                    rest.add(spell);
                }
            }
            result.addAll(pickWeighted(rest, n - result.size(), random));
        }
        return result;
    }

    /** 顺序遍历：原模式已停用，方法直接移除以避免误用与编译噪音。 */

    private static List<AbstractSpell> collectEnabled(PlayerSpellConfig config) {
        List<AbstractSpell> enabled = new ArrayList<>();
        for (AbstractSpell spell : SpellPoolManager.getAvailableSpells()) {
            if (config.isSpellEnabled(spell)) {
                enabled.add(spell);
            }
        }
        return enabled;
    }

    private static List<AssignedSpell> levelsFor(List<AbstractSpell> spells, PlayerSpellConfig config, RandomSource random) {
        List<AssignedSpell> out = new ArrayList<>(spells.size());
        for (AbstractSpell spell : spells) {
            SpellLevelRange range = config.effectiveRange(spell);
            out.add(AssignedSpell.of(spell, range.randomLevel(random)));
        }
        return out;
    }

    private static boolean sameIds(List<AssignedSpell> a, List<AssignedSpell> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Set<String> ids = new HashSet<>();
        for (AssignedSpell spell : a) {
            ids.add(spell.spellId());
        }
        for (AssignedSpell spell : b) {
            if (!ids.contains(spell.spellId())) {
                return false;
            }
        }
        return true;
    }

    // ---------------- 法术书构建 ----------------

    /**
     * 构建写入了指定法术的法术书 ItemStack，并以玩家名命名。
     * ISS 1.20.1（3.16.3）的写入方式：
     * ISpellContainer.create(maxSpells, spellWheel, mustEquip).mutableCopy()
     * + mutable.setMaxSpellCount(slots)
     * + mutable.addSpellAtIndex(spell, level, index, locked)
     * + ISpellContainer.set(stack, mutable.toImmutable())
     *
     * @param existing 非空的现有法术书（仅在追加模式下生效），会从其后方继续追加
     * @return 找不到任何 ISS 法术书时返回 EMPTY（绝不回退到原版书）
     */
    public static ItemStack buildSpellbook(List<AssignedSpell> assigned, @Nullable ItemStack existing,
                                           ServerPlayer player, RandomSource random) {
        Item item = resolveSpellbookItem(random);
        if (item == null) {
            return ItemStack.EMPTY;
        }

        int maxSpells = Math.max(1, Config.SERVER.maxSpells.get());

        ItemStack stack;
        ISpellContainerMutable mutable;
        int startIndex = 0;

        if (Config.SERVER.appendToSpellbook.get()
                && existing != null && !existing.isEmpty() && ISpellContainer.isSpellContainer(existing)) {
            stack = existing.copy();
            mutable = ISpellContainer.get(stack).mutableCopy();
            startIndex = mutable.getActiveSpellCount();
        } else {
            stack = new ItemStack(item);
            mutable = ISpellContainer.create(1, true, true).mutableCopy();
        }

        int needed = startIndex + assigned.size();
        int slots = Config.SERVER.fillSpellbookSlots.get()
                ? maxSpells
                : Math.max(1, Math.min(Math.max(1, needed), maxSpells));
        mutable.setMaxSpellCount(slots);

        int index = startIndex;
        for (AssignedSpell entry : assigned) {
            AbstractSpell spell = entry.spell();
            if (spell == null) {
                continue;
            }
            int level = Mth.clamp(entry.level(), 1, Math.max(1, spell.getMaxLevel()));
            // addSpellAtIndex 内部会拒绝重复法术，成功才推进下标
            if (mutable.addSpellAtIndex(spell, level, index, false)) {
                index++;
            }
        }
        ISpellContainer.set(stack, mutable.toImmutable());

        stack.setHoverName(Component.translatable("item.randomspellbench.random_spellbook_named",
                        player.getName().getString())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return stack;
    }

    /**
     * 决定用哪本法术书：
     * 1. 开启随机时，从 ISS 法术书目录里随机挑一本；
     * 2. 否则用配置指定的 id；
     * 3. 都失败时仍从 ISS 目录兜底。
     * 全程只认 ISS 的法术书，绝不返回原版书；一本都没有时返回 null。
     */
    @Nullable
    public static Item resolveSpellbookItem(RandomSource random) {
        if (Config.SERVER.randomizeSpellbook.get()) {
            Item random1 = SpellbookCatalog.random(random);
            if (random1 != null) {
                return random1;
            }
        }
        Item configured = resolveItem(ResourceLocation.tryParse(Config.SERVER.defaultSpellbook.get()));
        if (configured != null) {
            return configured;
        }
        Item fallback = SpellbookCatalog.random(random);
        if (fallback != null) {
            return fallback;
        }
        return resolveItem(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "spell_book"));
    }

    @Nullable
    public static Item resolveItem(@Nullable ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }
}
