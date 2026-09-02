package com.randomspellbench.client;

import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.spell.AssignedSpell;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端侧的服务端配置镜像（登录/重生/跨维度/打开 GUI 时由 S2CSyncConfigPacket 写入）。
 */
public final class ClientConfigData {
    private static PlayerSpellConfig config = new PlayerSpellConfig();
    private static int maxSpells = 12;
    private static boolean assigned = false;
    private static final List<AssignedSpell> lastResult = new ArrayList<>();
    /** 每次收到服务端同步递增，GUI 据此检测是否需要刷新显示。 */
    private static int syncVersion = 0;
    /** 服务端下发的禁用法术 id 列表（客户端 GUI 用此过滤，确保 UI 显示 = 实际分配范围）。 */
    private static final List<String> serverBannedSpells = new ArrayList<>();
    /** 服务端下发的学派白名单（空 = 不过滤）。 */
    private static final List<String> serverSchoolWhitelist = new ArrayList<>();

    // 对外暴露的只读视图：避免调用方误改内部集合；内部更新后统一重建
    // （getLastResult() 每帧被 drawStatus 调用，用视图可做到零分配）
    private static List<AssignedSpell> lastResultView = List.of();
    private static List<String> bannedView = List.of();
    private static List<String> whitelistView = List.of();

    private ClientConfigData() {
    }

    public static PlayerSpellConfig get() {
        return config;
    }

    public static int getMaxSpells() {
        return maxSpells;
    }

    public static boolean isAssigned() {
        return assigned;
    }

    /** 最近一次分配结果（供 GUI 展示，只读视图）。 */
    public static List<AssignedSpell> getLastResult() {
        return lastResultView;
    }

    /** GUI 打开时检测该版本号，变化则刷新界面显示。 */
    public static int getSyncVersion() {
        return syncVersion;
    }

    /** 服务端下发的禁用法术 id（只读视图）。 */
    public static List<String> getServerBannedSpells() {
        return bannedView;
    }

    /** 服务端下发的学派白名单（只读视图）。 */
    public static List<String> getServerSchoolWhitelist() {
        return whitelistView;
    }

    public static void applyServerConfig(CompoundTag tag, int serverMaxSpells,
                                         List<String> bannedSpells, List<String> schoolWhitelist) {
        if (tag != null) {
            config.deserializeNBT(tag);
        }
        maxSpells = Math.max(1, serverMaxSpells);
        assigned = config.isAssigned();
        serverBannedSpells.clear();
        if (bannedSpells != null) {
            serverBannedSpells.addAll(bannedSpells);
        }
        serverSchoolWhitelist.clear();
        if (schoolWhitelist != null) {
            serverSchoolWhitelist.addAll(schoolWhitelist);
        }
        bannedView = List.copyOf(serverBannedSpells);
        whitelistView = List.copyOf(serverSchoolWhitelist);
        syncVersion++;
    }

    public static void setAssignment(boolean success, List<AssignedSpell> spells) {
        assigned = success;
        config.setAssigned(success);
        lastResult.clear();
        if (spells != null) {
            lastResult.addAll(spells);
            config.setAssignedSpells(spells);
        }
        lastResultView = List.copyOf(lastResult);
    }
}
