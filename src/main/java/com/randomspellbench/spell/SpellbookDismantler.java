package com.randomspellbench.spell;

import com.randomspellbench.RandomSpellPVP;
import com.randomspellbench.equipment.EquipmentManager;
import io.redspace.ironsspellbooks.api.item.ISpellbook;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainerMutable;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一键拆法术书：把法术书里的法术逐个「抄写」成 ISS 卷轴（irons_spellbooks:scroll），
 * 从书里移除，并把卷轴放进玩家背包。
 *
 * <p>等价于在铁魔法的「法术抄写台」里把每个法术槽的卷轴一个个取出来，
 * 只是省掉了来回跑界面的操作，且一次性把整本书拆干净。</p>
 *
 * <h3>选取哪本书</h3>
 * <ol>
 *   <li>{@link Source#AUTO}（默认）：主手 → 副手 → Curios 的 spellbook 饰品槽；</li>
 *   <li>{@link Source#HAND}：只看主手 / 副手；</li>
 *   <li>{@link Source#CURIO}：只看 Curios 的 spellbook 饰品槽。</li>
 * </ol>
 * 手上和饰品栏同时有书时优先拆手上的（AUTO 顺序即优先级）。
 *
 * <h3>拆下来的书怎么放</h3>
 * <ul>
 *   <li>来源为手上：书留在手里，只把卷轴放进背包；</li>
 *   <li>来源为饰品槽：清空槽位，把（已拆空的）书和卷轴一起放进背包；</li>
 *   <li>背包满了：仍然继续拆，多出来的物品直接丢在玩家脚下。</li>
 * </ul>
 *
 * <h3>安全边界（失败时法术书绝对不会被改动）</h3>
 * <ul>
 *   <li>先只读扫一遍，把「可拆下的法术」收集出来，此时不动书；</li>
 *   <li>再预生成全部卷轴，任何一个生成失败（如 ISS 缺失、scroll 物品未注册）就整本放弃；</li>
 *   <li>最后才回写容器数据 —— 因此不会出现「法术没了但卷轴没拿到」的丢档。</li>
 * </ul>
 *
 * 必须在服务端主线程调用。
 */
public final class SpellbookDismantler {

    /** 查找范围。 */
    public enum Source { AUTO, HAND, CURIO }

    /** 法术书的所在位置。 */
    private enum Origin { MAINHAND, OFFHAND, CURIO }

    /** 找到的法术书（栈本身即所在格子的活引用）。 */
    private record Found(ItemStack stack, Origin origin) {
    }

    /**
     * 拆除结果。success=false 时 messageKey 为语言键；其余字段仅在成功时有意义。
     *
     * @param extracted   拆下并交出的卷轴数量
     * @param dropped     背包满、掉在地上的卷轴数量
     * @param locked      因锁定而保留在书里的法术数量
     * @param bookDropped 饰品栏来源时，拆空的书是否也因背包满而掉在地上
     */
    public record Result(boolean success, String messageKey, int extracted, int dropped, int locked, boolean bookDropped) {
        static Result fail(String messageKey) {
            return new Result(false, messageKey, 0, 0, 0, false);
        }
    }

    /** 脚下掉落物的拾取延迟（tick），与玩家主动丢弃（player.drop）保持一致。 */
    private static final int DROP_PICKUP_DELAY = 40;

    private SpellbookDismantler() {
    }

    /**
     * 执行一次拆分。
     *
     * @param source 查找范围
     * @return 结果；失败时 messageKey 为可直接 {@code Component.translatable} 的语言键
     */
    public static Result dismantle(ServerPlayer player, Source source) {
        Found found = find(player, source);
        if (found == null) {
            // 手上/饰品栏都没有法术书
            return Result.fail(source == Source.CURIO
                    ? "command.randomspellbench.error.no_spellbook_curio"
                    : "command.randomspellbench.error.no_spellbook_held");
        }

        ItemStack book = found.stack();
        if (!ISpellContainer.isSpellContainer(book)) {
            // 是法术书物品但没有写入任何法术（空白法术书）
            return Result.fail("command.randomspellbench.error.spellbook_empty");
        }
        ISpellContainer container = ISpellContainer.get(book);
        if (container == null || container.getMaxSpellCount() <= 0) {
            return Result.fail("command.randomspellbench.error.spellbook_broken");
        }

        // ---- 1) 只读扫描：哪些槽位可拆 ----
        List<Integer> candidateIndexes = new ArrayList<>();
        List<AssignedSpell> candidateSpells = new ArrayList<>();
        int locked = 0;
        int slots = container.getMaxSpellCount();
        for (int i = 0; i < slots; i++) {
            SpellData data = container.getSpellAtIndex(i);
            if (data == null || data == SpellData.EMPTY) {
                continue;
            }
            AbstractSpell spell = data.getSpell();
            if (AssignedSpell.isNoneSpell(spell)) {
                continue;
            }
            // 抄写台同样拒绝取出锁定的法术（预设/独特法术书自带锁定槽）
            if (!data.canRemove()) {
                locked++;
                continue;
            }
            candidateIndexes.add(i);
            candidateSpells.add(AssignedSpell.of(spell, Math.max(1, data.getLevel())));
        }

        // ---- 2) 预生成卷轴：造不出来的槽位就留着不拆（避免法术凭空消失） ----
        List<Integer> removeIndexes = new ArrayList<>(candidateIndexes.size());
        List<ItemStack> scrolls = new ArrayList<>(candidateIndexes.size());
        for (int i = 0; i < candidateSpells.size(); i++) {
            AbstractSpell spell = candidateSpells.get(i).spell();
            if (spell == null) {
                // 法术 id 已失效（版本变动/法术被移除）：保留在书里，不拆
                continue;
            }
            ItemStack scroll = RandomAssignmentEngine.buildScroll(
                    spell, candidateSpells.get(i).level(), player, player.getRandom());
            if (scroll.isEmpty()) {
                // ISS 的 scroll 物品不存在，整本放弃（此时书还没被改过）
                return Result.fail("command.randomspellbench.error.no_scroll");
            }
            removeIndexes.add(candidateIndexes.get(i));
            scrolls.add(scroll);
        }
        if (scrolls.isEmpty()) {
            return Result.fail(locked > 0
                    ? "command.randomspellbench.error.spellbook_all_locked"
                    : "command.randomspellbench.error.spellbook_empty");
        }

        // ---- 3) 回写法术书（走到这里才真正改动物品） ----
        // removeSpellAtIndex(i) 只把该槽位置空，不会挪动其它槽位，所以下标保持有效
        ISpellContainerMutable mutable = container.mutableCopy();
        for (int index : removeIndexes) {
            mutable.removeSpellAtIndex(index);
        }
        ISpellContainer.set(book, mutable.toImmutable());

        // ---- 4) 饰品栏来源：先把（已拆空的）书摘下来放进背包 ----
        // 顺序刻意排在卷轴之前：背包挤不下时优先保证书不掉在地上
        boolean bookDropped = false;
        if (found.origin() == Origin.CURIO) {
            ItemStack emptied = book.copy();
            EquipmentManager.clearSpellbookCurio(player);
            if (!player.getInventory().add(emptied)) {
                dropAtFeet(player, emptied);
                bookDropped = true;
            }
        }

        // ---- 5) 卷轴入背包，放不下的丢在脚下 ----
        // 背包满时仍然继续拆：多出来的卷轴直接掉在脚下，不会中断拆分
        int dropped = 0;
        for (ItemStack scroll : scrolls) {
            if (!player.getInventory().add(scroll)) {
                dropAtFeet(player, scroll);
                dropped++;
            }
        }

        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return new Result(true, "", scrolls.size(), dropped, locked, bookDropped);
    }

    // ---------------- 查找 ----------------

    /**
     * 按范围找第一本法术书，找不到返回 null。
     * 手上优先：主手 → 副手，最后才是 Curios 的 spellbook 槽位。
     */
    @Nullable
    private static Found find(ServerPlayer player, Source source) {
        if (source != Source.CURIO) {
            ItemStack mainHand = player.getMainHandItem();
            if (isSpellbook(mainHand)) {
                return new Found(mainHand, Origin.MAINHAND);
            }
            ItemStack offHand = player.getOffhandItem();
            if (isSpellbook(offHand)) {
                return new Found(offHand, Origin.OFFHAND);
            }
        }
        if (source != Source.HAND) {
            ItemStack curio = EquipmentManager.getEquippedSpellbook(player);
            if (isSpellbook(curio)) {
                return new Found(curio, Origin.CURIO);
            }
        }
        return null;
    }

    /**
     * 判断一个物品栈是不是「法术书」：
     * 1. 实现了 ISS 的 {@link ISpellbook} 标记接口（ISS 及绝大多数附属模组的法术书都满足）；
     * 2. 兜底：物品 id 落在 ISS 法术书目录里（见 {@link SpellbookCatalog}）。
     *
     * 卷轴、法杖、盔甲上的附魔法术都不算法术书，避免误拆。
     */
    public static boolean isSpellbook(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            if (stack.getItem() instanceof ISpellbook) {
                return true;
            }
        } catch (Throwable t) {
            // ISS 缺失时 instanceof 不会抛异常，这里只为防御附属模组的异常实现
            RandomSpellPVP.LOGGER.debug("ISpellbook check failed: {}", t.toString());
        }
        return SpellbookCatalog.get().contains(stack.getItem());
    }

    // ---------------- 兜底投放 ----------------

    /** 背包已满时的兜底：直接丢在玩家脚下（带拾取延迟，避免瞬间被自己吸回去）。 */
    private static void dropAtFeet(ServerPlayer player, ItemStack stack) {
        ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5D, player.getZ(), stack);
        entity.setPickUpDelay(DROP_PICKUP_DELAY);
        player.level().addFreshEntity(entity);
    }
}
