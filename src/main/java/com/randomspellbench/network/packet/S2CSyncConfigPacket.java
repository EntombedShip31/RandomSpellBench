package com.randomspellbench.network.packet;

import com.randomspellbench.client.ClientConfigData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：将服务端玩家配置 + 服务端 maxSpells + 服务端生效的
 * bannedSpells / schoolWhitelist 同步到客户端。
 *
 * 服务端 banned/whitelist 必须下发给客户端：客户端 GUI 才能正确显示
 * "玩家能选什么 = 服务端实际分配什么"，避免 UI 与分配结果不一致。
 */
public class S2CSyncConfigPacket {
    private final CompoundTag config;
    private final int maxSpells;
    private final List<String> bannedSpells;
    private final List<String> schoolWhitelist;

    public S2CSyncConfigPacket(CompoundTag config, int maxSpells,
                               List<String> bannedSpells, List<String> schoolWhitelist) {
        this.config = config;
        this.maxSpells = maxSpells;
        this.bannedSpells = bannedSpells == null ? List.of() : List.copyOf(bannedSpells);
        this.schoolWhitelist = schoolWhitelist == null ? List.of() : List.copyOf(schoolWhitelist);
    }

    public static void encode(S2CSyncConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.config);
        buf.writeVarInt(msg.maxSpells);
        buf.writeVarInt(msg.bannedSpells.size());
        for (String s : msg.bannedSpells) {
            buf.writeUtf(s);
        }
        buf.writeVarInt(msg.schoolWhitelist.size());
        for (String s : msg.schoolWhitelist) {
            buf.writeUtf(s);
        }
    }

    public static S2CSyncConfigPacket decode(FriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        int maxSpells = buf.readVarInt();
        int bannedSize = buf.readVarInt();
        List<String> banned = new java.util.ArrayList<>(bannedSize);
        for (int i = 0; i < bannedSize; i++) {
            banned.add(buf.readUtf());
        }
        int whitelistSize = buf.readVarInt();
        List<String> whitelist = new java.util.ArrayList<>(whitelistSize);
        for (int i = 0; i < whitelistSize; i++) {
            whitelist.add(buf.readUtf());
        }
        return new S2CSyncConfigPacket(tag, maxSpells, banned, whitelist);
    }

    public static void handle(S2CSyncConfigPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ClientConfigData.applyServerConfig(msg.config, msg.maxSpells, msg.bannedSpells, msg.schoolWhitelist);
        });
        ctx.setPacketHandled(true);
    }
}