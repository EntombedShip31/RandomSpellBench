package com.randomspellpvp.capability;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 法术等级范围（最小等级 ~ 最大等级）。
 */
public class SpellLevelRange {
    public static final String KEY_MIN = "min";
    public static final String KEY_MAX = "max";

    private int minLevel;
    private int maxLevel;

    public SpellLevelRange() {
        this(1, 1);
    }

    public SpellLevelRange(int minLevel, int maxLevel) {
        // 保证 1 <= min <= max：NBT 缺字段会读出 0，而等级 0 是非法值
        this.minLevel = Math.max(1, Math.min(minLevel, maxLevel));
        this.maxLevel = Math.max(1, Math.max(minLevel, maxLevel));
        if (this.maxLevel < this.minLevel) {
            this.maxLevel = this.minLevel;
        }
    }

    public int getMinLevel() {
        return minLevel;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMinLevel(int minLevel) {
        this.minLevel = Math.max(1, Math.min(minLevel, maxLevel));
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = Math.max(minLevel, maxLevel);
    }

    public void setRange(int minLevel, int maxLevel) {
        this.minLevel = Math.min(minLevel, maxLevel);
        this.maxLevel = Math.max(minLevel, maxLevel);
    }

    /** 将该范围裁剪到某个法术的有效等级区间 [1, getMaxLevel()]。 */
    public SpellLevelRange clamp(AbstractSpell spell) {
        // 防御：部分附属法术 getMaxLevel() 可能返回 0（注册不完整），
        // 直接 Mth.clamp(v, 1, 0) 会得到 0（非法等级），这里先保证上界 >= 1
        int upper = Math.max(1, spell.getMaxLevel());
        int lo = Mth.clamp(minLevel, 1, upper);
        int hi = Mth.clamp(maxLevel, 1, upper);
        return new SpellLevelRange(Math.min(lo, hi), Math.max(lo, hi));
    }

    /** 在该范围内均匀随机取一个等级（含端点）。 */
    public int randomLevel(RandomSource random) {
        if (maxLevel <= minLevel) {
            return minLevel;
        }
        return minLevel + random.nextInt(maxLevel - minLevel + 1);
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_MIN, minLevel);
        tag.putInt(KEY_MAX, maxLevel);
        return tag;
    }

    public static SpellLevelRange fromNBT(CompoundTag tag) {
        if (tag == null) {
            return new SpellLevelRange();
        }
        return new SpellLevelRange(tag.getInt(KEY_MIN), tag.getInt(KEY_MAX));
    }

    @Override
    public String toString() {
        return minLevel + "-" + maxLevel;
    }
}
