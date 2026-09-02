package com.randomspellbench.capability;

/**
 * 等级决定方式。
 */
public enum LevelMode {
    /** 在等级范围内随机取值。 */
    RANGE,
    /** 固定为指定等级（便于对比同一法术不同等级的强度）。 */
    FIXED;

    public LevelMode next() {
        LevelMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
