package com.randomspellbench.network.packet;

import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.capability.SpellFilter;
import com.randomspellbench.capability.SpellLevelRange;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * C2S：同步等级范围设置。
 * - spellId == null：更新全局等级范围；
 * - spellId != null：更新指定法术的独立范围，并按 useGlobal 切换是否使用全局范围。
 */
public class C2SUpdateLevelRangePacket {
    @Nullable
    private final String spellId;
    private final SpellLevelRange range;
    private final boolean useGlobal;

    public C2SUpdateLevelRangePacket(@Nullable String spellId, SpellLevelRange range, boolean useGlobal) {
        this.spellId = spellId;
        this.range = range;
        this.useGlobal = useGlobal;
    }

    public static void encode(C2SUpdateLevelRangePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.spellId != null);
        if (msg.spellId != null) {
            buf.writeUtf(msg.spellId);
        }
        buf.writeNbt(msg.range.serializeNBT());
        buf.writeBoolean(msg.useGlobal);
    }

    public static C2SUpdateLevelRangePacket decode(FriendlyByteBuf buf) {
        boolean hasSpell = buf.readBoolean();
        String spellId = hasSpell ? buf.readUtf() : null;
        SpellLevelRange range = SpellLevelRange.fromNBT(buf.readNbt());
        boolean useGlobal = buf.readBoolean();
        return new C2SUpdateLevelRangePacket(spellId, range, useGlobal);
    }

    public static void handle(C2SUpdateLevelRangePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // 权限校验：无权限玩家不得改动等级范围
            if (!com.randomspellbench.events.PermissionHelper.canUse(player)) {
                player.displayClientMessage(
                        com.randomspellbench.events.PermissionHelper.creativeOnlyMessage(), true);
                return;
            }
            PlayerSpellConfig config = PlayerConfigStore.get(player);
            if (msg.spellId == null) {
                config.setGlobalRange(msg.range);
            } else {
                SpellFilter filter = config.getFilter(msg.spellId);
                filter.setUseGlobalRange(msg.useGlobal);
                if (!msg.useGlobal) {
                    filter.setMinLevel(msg.range.getMinLevel());
                    filter.setMaxLevel(msg.range.getMaxLevel());
                }
            }
            PlayerConfigStore.save(player, config);
        });
        ctx.setPacketHandled(true);
    }
}
