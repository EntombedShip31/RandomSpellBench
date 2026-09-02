package com.randomspellbench.network.packet;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：通知客户端关闭当前 GUI（/rsta reload 后清理旧界面，避免显示陈旧数据）。
 */
public class S2CCloseScreenPacket {

    public S2CCloseScreenPacket() {
    }

    public static void encode(S2CCloseScreenPacket msg, FriendlyByteBuf buf) {
    }

    public static S2CCloseScreenPacket decode(FriendlyByteBuf buf) {
        return new S2CCloseScreenPacket();
    }

    public static void handle(S2CCloseScreenPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> Minecraft.getInstance().setScreen(null));
        ctx.setPacketHandled(true);
    }
}
