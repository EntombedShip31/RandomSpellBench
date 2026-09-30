package com.randomspellbench.network.packet;

import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.events.PermissionHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：切换「法术无冷却」（测试用）。
 *
 * <p>开启后服务端在 PlayerTickEvent 里周期性清空该玩家的 ISS 冷却（见 ModEvents），
 * 关闭即恢复原版冷却，无残留状态。状态随玩家配置 NBT 持久化，
 * 并经 {@code S2CSyncConfigPacket} 同步到客户端 GUI。</p>
 */
public class C2SSetNoCooldownPacket {
    private final boolean noCooldown;

    public C2SSetNoCooldownPacket(boolean noCooldown) {
        this.noCooldown = noCooldown;
    }

    public static void encode(C2SSetNoCooldownPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.noCooldown);
    }

    public static C2SSetNoCooldownPacket decode(FriendlyByteBuf buf) {
        return new C2SSetNoCooldownPacket(buf.readBoolean());
    }

    public static void handle(C2SSetNoCooldownPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!PermissionHelper.canUse(player)) {
                player.displayClientMessage(PermissionHelper.creativeOnlyMessage(), true);
                return;
            }
            PlayerSpellConfig config = PlayerConfigStore.get(player);
            config.setNoCooldown(msg.noCooldown);
            PlayerConfigStore.save(player, config);
        });
        ctx.setPacketHandled(true);
    }
}
