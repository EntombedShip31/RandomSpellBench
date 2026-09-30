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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * C2S：把「勾选的法术」批量生成 ISS 卷轴（GUI「生成卷轴」按钮，勾选数 &gt; 1 时触发）。
 *
 * <p>防卡顿设计：勾选 N 个法术时客户端只发<b>一个</b>包，服务端在单次 enqueueWork 里
 * 依次生成全部卷轴、统一入背包、只做一次容器同步与一次汇总播报——
 * 而不是逐个法术发包（每包各触发一次权限校验 + 背包同步 + actionbar 播报，
 * 12 连发会在客户端表现成明显的卡顿与刷屏）。</p>
 *
 * <p>数量上限 {@value #MAX_SCROLLS} 张：客户端截断到前 12 个并发提示，
 * 服务端再兜底截断一次（防伪造包刷物品）。</p>
 */
public class C2SBatchSpawnScrollPacket {
    /** 单次批量生成卷轴的硬上限。 */
    public static final int MAX_SCROLLS = 12;

    private final List<String> spellIds;
    /** true = 每张卷轴直接替换主手（原主手回背包），false = 普通入背包。 */
    private final boolean mainhand;

    public C2SBatchSpawnScrollPacket(List<String> spellIds) {
        this(spellIds, false);
    }

    public C2SBatchSpawnScrollPacket(List<String> spellIds, boolean mainhand) {
        this.spellIds = spellIds == null ? List.of() : spellIds;
        this.mainhand = mainhand;
    }

    public static void encode(C2SBatchSpawnScrollPacket msg, FriendlyByteBuf buf) {
        int n = Math.min(msg.spellIds.size(), MAX_SCROLLS);
        buf.writeVarInt(n);
        buf.writeBoolean(msg.mainhand);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(msg.spellIds.get(i));
        }
    }

    public static C2SBatchSpawnScrollPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_SCROLLS);
        boolean mainhand = buf.readBoolean();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf());
        }
        return new C2SBatchSpawnScrollPacket(ids, mainhand);
    }

    public static void handle(C2SBatchSpawnScrollPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            List<AbstractSpell> spells = new ArrayList<>(msg.spellIds.size());
            for (String id : msg.spellIds) {
                AbstractSpell spell = SpellPoolManager.getSpell(id);
                if (spell == null || AssignedSpell.isNoneSpell(spell)) {
                    continue; // 单个失效法术跳过，不阻塞整批
                }
                spells.add(spell);
            }
            if (spells.isEmpty()) {
                player.sendSystemMessage(Component.translatable("command.randomspellbench.error.spell_not_found")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            TestManager.spawnScrolls(player, spells, 0, msg.mainhand);
        });
        ctx.setPacketHandled(true);
    }
}
