package com.randomspellpvp.network.packet;

import com.randomspellpvp.spell.AssignedSpell;
import com.randomspellpvp.spell.SpellPoolManager;
import com.randomspellpvp.testbench.TestManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：把选中法术生成成 ISS 法术卷轴并交给玩家。
 */
public class C2SSpawnScrollPacket {
    private final String spellId;
    private final int level;

    public C2SSpawnScrollPacket(String spellId, int level) {
        this.spellId = spellId == null ? "" : spellId;
        this.level = level;
    }

    public static void encode(C2SSpawnScrollPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.spellId);
        buf.writeVarInt(msg.level);
    }

    public static C2SSpawnScrollPacket decode(FriendlyByteBuf buf) {
        return new C2SSpawnScrollPacket(buf.readUtf(), buf.readVarInt());
    }

    public static void handle(C2SSpawnScrollPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            AbstractSpell spell = SpellPoolManager.getSpell(msg.spellId);
            if (spell == null || AssignedSpell.isNoneSpell(spell)) {
                player.sendSystemMessage(Component.translatable("command.randomspellbench.error.spell_not_found")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            TestManager.spawnScroll(player, spell, msg.level);
        });
        ctx.setPacketHandled(true);
    }
}
