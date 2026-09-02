package com.randomspellpvp.network.packet;

import com.randomspellpvp.capability.PlayerConfigStore;
import com.randomspellpvp.capability.PlayerSpellConfig;
import com.randomspellpvp.capability.SpellFilter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：同步单个法术的启用/禁用状态。
 */
public class C2SUpdateSpellFilterPacket {
    private final String spellId;
    private final SpellFilter filter;

    public C2SUpdateSpellFilterPacket(String spellId, SpellFilter filter) {
        this.spellId = spellId;
        this.filter = filter;
    }

    public static void encode(C2SUpdateSpellFilterPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.spellId);
        buf.writeNbt(msg.filter.serializeNBT());
    }

    public static C2SUpdateSpellFilterPacket decode(FriendlyByteBuf buf) {
        return new C2SUpdateSpellFilterPacket(buf.readUtf(), SpellFilter.fromNBT(buf.readNbt()));
    }

    public static void handle(C2SUpdateSpellFilterPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // 权限校验：与随机分配/卷轴/学习保持一致，无权限玩家不得改动自己的法术池配置
            if (!com.randomspellpvp.events.PermissionHelper.canUse(player)) {
                player.displayClientMessage(
                        com.randomspellpvp.events.PermissionHelper.creativeOnlyMessage(), true);
                return;
            }
            PlayerSpellConfig config = PlayerConfigStore.get(player);
            config.setFilter(msg.spellId, msg.filter);
            PlayerConfigStore.save(player, config);
        });
        ctx.setPacketHandled(true);
    }
}
