package com.randomspellbench.network.packet;

import com.randomspellbench.client.ClientConfigData;
import com.randomspellbench.spell.AssignedSpell;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：同步随机分配结果（成功标志 + 法术列表）。
 */
public class S2CAssignedSpellsPacket {
    private final boolean success;
    private final List<AssignedSpell> spells;

    public S2CAssignedSpellsPacket(boolean success, List<AssignedSpell> spells) {
        this.success = success;
        this.spells = spells == null ? List.of() : spells;
    }

    public static void encode(S2CAssignedSpellsPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeVarInt(msg.spells.size());
        for (AssignedSpell spell : msg.spells) {
            spell.encode(buf);
        }
    }

    public static S2CAssignedSpellsPacket decode(FriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        int size = buf.readVarInt();
        List<AssignedSpell> spells = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            spells.add(AssignedSpell.decode(buf));
        }
        return new S2CAssignedSpellsPacket(success, spells);
    }

    public static void handle(S2CAssignedSpellsPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> ClientConfigData.setAssignment(msg.success, msg.spells));
        ctx.setPacketHandled(true);
    }
}
