package com.randomspellpvp.compat;

import com.randomspellpvp.RandomSpellPVP;
import io.redspace.ironsspellbooks.api.magic.MagicData;

import java.lang.reflect.Method;

/**
 * 对 ISS 非稳定 API 的兼容访问。
 * MagicData.getPlayerCooldowns()/getPlayerRecasts() 返回的
 * PlayerCooldowns / PlayerRecasts 类位于 capabilities 包（不在稳定 API 中），
 * 为避免编译依赖整模组，这里用反射调用其清空方法。
 * 反射失败只会导致冷却未清空，不影响主流程。
 */
public final class MagicCompat {
    private MagicCompat() {
    }

    public static void clearCooldowns(MagicData magicData) {
        try {
            Object cooldowns = magicData.getClass().getMethod("getPlayerCooldowns").invoke(magicData);
            if (cooldowns == null) {
                return;
            }
            // 优先精确方法名；ISS 各版本命名不统一，方法缺失或调用失败时
            // 退化为「无参且名字含 reset/clear」的兼容匹配（getMethods 顺序不定，逐个尝试）。
            try {
                cooldowns.getClass().getMethod("clearCooldowns").invoke(cooldowns);
                return;
            } catch (Throwable ignored) {
                // 落到兼容匹配
            }
            for (Method m : cooldowns.getClass().getMethods()) {
                if (m.getParameterCount() == 0
                        && (m.getName().contains("reset") || m.getName().contains("clear"))) {
                    try {
                        m.invoke(cooldowns);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.debug("clearCooldowns failed: {}", t.toString());
        }
    }

    public static void clearRecasts(MagicData magicData) {
        try {
            Object recasts = magicData.getClass().getMethod("getPlayerRecasts").invoke(magicData);
            if (recasts != null) {
                try {
                    recasts.getClass().getMethod("clearRecasts").invoke(recasts);
                } catch (NoSuchMethodException e) {
                    recasts.getClass().getMethod("clear").invoke(recasts);
                }
            }
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.debug("clearRecasts fallback failed: {}", t.toString());
        }
    }
}
