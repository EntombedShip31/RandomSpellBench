package com.randomspellbench.capability;

import net.minecraft.world.entity.player.Player;

/**
 * 玩家配置的存取。
 *
 * 使用 Forge 的 Player#getPersistentData()（玩家 ForgeData，随玩家存档一起保存），
 * 不依赖 Capability 注册/附加流程，避免在复杂模组环境中出现
 * 「服务端玩家身上没有能力 → 随机分配报 无法读取玩家配置」的问题。
 */
public final class PlayerConfigStore {
    private static final String KEY = "randomspellbench_config";

    private PlayerConfigStore() {
    }

    /** 读取玩家配置；首次访问返回默认配置。 */
    public static PlayerSpellConfig get(Player player) {
        PlayerSpellConfig config = new PlayerSpellConfig();
        if (player.getPersistentData().contains(KEY)) {
            config.deserializeNBT(player.getPersistentData().getCompound(KEY));
        }
        return config;
    }

    /** 写回玩家配置。 */
    public static void save(Player player, PlayerSpellConfig config) {
        player.getPersistentData().put(KEY, config.serializeNBT());
    }

    /**
     * 在 PlayerEvent.Clone（死亡重生）时把配置原样带到新实体上：
     * /rsta unlock 的解除状态、勾选、等级规则等都跨死亡保留。
     */
    public static void copy(Player from, Player to) {
        if (from.getPersistentData().contains(KEY)) {
            to.getPersistentData().put(KEY, from.getPersistentData().getCompound(KEY).copy());
        }
    }
}
