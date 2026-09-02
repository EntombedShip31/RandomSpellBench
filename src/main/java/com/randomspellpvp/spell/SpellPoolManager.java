package com.randomspellpvp.spell;

import com.randomspellpvp.Config;
import com.randomspellpvp.RandomSpellPVP;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 法术池管理：扫描所有已注册法术并应用全局过滤（enabled + 配置黑名单）。
 * 兼容其他附属模组通过 DeferredRegister 注册的自定义法术。
 *
 * 实现要点：1.20.1 中所有法术注册在 SpellRegistry.SPELL_REGISTRY_KEY 对应的注册表里，
 * 通过 SpellRegistry.REGISTRY.get().getValues() 即可取到（含附属模组法术）。
 */
public final class SpellPoolManager {
    private static List<AbstractSpell> cachedSpells;

    private SpellPoolManager() {
    }

    /** 获取全部「可用」法术：已启用、非 none、未被服务器禁用、通过学派白名单。 */
    public static List<AbstractSpell> getAvailableSpells() {
        if (cachedSpells == null) {
            List<? extends String> schoolFilter = Config.SERVER.schoolWhitelist.get();
            List<AbstractSpell> spells = new ArrayList<>();
            try {
                // 与 iss_ponder 一致，使用 ISS 官方的 getEnabledSpells() 枚举
                for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
                    if (spell == null || AssignedSpell.isNoneSpell(spell)) {
                        continue;
                    }
                    if (Config.isSpellBanned(spell.getSpellId())) {
                        continue;
                    }
                    if (!schoolFilter.isEmpty()) {
                        ResourceLocation schoolId = spell.getSchoolType() == null
                                ? null : spell.getSchoolType().getId();
                        if (schoolId == null || !schoolMatches(schoolFilter, schoolId)) {
                            continue;
                        }
                    }
                    spells.add(spell);
                }
            } catch (Exception e) {
                RandomSpellPVP.LOGGER.error("Failed to scan spell registry", e);
            }
            spells.sort(Comparator.comparing(AbstractSpell::getSpellName));
            cachedSpells = Collections.unmodifiableList(spells);
        }
        return cachedSpells;
    }

    public static AbstractSpell getSpell(String spellId) {
        return SpellRegistry.getSpell(spellId);
    }

    public static AbstractSpell getSpell(ResourceLocation id) {
        return SpellRegistry.getSpell(id);
    }

    /** 缓存失效（服务器启动 / /rspvp reload 时调用）。 */
    public static void invalidate() {
        cachedSpells = null;
    }

    /**
     * 配置写 "eldritch" 或 "irons_spellbooks:eldritch" 都视为同一学派，并大小写不敏感。
     * 旧版本用 contains(schoolId.toString()) 会因为命名空间永远不匹配，导致白名单把全部法术过滤掉。
     */
    public static boolean schoolMatches(List<? extends String> configured, ResourceLocation schoolId) {
        String full = schoolId.toString().toLowerCase(Locale.ROOT);
        String path = schoolId.getPath().toLowerCase(Locale.ROOT);
        for (String entry : configured) {
            if (entry == null) continue;
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && (e.equals(full) || e.equals(path))) {
                return true;
            }
        }
        return false;
    }
}
