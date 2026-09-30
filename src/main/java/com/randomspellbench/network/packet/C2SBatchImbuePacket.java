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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * C2S：把「勾选的法术」批量注入到目标槽位的物品（GUI「注入法术」按钮，勾选数 &gt; 1 时触发）。
 *
 * <p>与单发 {@link C2SImbueSpellPacket} 的区别：一次包带全部法术 id，
 * 服务端在同一批里受「单物品注入上限」（默认 3，{@code maxSpellsPerItem}）约束——
 * 能装几个装几个，装不下的跳过并在汇总播报里说明；
 * 物品本身的注入法术数已经超过上限时，直接报「注入法术超过装备上限」并整单拒绝。</p>
 *
 * <p>等级不单独传输：服务端按每个法术各自的「等级规则」下限取值，
 * 与单发注入 / 点击思索的预览等级保持同一来源。</p>
 */
public class C2SBatchImbuePacket {
    /**
     * 防伪造包：单次最多注入的法术数。
     * 上限对齐书容量 20（{@code maxSpells} 的配置上限）——装备上限默认 3，书的批量一次最多写 20 个。
     */
    public static final int MAX_SPELLS = 20;

    private final List<String> spellIds;
    /** {@link ImbueTarget#key()}。 */
    private final String target;

    public C2SBatchImbuePacket(List<String> spellIds, String target) {
        this.spellIds = spellIds == null ? List.of() : spellIds;
        this.target = target == null ? "" : target;
    }

    public static void encode(C2SBatchImbuePacket msg, FriendlyByteBuf buf) {
        int n = Math.min(msg.spellIds.size(), MAX_SPELLS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(msg.spellIds.get(i));
        }
        buf.writeUtf(msg.target);
    }

    public static C2SBatchImbuePacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_SPELLS);
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf());
        }
        return new C2SBatchImbuePacket(ids, buf.readUtf());
    }

    public static void handle(C2SBatchImbuePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
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
            SpellImbueManager.report(player, SpellImbueManager.imbueBatch(player, spells, target));
        });
        ctx.setPacketHandled(true);
    }
}
