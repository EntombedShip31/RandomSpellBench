package com.randomspellpvp.network.packet;

import com.randomspellpvp.testbench.TestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：请求服务端为发送者执行一次随机分配。
 */
public class C2SRequestRandomizePacket {

    public C2SRequestRandomizePacket() {
    }

    public static void encode(C2SRequestRandomizePacket msg, FriendlyByteBuf buf) {
    }

    public static C2SRequestRandomizePacket decode(FriendlyByteBuf buf) {
        return new C2SRequestRandomizePacket();
    }

    public static void handle(C2SRequestRandomizePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                TestManager.randomize(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
