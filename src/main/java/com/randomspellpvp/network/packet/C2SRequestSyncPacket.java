package com.randomspellpvp.network.packet;

import com.randomspellpvp.Config;
import com.randomspellpvp.capability.PlayerConfigStore;
import com.randomspellpvp.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：请求服务端把最新玩家配置同步回来（GUI 每次打开时发送，
 * 保证显示的一定是服务端真实配置，避免「数量/勾选与服务端不符」）。
 */
public class C2SRequestSyncPacket {

    public C2SRequestSyncPacket() {
    }

    public static void encode(C2SRequestSyncPacket msg, FriendlyByteBuf buf) {
    }

    public static C2SRequestSyncPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestSyncPacket();
    }

    public static void handle(C2SRequestSyncPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // 权限校验：无权限玩家不得请求同步（避免伪造包高频请求 + 服务端配置外泄）
            if (!com.randomspellpvp.events.PermissionHelper.canUse(player)) {
                return;
            }
            var config = PlayerConfigStore.get(player);
            NetworkHandler.sendToPlayer(new S2CSyncConfigPacket(
                    config.serializeNBT(), Config.SERVER.maxSpells.get(),
                    new java.util.ArrayList<>(Config.SERVER.bannedSpells.get()),
                    new java.util.ArrayList<>(Config.SERVER.schoolWhitelist.get())), player);
        });
        ctx.setPacketHandled(true);
    }
}
