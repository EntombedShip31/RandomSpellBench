package com.randomspellbench.network;

import com.randomspellbench.RandomSpellPVP;
import com.randomspellbench.network.packet.C2SRequestRandomizePacket;
import com.randomspellbench.network.packet.C2SExtractSpellsPacket;
import com.randomspellbench.network.packet.C2SImbueSpellPacket;
import com.randomspellbench.network.packet.C2SRequestSyncPacket;
import com.randomspellbench.network.packet.C2SSpawnScrollPacket;
import com.randomspellbench.network.packet.C2STestActionPacket;
import com.randomspellbench.network.packet.C2SUpdateLevelRangePacket;
import com.randomspellbench.network.packet.C2SUpdateSettingsPacket;
import com.randomspellbench.network.packet.C2SUpdateSpellFilterPacket;
import com.randomspellbench.network.packet.S2CAssignedSpellsPacket;
import com.randomspellbench.network.packet.S2CCloseScreenPacket;
import com.randomspellbench.network.packet.S2COpenScreenPacket;
import com.randomspellbench.network.packet.S2CSyncConfigPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 网络通道。采用服务端权威架构：
 * 客户端仅发送配置变更与请求，所有分配/装备/传送逻辑在服务端执行。
 */
public final class NetworkHandler {
    public static final String PROTOCOL_VERSION = "1.0";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(RandomSpellPVP.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private NetworkHandler() {
    }

    public static void register() {
        int id = 0;
        // C2S
        register(++id, C2SUpdateSpellFilterPacket.class,
                C2SUpdateSpellFilterPacket::encode, C2SUpdateSpellFilterPacket::decode, C2SUpdateSpellFilterPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2SUpdateLevelRangePacket.class,
                C2SUpdateLevelRangePacket::encode, C2SUpdateLevelRangePacket::decode, C2SUpdateLevelRangePacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2SUpdateSettingsPacket.class,
                C2SUpdateSettingsPacket::encode, C2SUpdateSettingsPacket::decode, C2SUpdateSettingsPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2SRequestRandomizePacket.class,
                C2SRequestRandomizePacket::encode, C2SRequestRandomizePacket::decode, C2SRequestRandomizePacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2STestActionPacket.class,
                C2STestActionPacket::encode, C2STestActionPacket::decode, C2STestActionPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2SSpawnScrollPacket.class,
                C2SSpawnScrollPacket::encode, C2SSpawnScrollPacket::decode, C2SSpawnScrollPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2SRequestSyncPacket.class,
                C2SRequestSyncPacket::encode, C2SRequestSyncPacket::decode, C2SRequestSyncPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(++id, C2SExtractSpellsPacket.class,
                C2SExtractSpellsPacket::encode, C2SExtractSpellsPacket::decode, C2SExtractSpellsPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // 追加的新包一律排在末尾：id 已经发出去的包不能复用，否则旧客户端会解错数据
        register(++id, C2SImbueSpellPacket.class,
                C2SImbueSpellPacket::encode, C2SImbueSpellPacket::decode, C2SImbueSpellPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // S2C
        register(++id, S2CSyncConfigPacket.class,
                S2CSyncConfigPacket::encode, S2CSyncConfigPacket::decode, S2CSyncConfigPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(++id, S2CAssignedSpellsPacket.class,
                S2CAssignedSpellsPacket::encode, S2CAssignedSpellsPacket::decode, S2CAssignedSpellsPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(++id, S2COpenScreenPacket.class,
                S2COpenScreenPacket::encode, S2COpenScreenPacket::decode, S2COpenScreenPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(++id, S2CCloseScreenPacket.class,
                S2CCloseScreenPacket::encode, S2CCloseScreenPacket::decode, S2CCloseScreenPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    private static <MSG> void register(int id, Class<MSG> clazz,
                                       BiConsumer<MSG, FriendlyByteBuf> encode,
                                       Function<FriendlyByteBuf, MSG> decode,
                                       BiConsumer<MSG, Supplier<NetworkEvent.Context>> handle,
                                       NetworkDirection direction) {
        CHANNEL.registerMessage(id, clazz, encode, decode, handle, Optional.of(direction));
    }

    public static <MSG> void sendToServer(MSG msg) {
        CHANNEL.sendToServer(msg);
    }

    public static <MSG> void sendToPlayer(MSG msg, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}
