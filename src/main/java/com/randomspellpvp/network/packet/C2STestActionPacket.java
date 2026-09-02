package com.randomspellpvp.network.packet;

import com.randomspellpvp.testbench.TestManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：测试台操作（撤销 / 切换聊天播报 / 长按学习法术）。
 *
 * 已移除：恢复状态、传送测试点、搭建场地、清空法术书、只给选中法术等分支。
 */
public class C2STestActionPacket {
    public enum Action {
        UNDO,
        TOGGLE_CHAT,
        LEARN_SPELL,
        REPEAT_LAST
    }

    private final Action action;
    private final String spellId;

    public C2STestActionPacket(Action action, String spellId) {
        this.action = action == null ? Action.UNDO : action;
        this.spellId = spellId == null ? "" : spellId;
    }

    public static void encode(C2STestActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action.name());
        buf.writeUtf(msg.spellId);
    }

    public static C2STestActionPacket decode(FriendlyByteBuf buf) {
        Action action;
        try {
            action = Action.valueOf(buf.readUtf());
        } catch (IllegalArgumentException e) {
            action = Action.UNDO;
        }
        return new C2STestActionPacket(action, buf.readUtf());
    }

    public static void handle(C2STestActionPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            switch (msg.action) {
                case UNDO -> TestManager.undo(player);
                case TOGGLE_CHAT -> TestManager.toggleChatResult(player);
                case LEARN_SPELL -> {
                    if (msg.spellId.isEmpty()) {
                        return;
                    }
                    AbstractSpell spell = com.randomspellpvp.spell.SpellPoolManager.getSpell(msg.spellId);
                    if (spell != null) {
                        TestManager.learnSpell(player, spell);
                    }
                }
                case REPEAT_LAST -> TestManager.repeatLast(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}