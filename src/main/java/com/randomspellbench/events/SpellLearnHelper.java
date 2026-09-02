package com.randomspellbench.events;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 让玩家永久学习一个 ISS 法术。
 *
 * ISS 的 SyncedSpellData.learnSpell(AbstractSpell) 与 MagicData.getSyncedData()
 * 的返回类型在 capabilities 包，不在 api jar 中（编译期不可见），
 * 所以 MagicData 与 SyncedSpellData 都通过反射访问。
 * ISS SpellBookScreen 的长按学习也是调此方法。
 */
public final class SpellLearnHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SpellLearnHelper.class);

    /** 缓存的 SyncedSpellData.learnSpell 方法引用（首次调用时解析）。 */
    private static volatile Method cachedLearnSpell;
    /** 缓存的 MagicData.getSyncedData 方法引用。 */
    private static volatile Method cachedGetSyncedData;
    /** 首次失败已 warn 的标记（ISS 版本不兼容时避免每次长按都刷 warn 日志）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean loggedFailure =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private SpellLearnHelper() {
    }

    /** 学习成功返回 true，法术已学过、反射失败、参数无效返回 false。 */
    public static boolean learn(ServerPlayer player, AbstractSpell spell) {
        if (player == null || spell == null) {
            return false;
        }
        try {
            Object magicData = MagicData.getPlayerMagicData(player);
            if (magicData == null) {
                return false;
            }
            Object syncedData = invoke(cachedGetSyncedData, magicData);
            if (syncedData == null) {
                return false;
            }
            Method method = cachedLearnSpell;
            if (method == null) {
                Class<?>[] paramTypes = { AbstractSpell.class };
                try {
                    method = syncedData.getClass().getMethod("learnSpell", paramTypes);
                } catch (NoSuchMethodException e) {
                    method = syncedData.getClass().getMethod("learnSpell", AbstractSpell.class, boolean.class);
                }
                cachedLearnSpell = method;
            }
            if (method.getParameterCount() == 2) {
                method.invoke(syncedData, spell, false);
            } else {
                method.invoke(syncedData, spell);
            }
            return true;
        } catch (Throwable t) {
            // 首次失败用 warn（便于排查 ISS 版本兼容问题），之后降级为 debug，
            // 避免 ISS 不兼容时玩家反复长按导致日志刷屏
            if (loggedFailure.compareAndSet(false, true)) {
                LOG.warn("Failed to learn spell (后续同类失败只记 debug) {} for {} (可能原因: ISS 版本不兼容 / 反射方法签名变化): {}",
                        spell.getSpellId(), player.getName().getString(), t.toString());
            } else {
                LOG.debug("Failed to learn spell {} for {}: {}",
                        spell.getSpellId(), player.getName().getString(), t.toString());
            }
            return false;
        }
    }

    private static Object invoke(Method cached, Object target) throws Exception {
        if (cached == null) {
            Method method = MagicData.class.getMethod("getSyncedData");
            cachedGetSyncedData = method;
            return method.invoke(target);
        }
        return cached.invoke(target);
    }
}