package com.randomspellbench.network.packet;

import com.randomspellbench.spell.AssignedSpell;
import com.randomspellbench.spell.ImbueTarget;
import com.randomspellbench.spell.SpellImbueManager;
import com.randomspellbench.spell.SpellPoolManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：注入 / 清除注入。
 *
 * 注入（IMBUE）把选中法术按指定等级写进目标槽位的物品，效果等同 ISS 奥术铁砧：
 * 手持（武器）/ 穿戴（盔甲、饰品）即可在法术轮盘上看到并使用该法术。
 * 清除（CLEAR）等价于原版忏悔石，移除物品上的 {@code spell_container}。
 */
public class C2SImbueSpellPacket {
    public enum Action {
        IMBUE,
        CLEAR
    }

    private final Action action;
    private final String spellId;
    private final int level;
    /** {@link ImbueTarget#key()}。 */
    private final String target;

    public C2SImbueSpellPacket(Action action, String spellId, int level, String target) {
        this.action = action == null ? Action.CLEAR : action;
        this.spellId = spellId == null ? "" : spellId;
        this.level = level;
        this.target = target == null ? "" : target;
    }

    public static void encode(C2SImbueSpellPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action.name());
        buf.writeUtf(msg.spellId);
        buf.writeVarInt(msg.level);
        buf.writeUtf(msg.target);
    }

    public static C2SImbueSpellPacket decode(FriendlyByteBuf buf) {
        Action action;
        try {
            action = Action.valueOf(buf.readUtf());
        } catch (IllegalArgumentException e) {
            action = Action.CLEAR;
        }
        return new C2SImbueSpellPacket(action, buf.readUtf(), buf.readVarInt(), buf.readUtf());
    }

    public static void handle(C2SImbueSpellPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            ImbueTarget target = ImbueTarget.byKey(msg.target);
            if (target == null) {
                return;
            }
            SpellImbueManager.Result result;
            if (msg.action == Action.IMBUE) {
                AbstractSpell spell = SpellPoolManager.getSpell(msg.spellId);
                if (AssignedSpell.isNoneSpell(spell)) {
                    player.sendSystemMessage(Component
                            .translatable("command.randomspellbench.error.spell_not_found")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                result = SpellImbueManager.imbue(player, spell, msg.level, target);
            } else {
                result = SpellImbueManager.clear(player, target);
            }
            SpellImbueManager.report(player, result);
        });
        ctx.setPacketHandled(true);
    }
}
