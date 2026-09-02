package com.randomspellbench.network.packet;

import com.randomspellbench.client.ClientEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：通知客户端打开战前配置 GUI（供 /rspvp config 使用）。
 */
public class S2COpenScreenPacket {

    public S2COpenScreenPacket() {
    }

    public static void encode(S2COpenScreenPacket msg, FriendlyByteBuf buf) {
    }

    public static S2COpenScreenPacket decode(FriendlyByteBuf buf) {
        return new S2COpenScreenPacket();
    }

    public static void handle(S2COpenScreenPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(ClientEvents::openConfigScreenFromServer);
        ctx.setPacketHandled(true);
    }
}
