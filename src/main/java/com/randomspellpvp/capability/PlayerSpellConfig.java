package com.randomspellpvp.capability;

import com.randomspellpvp.Config;
import com.randomspellpvp.spell.AssignedSpell;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每位玩家的测试台配置（基于 Player#getPersistentData() 存取）。
 * 使用 LinkedHashMap 保持法术顺序稳定，方便 UI 展示。
 */
public class PlayerSpellConfig implements INBTSerializable<CompoundTag> {

    private static final String KEY_GLOBAL_RANGE = "GlobalRange";
    private static final String KEY_FILTERS = "Filters";
    private static final String KEY_SPELL_COUNT = "SpellCount";
    private static final String KEY_ASSIGNED = "Assigned";
    private static final String KEY_ASSIGNED_SPELLS = "AssignedSpells";
    private static final String KEY_PREVIOUS_SPELLS = "PreviousSpells";
    private static final String KEY_MODE = "Mode";
    private static final String KEY_LEVEL_MODE = "LevelMode";
    private static final String KEY_FIXED_LEVEL = "FixedLevel";
    private static final String KEY_CURSOR = "Cursor"; // 旧字段（顺序遍历），保留读以兼容旧存档
    private static final String KEY_CHAT_RESULT = "ChatResult";
    private static final String KEY_MIN_ONE_PER_SCHOOL = "MinOnePerSchool";
    private static final String KEY_BYPASS_CREATIVE_ONLY = "BypassCreativeOnly";

    private final Map<String, SpellFilter> filters = new LinkedHashMap<>();
    private SpellLevelRange globalRange = new SpellLevelRange(
            Config.SERVER.defaultMinLevel.get(),
            Config.SERVER.defaultMaxLevel.get());
    private int spellCount = Config.SERVER.defaultSpellCount.get();
    private boolean assigned = false;
    private List<AssignedSpell> assignedSpells = new ArrayList<>();
    /** 上一次的分配结果，用于「撤销」。 */
    private List<AssignedSpell> previousSpells = new ArrayList<>();

    private AssignMode mode = fallback(Config.SERVER.defaultAssignMode.get(), AssignMode.RANDOM);
    private LevelMode levelMode = fallback(Config.SERVER.defaultLevelMode.get(), LevelMode.RANGE);
    private int fixedLevel = Config.SERVER.defaultFixedLevel.get();
    /** 是否在聊天栏播报分配结果（实际上现在用 actionbar）。 */
    private boolean showResultInChat = Config.SERVER.showResultInChat.get();
    /** 是否解除创造模式限制（/rspvp unlock 切换），跨世界持久化。 */
    private boolean bypassCreativeOnly = false;
    /** 随机时每学派至少抽 1 个。 */
    private boolean minOnePerSchool = Config.SERVER.defaultMinOnePerSchool.get();

    public PlayerSpellConfig() {
    }

    // ---------- 法术筛选 ----------

    public SpellFilter getFilter(AbstractSpell spell) {
        return filters.computeIfAbsent(spell.getSpellId(), s -> new SpellFilter());
    }

    public SpellFilter getFilter(String spellId) {
        return filters.computeIfAbsent(spellId, s -> new SpellFilter());
    }

    public void setFilter(String spellId, SpellFilter filter) {
        filters.put(spellId, filter);
    }

    public boolean isSpellEnabled(AbstractSpell spell) {
        SpellFilter f = filters.get(spell.getSpellId());
        // 用 get() 而非 computeIfAbsent：未显式设置的法术视为默认（启用），
        // 避免 enabledSpellCount/countEnabled 遍历整个法术池时为每个法术创建默认 filter 条目
        return f == null || f.isEnabled();
    }

    public int countEnabled(List<AbstractSpell> spells) {
        int count = 0;
        for (AbstractSpell spell : spells) {
            if (isSpellEnabled(spell)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 该法术生效的等级范围（已裁剪到法术合法等级）。
     * 固定等级模式下直接返回 [fixedLevel, fixedLevel]。
     */
    public SpellLevelRange effectiveRange(AbstractSpell spell) {
        if (levelMode == LevelMode.FIXED) {
            return new SpellLevelRange(fixedLevel, fixedLevel).clamp(spell);
        }
        return getFilter(spell).effectiveRange(globalRange).clamp(spell);
    }

    public int enabledSpellCount(List<AbstractSpell> pool) {
        int count = 0;
        for (AbstractSpell spell : pool) {
            if (isSpellEnabled(spell)) {
                count++;
            }
        }
        return count;
    }

    // ---------- 全局设置 ----------

    public SpellLevelRange getGlobalRange() {
        return globalRange;
    }

    public void setGlobalRange(SpellLevelRange globalRange) {
        this.globalRange = globalRange;
    }

    public int getSpellCount() {
        return spellCount;
    }

    public void setSpellCount(int spellCount) {
        this.spellCount = Math.max(1, spellCount);
    }

    // ---------- 分配模式 ----------

    public AssignMode getMode() {
        return mode;
    }

    public void setMode(AssignMode mode) {
        this.mode = mode == null ? AssignMode.RANDOM : mode;
    }

    public LevelMode getLevelMode() {
        return levelMode;
    }

    public void setLevelMode(LevelMode levelMode) {
        this.levelMode = levelMode == null ? LevelMode.RANGE : levelMode;
    }

    public int getFixedLevel() {
        return fixedLevel;
    }

    public void setFixedLevel(int fixedLevel) {
        this.fixedLevel = Math.max(1, fixedLevel);
    }

    // ---------- 学派均衡 ----------

    public boolean isMinOnePerSchool() {
        return minOnePerSchool;
    }

    public void setMinOnePerSchool(boolean minOnePerSchool) {
        this.minOnePerSchool = minOnePerSchool;
    }

    // ---------- 聊天播报 ----------

    public boolean isShowResultInChat() {
        return showResultInChat;
    }

    public void setShowResultInChat(boolean showResultInChat) {
        this.showResultInChat = showResultInChat;
    }

    // ---------- 创造模式限制 ----------

    public boolean isBypassCreativeOnly() {
        return bypassCreativeOnly;
    }

    public void setBypassCreativeOnly(boolean bypassCreativeOnly) {
        this.bypassCreativeOnly = bypassCreativeOnly;
    }

    // ---------- 分配结果 ----------

    public boolean isAssigned() {
        return assigned;
    }

    public void setAssigned(boolean assigned) {
        this.assigned = assigned;
    }

    public List<AssignedSpell> getAssignedSpells() {
        return assignedSpells;
    }

    public void setAssignedSpells(List<AssignedSpell> assignedSpells) {
        this.assignedSpells = new ArrayList<>(assignedSpells);
    }

    public List<AssignedSpell> getPreviousSpells() {
        return previousSpells;
    }

    public void setPreviousSpells(List<AssignedSpell> previousSpells) {
        this.previousSpells = new ArrayList<>(previousSpells);
    }

    /** 在覆盖当前结果之前，把当前结果存入历史以便撤销。 */
    public void pushHistory() {
        if (!assignedSpells.isEmpty()) {
            previousSpells = new ArrayList<>(assignedSpells);
        }
    }

    // ---------- 序列化 ----------

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_GLOBAL_RANGE, globalRange.serializeNBT());

        CompoundTag filterTag = new CompoundTag();
        for (Map.Entry<String, SpellFilter> entry : filters.entrySet()) {
            // 默认筛选不落盘：随机分配会为每个法术建条目，全部写入会让存档持续膨胀
            if (entry.getValue().isDefault()) {
                continue;
            }
            filterTag.put(entry.getKey(), entry.getValue().serializeNBT());
        }
        tag.put(KEY_FILTERS, filterTag);

        tag.putInt(KEY_SPELL_COUNT, spellCount);
        tag.putBoolean(KEY_ASSIGNED, assigned);
        tag.putString(KEY_MODE, mode.name());
        tag.putString(KEY_LEVEL_MODE, levelMode.name());
        tag.putInt(KEY_FIXED_LEVEL, fixedLevel);
        tag.putBoolean(KEY_CHAT_RESULT, showResultInChat);
        tag.putBoolean(KEY_MIN_ONE_PER_SCHOOL, minOnePerSchool);
        tag.putBoolean(KEY_BYPASS_CREATIVE_ONLY, bypassCreativeOnly);

        tag.put(KEY_ASSIGNED_SPELLS, writeSpellList(assignedSpells));
        tag.put(KEY_PREVIOUS_SPELLS, writeSpellList(previousSpells));
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        globalRange = SpellLevelRange.fromNBT(tag.getCompound(KEY_GLOBAL_RANGE));

        filters.clear();
        CompoundTag filterTag = tag.getCompound(KEY_FILTERS);
        for (String key : filterTag.getAllKeys()) {
            filters.put(key, SpellFilter.fromNBT(filterTag.getCompound(key)));
        }

        spellCount = tag.getInt(KEY_SPELL_COUNT);
        if (spellCount < 1) {
            spellCount = Config.SERVER.defaultSpellCount.get();
        }
        assigned = tag.getBoolean(KEY_ASSIGNED);

        mode = parseEnum(tag.getString(KEY_MODE), AssignMode.RANDOM);
        levelMode = parseEnum(tag.getString(KEY_LEVEL_MODE), LevelMode.RANGE);
        fixedLevel = tag.contains(KEY_FIXED_LEVEL) ? Math.max(1, tag.getInt(KEY_FIXED_LEVEL))
                : Config.SERVER.defaultFixedLevel.get();
        showResultInChat = !tag.contains(KEY_CHAT_RESULT) || tag.getBoolean(KEY_CHAT_RESULT);
        minOnePerSchool = tag.getBoolean(KEY_MIN_ONE_PER_SCHOOL);
        bypassCreativeOnly = tag.getBoolean(KEY_BYPASS_CREATIVE_ONLY);

        assignedSpells = readSpellList(tag.getCompound(KEY_ASSIGNED_SPELLS));
        previousSpells = readSpellList(tag.getCompound(KEY_PREVIOUS_SPELLS));
        // KEY_CURSOR（顺序遍历的旧字段）忽略：功能已停用，保留 tag key 仅用于兼容旧存档
    }

    private static <E extends Enum<E>> E fallback(@Nullable E value, E defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <E extends Enum<E>> E parseEnum(String value, E fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static CompoundTag writeSpellList(List<AssignedSpell> spells) {
        CompoundTag listTag = new CompoundTag();
        int i = 0;
        for (AssignedSpell spell : spells) {
            listTag.put("slot" + i, spell.toNBT());
            i++;
        }
        listTag.putInt("size", i);
        return listTag;
    }

    private static List<AssignedSpell> readSpellList(CompoundTag listTag) {
        List<AssignedSpell> result = new ArrayList<>();
        if (listTag == null) {
            return result;
        }
        int size = listTag.getInt("size");
        // 防御：损坏/恶意 NBT 可能写入极大的 size，造成无意义大循环。
        // 上限取远超合理值（法术上限配置仅 1~16）的安全数
        size = Math.max(0, Math.min(size, 256));
        for (int i = 0; i < size; i++) {
            AssignedSpell spell = AssignedSpell.fromNBT(listTag.getCompound("slot" + i));
            if (spell != null) {
                result.add(spell);
            }
        }
        return result;
    }
}