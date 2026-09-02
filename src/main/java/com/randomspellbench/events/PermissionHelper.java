package com.randomspellbench.events;

import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.capability.PlayerSpellConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

/**
 * 创造模式限制与白名单。
 *
 * 默认只有创造模式玩家可使用本模组（避免在生存/PVP 中造成不公平）。
 * 服务端管理员可通过 /rspvp unlock 给单个玩家解除限制，或在
 * server.toml 的 randomspellbench-server.bypassCreativeOnly 中强制全开。
 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /** 玩家是否有权使用本模组：创造模式 或 玩家级 bypass 或 服务端全局 bypass。 */
    public static boolean canUse(ServerPlayer player) {
        if (player.isCreative()) {
            return true;
        }
        if (com.randomspellbench.Config.SERVER.bypassCreativeOnly.get()) {
            return true;
        }
        PlayerSpellConfig config = PlayerConfigStore.get(player);
        return config != null && config.isBypassCreativeOnly();
    }

    /** 当玩家无权限时返回的提示文本（用于命令失败 / 聊天栏红字）。 */
    public static MutableComponent creativeOnlyMessage() {
        return Component.translatable("command.randomspellbench.error.creative_only",
                Component.translatable("command.randomspellbench.unlock.hint"))
                .withStyle(ChatFormatting.RED);
    }
}