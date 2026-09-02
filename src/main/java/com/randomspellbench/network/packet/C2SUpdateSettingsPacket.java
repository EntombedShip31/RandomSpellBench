package com.randomspellbench.network.packet;

import com.randomspellbench.Config;
import com.randomspellbench.capability.AssignMode;
import com.randomspellbench.capability.LevelMode;
import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.capability.PlayerSpellConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：同步随机规则设置（数量、分配模式、等级模式、固定等级、学派均衡）。
 */
public class C2SUpdateSettingsPacket {
    private final int spellCount;
    private final AssignMode mode;
    private final LevelMode levelMode;
    private final int fixedLevel;
    private final boolean minOnePerSchool;

    public C2SUpdateSettingsPacket(int spellCount, AssignMode mode, LevelMode levelMode,
                                   int fixedLevel, boolean minOnePerSchool) {
        this.spellCount = spellCount;
        this.mode = mode == null ? AssignMode.RANDOM : mode;
        this.levelMode = levelMode == null ? LevelMode.RANGE : levelMode;
        this.fixedLevel = fixedLevel;
        this.minOnePerSchool = minOnePerSchool;
    }

    public static void encode(C2SUpdateSettingsPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.spellCount);
        buf.writeUtf(msg.mode.name());
        buf.writeUtf(msg.levelMode.name());
        buf.writeVarInt(msg.fixedLevel);
        buf.writeBoolean(msg.minOnePerSchool);
    }

    public static C2SUpdateSettingsPacket decode(FriendlyByteBuf buf) {
        int spellCount = buf.readVarInt();
        AssignMode mode;
        LevelMode levelMode;
        try {
            mode = AssignMode.valueOf(buf.readUtf());
        } catch (IllegalArgumentException e) {
            mode = AssignMode.RANDOM;
        }
        try {
            levelMode = LevelMode.valueOf(buf.readUtf());
        } catch (IllegalArgumentException e) {
            levelMode = LevelMode.RANGE;
        }
        int fixedLevel = buf.readVarInt();
        boolean minOnePerSchool = buf.readBoolean();
        return new C2SUpdateSettingsPacket(spellCount, mode, levelMode, fixedLevel, minOnePerSchool);
    }

    public static void handle(C2SUpdateSettingsPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // 权限校验：无权限玩家不得改动随机规则设置
            if (!com.randomspellbench.events.PermissionHelper.canUse(player)) {
                player.displayClientMessage(
                        com.randomspellbench.events.PermissionHelper.creativeOnlyMessage(), true);
                return;
            }
            PlayerSpellConfig config = PlayerConfigStore.get(player);
            // 服务端权威钳制：数量不能超过 maxSpells，避免客户端显示值与生效值不一致
            config.setSpellCount(Mth.clamp(msg.spellCount, 1, Math.max(1, Config.SERVER.maxSpells.get())));
            config.setMode(msg.mode);
            config.setLevelMode(msg.levelMode);
            config.setFixedLevel(msg.fixedLevel);
            config.setMinOnePerSchool(msg.minOnePerSchool);
            PlayerConfigStore.save(player, config);
        });
        ctx.setPacketHandled(true);
    }
}
