package com.randomspellbench.testbench;

import com.randomspellbench.capability.AssignMode;
import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.events.PermissionHelper;
import com.randomspellbench.equipment.EquipmentManager;
import com.randomspellbench.network.NetworkHandler;
import com.randomspellbench.network.packet.S2CAssignedSpellsPacket;
import com.randomspellbench.spell.AssignedSpell;
import com.randomspellbench.spell.RandomAssignmentEngine;
import com.randomspellbench.spell.SpellbookDismantler;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 测试台流程编排（服务端权威）。
 *
 * 简化后保留：随机分配 / 撤销 / 生成法术卷轴 / 长按学习法术 / 一键拆法术书。
 * 已移除：分配后回血饱食/清状态清冷却（v1.0.3）、聊天播报开关（v1.0.3 起固定开启）、
 *         恢复状态、传送测试点、搭建场地、清空法术书、只给选中法术、全部/顺序分配模式、DPS 统计。
 */
public final class TestManager {
    /** actionbar 一行最多展示几个法术名（超出折叠）。 */
    private static final int RESULT_PREVIEW = 4;

    private TestManager() {
    }

    // ---------------- 分配 ----------------

    public static void randomize(ServerPlayer player) {
        if (!PermissionHelper.canUse(player)) {
            feedback(player, PermissionHelper.creativeOnlyMessage(), false);
            return;
        }
        // curios + spellbook 槽缺失检测：塞背包后 ISS 法术书不生效，给玩家明确前置依赖提示
        if (!EquipmentManager.hasSpellbookSlot(player)) {
            feedback(player, Component.translatable("command.randomspellbench.error.no_curios_slot")
                    .withStyle(ChatFormatting.RED), false);
        }
        assign(player, PlayerConfigStore.get(player));
    }

    private static void assign(ServerPlayer player, PlayerSpellConfig config) {
        RandomAssignmentEngine.Result result = RandomAssignmentEngine.assign(player, config);
        if (!result.success()) {
            feedback(player, Component.translatable(result.message()), true);
            return;
        }
        config.setAssignedSpells(result.spells());
        config.setAssigned(true);
        PlayerConfigStore.save(player, config);
        afterAssign(player, config, result.spells());
    }

    /** 复现上一次分配结果（不重抽，把上次那批重新写入法术书）。 */
    public static void repeatLast(ServerPlayer player) {
        if (!PermissionHelper.canUse(player)) {
            feedback(player, PermissionHelper.creativeOnlyMessage(), false);
            return;
        }
        PlayerSpellConfig config = PlayerConfigStore.get(player);
        if (config.getAssignedSpells().isEmpty()) {
            feedback(player, Component.translatable("command.randomspellbench.error.no_last"), true);
            return;
        }
        RandomAssignmentEngine.Result result =
                RandomAssignmentEngine.assignExact(player, config, config.getAssignedSpells());
        if (!result.success()) {
            feedback(player, Component.translatable(result.message()), true);
            return;
        }
        PlayerConfigStore.save(player, config);
        afterAssign(player, config, result.spells());
    }

    /** 撤销到上一套结果。 */
    public static void undo(ServerPlayer player) {
        if (!PermissionHelper.canUse(player)) {
            feedback(player, PermissionHelper.creativeOnlyMessage(), false);
            return;
        }
        PlayerSpellConfig config = PlayerConfigStore.get(player);
        RandomAssignmentEngine.Result result = RandomAssignmentEngine.undo(player, config);
        if (!result.success()) {
            feedback(player, Component.translatable(result.message()), true);
            return;
        }
        config.setAssigned(true);
        PlayerConfigStore.save(player, config);
        afterAssign(player, config, result.spells());
    }

    /** 把选中法术生成成 ISS 卷轴并交给玩家（背包优先，放不下丢脚下）。 */
    public static void spawnScroll(ServerPlayer player, AbstractSpell spell, int level) {
        if (!PermissionHelper.canUse(player)) {
            feedback(player, PermissionHelper.creativeOnlyMessage(), false);
            return;
        }
        PlayerSpellConfig config = PlayerConfigStore.get(player);
        int lv = level > 0 ? level : config.effectiveRange(spell).randomLevel(player.getRandom());
        ItemStack scroll = RandomAssignmentEngine.buildScroll(spell, lv, player, player.getRandom());
        if (scroll.isEmpty()) {
            feedback(player, Component.translatable("command.randomspellbench.error.no_scroll"), true);
            return;
        }
        boolean added = player.getInventory().add(scroll);
        if (!added) {
            player.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    player.level(), player.getX(), player.getY() + 0.5, player.getZ(), scroll));
        }
        feedback(player, Component.translatable("command.randomspellbench.scroll.done",
                spell.getDisplayName(player), lv), true);
    }

    /**
     * 一键拆法术书：把书里的法术抄成卷轴放进背包，并从书里移除。
     *
     * 各分支的提示都由 {@link SpellbookDismantler} 判定，这里只负责权限校验与文案播报：
     * 没有法术书 / 空白法术书 / 全部锁定 / 背包溢出掉落，都有各自明确的提示。
     */
    public static void extractSpells(ServerPlayer player, SpellbookDismantler.Source source) {
        if (!PermissionHelper.canUse(player)) {
            feedback(player, PermissionHelper.creativeOnlyMessage(), false);
            return;
        }
        SpellbookDismantler.Result result = SpellbookDismantler.dismantle(player, source);
        if (!result.success()) {
            feedback(player, Component.translatable(result.messageKey())
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        MutableComponent msg = Component.translatable(result.dropped() > 0
                        ? "command.randomspellbench.extract.done_dropped"
                        : "command.randomspellbench.extract.done",
                result.extracted(), result.dropped());
        if (result.locked() > 0) {
            // 锁定的法术（预设/独特法术书自带）抄不下来，明确告知玩家「没丢，只是留在书里」
            msg = msg.copy().append(Component.literal(" "))
                    .append(Component.translatable("command.randomspellbench.extract.locked_kept",
                            result.locked()).withStyle(ChatFormatting.GRAY));
        }
        if (result.bookDropped()) {
            // 饰品栏拆出的空书连背包都塞不下，只能掉地上——必须说清楚，否则玩家会以为书没了
            msg = msg.copy().append(Component.literal(" "))
                    .append(Component.translatable("command.randomspellbench.extract.book_dropped")
                            .withStyle(ChatFormatting.YELLOW));
        }
        feedback(player, msg.withStyle(ChatFormatting.GREEN), true);
    }

    /**
     * 让玩家永久学习选中法术（仿照 ISS 原版：长按法术图标 1.5 秒）。
     * 服务端通过反射调用 SyncedSpellData.learnSpell（该类不在 ISS api jar 中）。
     */
    public static void learnSpell(ServerPlayer player, AbstractSpell spell) {
        if (!PermissionHelper.canUse(player)) {
            feedback(player, PermissionHelper.creativeOnlyMessage(), false);
            return;
        }
        boolean ok = com.randomspellbench.events.SpellLearnHelper.learn(player, spell);
        if (ok) {
            feedback(player, Component.translatable("command.randomspellbench.learn.done",
                    spell.getDisplayName(player)).withStyle(ChatFormatting.GREEN), true);
        } else {
            feedback(player, Component.translatable("command.randomspellbench.learn.failed",
                    spell.getDisplayName(player)).withStyle(ChatFormatting.RED), true);
        }
    }

    private static void afterAssign(ServerPlayer player, PlayerSpellConfig config, List<AssignedSpell> spells) {
        NetworkHandler.sendToPlayer(new S2CAssignedSpellsPacket(true, spells), player);
        // 分配结果播报固定开启（v1.0.3 已移除「聊天栏播报」开关及其玩家级配置）
        sendResult(player, spells);
    }

    // ---------------- 结果展示（actionbar 一行小字，不入聊天栏） ----------------

    /**
     * 在 actionbar 用一行小字播报结果：
     * - 标题「获得 N 个法术」 + 前几个法术名 + 超出折叠为「(+K)」。
     * - 不进聊天栏，避免遮罩施法轮盘。
     */
    private static void sendResult(ServerPlayer player, List<AssignedSpell> spells) {
        if (spells.isEmpty()) {
            return;
        }
        MutableComponent preview = Component.literal("");
        int shown = Math.min(RESULT_PREVIEW, spells.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                preview.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            }
            preview.append(format(spells.get(i), player));
        }
        if (spells.size() > shown) {
            preview.append(Component.literal(" (+" + (spells.size() - shown) + ")")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        player.displayClientMessage(preview, true);
    }

    private static MutableComponent format(AssignedSpell entry, ServerPlayer player) {
        AbstractSpell spell = entry.spell();
        if (spell == null) {
            return Component.literal(entry.spellId()).withStyle(ChatFormatting.GRAY);
        }
        MutableComponent name = spell.getDisplayName(player).copy();
        TextColor color = spell.getSchoolType().getDisplayName().getStyle().getColor();
        if (color != null) {
            name.setStyle(name.getStyle().withColor(color));
        }
        return name.append(Component.literal(" Lv" + entry.level()).withStyle(ChatFormatting.GRAY));
    }

    /** 统一反馈通道：success 进 actionbar，error 进聊天栏（红色）。 */
    private static void feedback(ServerPlayer player, Component component, boolean success) {
        if (success) {
            player.displayClientMessage(component, true);
        } else {
            player.sendSystemMessage(component);
        }
    }
}