package com.randomspellbench.network.packet;

import com.randomspellbench.spell.SpellbookDismantler;
import com.randomspellbench.testbench.TestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：一键拆下自己法术书里的法术（拆成卷轴放进背包）。
 *
 * 来源枚举刻意定义在包内（{@link From}）而非复用 {@link SpellbookDismantler.Source}，
 * 让客户端侧（按键 / GUI）只依赖网络层，不必加载服务端的拆分类。
 */
public class C2SExtractSpellsPacket {

    /** 从哪里找法术书：AUTO = 主手 → 副手 → 饰品栏。 */
    public enum From { AUTO, HAND, CURIO }

    private final From from;

    public C2SExtractSpellsPacket(From from) {
        this.from = from == null ? From.AUTO : from;
    }

    public static void encode(C2SExtractSpellsPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.from.name());
    }

    public static C2SExtractSpellsPacket decode(FriendlyByteBuf buf) {
        From from;
        try {
            from = From.valueOf(buf.readUtf());
        } catch (IllegalArgumentException e) {
            from = From.AUTO;
        }
        return new C2SExtractSpellsPacket(from);
    }

    public static void handle(C2SExtractSpellsPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            SpellbookDismantler.Source source = switch (msg.from) {
                case HAND -> SpellbookDismantler.Source.HAND;
                case CURIO -> SpellbookDismantler.Source.CURIO;
                case AUTO -> SpellbookDismantler.Source.AUTO;
            };
            TestManager.extractSpells(player, source);
        });
        ctx.setPacketHandled(true);
    }
}
