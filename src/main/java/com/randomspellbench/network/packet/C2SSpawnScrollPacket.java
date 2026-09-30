package com.randomspellbench.network.packet;

import com.randomspellbench.spell.AssignedSpell;
import com.randomspellbench.spell.SpellPoolManager;
import com.randomspellbench.testbench.TestManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：把选中法术生成成 ISS 法术卷轴并交给玩家。
 *
 * @param mainhand true = 直接替换主手（原主手回背包/丢脚下，测试连发免翻背包）；
 *                 false = 普通入背包（满则丢脚下）
 */
public class C2SSpawnScrollPacket {
    private final String spellId;
    private final int level;
    private final boolean mainhand;

    public C2SSpawnScrollPacket(String spellId, int level) {
        this(spellId, level, false);
    }

    public C2SSpawnScrollPacket(String spellId, int level, boolean mainhand) {
        this.spellId = spellId == null ? "" : spellId;
        this.level = level;
        this.mainhand = mainhand;
    }

    public static void encode(C2SSpawnScrollPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.spellId);
        buf.writeVarInt(msg.level);
        buf.writeBoolean(msg.mainhand);
    }

    public static C2SSpawnScrollPacket decode(FriendlyByteBuf buf) {
        return new C2SSpawnScrollPacket(buf.readUtf(), buf.readVarInt(), buf.readBoolean());
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
            TestManager.spawnScroll(player, spell, msg.level, msg.mainhand);
        });
        ctx.setPacketHandled(true);
    }
}
