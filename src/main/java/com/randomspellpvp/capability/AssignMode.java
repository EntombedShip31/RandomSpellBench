package com.randomspellpvp.capability;

/**
 * 法术挑选策略。
 */
public enum AssignMode {
    /** 按权重随机抽取 N 个互不重复的法术。 */
    RANDOM,
    /** 把启用法术池中的全部法术一次写入（受 maxSpells 限制）。 */
    ALL,
    /** 按列表顺序依次取 N 个，游标自动前进，可反复触发以遍历整个池子。 */
    SEQUENTIAL;

    public AssignMode next() {
        AssignMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
