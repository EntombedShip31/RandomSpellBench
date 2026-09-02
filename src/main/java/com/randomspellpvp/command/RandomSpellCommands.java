package com.randomspellpvp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.randomspellpvp.Config;
import com.randomspellpvp.capability.PlayerConfigStore;
import com.randomspellpvp.capability.PlayerSpellConfig;
import com.randomspellpvp.events.PermissionHelper;
import com.randomspellpvp.network.NetworkHandler;
import com.randomspellpvp.network.packet.C2SSpawnScrollPacket;
import com.randomspellpvp.network.packet.S2CCloseScreenPacket;
import com.randomspellpvp.network.packet.S2COpenScreenPacket;
import com.randomspellpvp.network.packet.S2CSyncConfigPacket;
import com.randomspellpvp.spell.AssignedSpell;
import com.randomspellpvp.spell.SpellPoolManager;
import com.randomspellpvp.spell.SpellbookCatalog;
import com.randomspellpvp.testbench.TestManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;

/**
 * 命令：/rspvp
 *
 * 保留：config / randomize / undo / chat / scroll / learn / unlock / lock / reload
 * 已移除：reset / tp / point / setpoint / arena / clear / all / cursor / give / dps / printLast
 */
public final class RandomSpellCommands {

    private static final SuggestionProvider<CommandSourceStack> SPELL_SUGGESTIONS = (ctx, builder) -> {
        for (AbstractSpell spell : SpellPoolManager.getAvailableSpells()) {
            builder.suggest(spell.getSpellId());
        }
        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("rsta")
                .executes(RandomSpellCommands::usage)
                // 显式 help 子命令，方便在游戏中查看所有子命令（与 vanilla /help 一致）
                .then(Commands.literal("help")
                        .executes(RandomSpellCommands::usage))
                .then(Commands.literal("config")
                        .executes(RandomSpellCommands::openConfig))
                .then(Commands.literal("randomize")
                        .executes(RandomSpellCommands::randomizeSelf))
                .then(Commands.literal("undo")
                        .executes(RandomSpellCommands::undoSelf))
                .then(Commands.literal("chat")
                        .executes(RandomSpellCommands::chatSelf))
                .then(Commands.literal("scroll")
                        .then(Commands.argument("spell", StringArgumentType.word())
                                .suggests(SPELL_SUGGESTIONS)
                                .executes(RandomSpellCommands::scrollDefault)
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 20))
                                        .executes(RandomSpellCommands::scrollWithLevel))))
                .then(Commands.literal("learn")
                        .then(Commands.argument("spell", StringArgumentType.word())
                                .suggests(SPELL_SUGGESTIONS)
                                .executes(RandomSpellCommands::learnSelf)
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .requires(s -> s.hasPermission(2))
                                        .executes(RandomSpellCommands::learnTargets))))
                .then(Commands.literal("unlock")
                        .executes(RandomSpellCommands::unlockSelf)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .requires(s -> s.hasPermission(2))
                                .executes(RandomSpellCommands::unlockTargets)))
                .then(Commands.literal("lock")
                        .executes(RandomSpellCommands::lockSelfCmd)
                        .then(Commands.argument("targets", EntityArgument.players())
                                .requires(s -> s.hasPermission(2))
                                .executes(RandomSpellCommands::lockTargets)))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(RandomSpellCommands::reload)));
    }

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable("command.randomspellbench.usage"), false);
        return 1;
    }

    private static int openConfig(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!PermissionHelper.canUse(player)) {
            player.sendSystemMessage(PermissionHelper.creativeOnlyMessage());
            return 0;
        }
        NetworkHandler.sendToPlayer(new S2COpenScreenPacket(), player);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        SpellPoolManager.invalidate();
        SpellbookCatalog.invalidate();
        Config.invalidateWeightCache();
        // 关闭所有在线玩家已打开的旧 GUI，避免显示 reload 前的陈旧数据
        var server = ctx.getSource().getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                NetworkHandler.sendToPlayer(new S2CCloseScreenPacket(), p);
            }
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("command.randomspellbench.reloaded",
                SpellPoolManager.getAvailableSpells().size(), SpellbookCatalog.get().size()), true);
        return 1;
    }

    private static int randomizeSelf(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!PermissionHelper.canUse(player)) {
            player.sendSystemMessage(PermissionHelper.creativeOnlyMessage());
            return 0;
        }
        TestManager.randomize(player);
        return 1;
    }

    private static int undoSelf(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!PermissionHelper.canUse(player)) {
            player.sendSystemMessage(PermissionHelper.creativeOnlyMessage());
            return 0;
        }
        TestManager.undo(player);
        return 1;
    }

    private static int chatSelf(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TestManager.toggleChatResult(player);
        return 1;
    }

    private static int scrollDefault(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AbstractSpell spell = SpellPoolManager.getSpell(StringArgumentType.getString(ctx, "spell"));
        if (spell == null || AssignedSpell.isNoneSpell(spell)) {
            player.sendSystemMessage(Component.translatable("command.randomspellbench.error.spell_not_found"));
            return 0;
        }
        NetworkHandler.sendToPlayer(new C2SSpawnScrollPacket(spell.getSpellId(), 0), player);
        return 1;
    }

    private static int scrollWithLevel(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AbstractSpell spell = SpellPoolManager.getSpell(StringArgumentType.getString(ctx, "spell"));
        if (spell == null || AssignedSpell.isNoneSpell(spell)) {
            player.sendSystemMessage(Component.translatable("command.randomspellbench.error.spell_not_found"));
            return 0;
        }
        int level = IntegerArgumentType.getInteger(ctx, "level");
        NetworkHandler.sendToPlayer(new C2SSpawnScrollPacket(spell.getSpellId(), level), player);
        return 1;
    }

    private static int learnSelf(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!PermissionHelper.canUse(player)) {
            player.sendSystemMessage(PermissionHelper.creativeOnlyMessage());
            return 0;
        }
        AbstractSpell spell = SpellPoolManager.getSpell(StringArgumentType.getString(ctx, "spell"));
        if (spell == null || AssignedSpell.isNoneSpell(spell)) {
            player.sendSystemMessage(Component.translatable("command.randomspellbench.error.spell_not_found"));
            return 0;
        }
        TestManager.learnSpell(player, spell);
        return 1;
    }

    private static int learnTargets(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "targets");
        AbstractSpell spell = SpellPoolManager.getSpell(StringArgumentType.getString(ctx, "spell"));
        if (spell == null || AssignedSpell.isNoneSpell(spell)) {
            ctx.getSource().sendFailure(Component.translatable("command.randomspellbench.error.spell_not_found"));
            return 0;
        }
        for (ServerPlayer p : players) {
            TestManager.learnSpell(p, spell);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("command.randomspellbench.batch.done",
                players.size(), "learn"), true);
        return players.size();
    }

    private static int unlockSelf(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        setBypass(player, true);
        player.sendSystemMessage(Component.translatable("command.randomspellbench.unlock.self")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int unlockTargets(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer p : players) {
            setBypass(p, true);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("command.randomspellbench.batch.done",
                players.size(), "unlock"), true);
        return players.size();
    }

    private static int lockSelfCmd(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        setBypass(player, false);
        player.sendSystemMessage(Component.translatable("command.randomspellbench.lock.self"));
        return 1;
    }

    private static int lockTargets(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer p : players) {
            setBypass(p, false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("command.randomspellbench.batch.done",
                players.size(), "lock"), true);
        return players.size();
    }

    private static void setBypass(ServerPlayer player, boolean value) {
        PlayerSpellConfig config = PlayerConfigStore.get(player);
        config.setBypassCreativeOnly(value);
        PlayerConfigStore.save(player, config);
        // unlock/lock 改的是服务端持久数据；必须把最新配置推回客户端，
        // 否则客户端打开 GUI 前的本地权限镜像（ClientConfigData）仍为旧值，
        // 生存玩家会被误拦（表现为 /rsta unlock 后仍然无法使用）。
        NetworkHandler.sendToPlayer(new S2CSyncConfigPacket(
                config.serializeNBT(), Config.SERVER.maxSpells.get(),
                new java.util.ArrayList<>(Config.SERVER.bannedSpells.get()),
                new java.util.ArrayList<>(Config.SERVER.schoolWhitelist.get())), player);
    }
}