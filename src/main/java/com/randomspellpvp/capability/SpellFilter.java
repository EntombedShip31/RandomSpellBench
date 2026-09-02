package com.randomspellpvp.capability;

import net.minecraft.nbt.CompoundTag;

/**
 * 单个法术的筛选配置：
 * - enabled       是否参与随机池
 * - useGlobalRange 是否使用全局等级范围（否则使用本法术独立范围）
 * - min/maxLevel   本法术独立范围
 */
public class SpellFilter {
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_USE_GLOBAL = "useGlobal";
    public static final String KEY_MIN = "min";
    public static final String KEY_MAX = "max";

    private boolean enabled = true;
    private boolean useGlobalRange = true;
    private int minLevel = 1;
    private int maxLevel = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 是否为「默认」筛选（全启用、用全局范围、范围 1-1）。
     * 随机分配时会因 getFilter 的 computeIfAbsent 为每个法术建条目，
     * 这些默认条目不必写入存档，否则会持续膨胀玩家 NBT。
     */
    public boolean isDefault() {
        return enabled && useGlobalRange && minLevel == 1 && maxLevel == 1;
    }

    public boolean isUseGlobalRange() {
        return useGlobalRange;
    }

    public void setUseGlobalRange(boolean useGlobalRange) {
        this.useGlobalRange = useGlobalRange;
    }

    public int getMinLevel() {
        return minLevel;
    }

    public void setMinLevel(int minLevel) {
        this.minLevel = Math.max(1, minLevel);
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = Math.max(1, maxLevel);
    }

    /** 获取该法术生效的等级范围（遵循 useGlobalRange 语义）。 */
    public SpellLevelRange effectiveRange(SpellLevelRange globalRange) {
        if (useGlobalRange) {
            return new SpellLevelRange(globalRange.getMinLevel(), globalRange.getMaxLevel());
        }
        return new SpellLevelRange(minLevel, maxLevel);
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(KEY_ENABLED, enabled);
        tag.putBoolean(KEY_USE_GLOBAL, useGlobalRange);
        tag.putInt(KEY_MIN, minLevel);
        tag.putInt(KEY_MAX, maxLevel);
        return tag;
    }

    public static SpellFilter fromNBT(CompoundTag tag) {
        SpellFilter filter = new SpellFilter();
        if (tag == null) {
            return filter;
        }
        filter.enabled = tag.getBoolean(KEY_ENABLED);
        filter.useGlobalRange = tag.getBoolean(KEY_USE_GLOBAL);
        // 缺失/非法值回落到 1：否则 minLevel=0 会让 isDefault() 判定为 false，
        // 导致「默认筛选」也被写入存档，玩家 NBT 随法术数缓慢膨胀
        filter.minLevel = Math.max(1, tag.getInt(KEY_MIN));
        filter.maxLevel = Math.max(1, tag.getInt(KEY_MAX));
        return filter;
    }
}
