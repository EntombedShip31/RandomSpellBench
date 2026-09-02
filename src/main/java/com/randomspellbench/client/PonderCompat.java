package com.randomspellbench.client;

import com.randomspellbench.RandomSpellPVP;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * 与 iss_ponder（法术预览）模组的软联动。
 *
 * 设计要点（不造成卡顿）：
 * - 非硬依赖：编译期不引用任何 iss_ponder 类，全部反射调用；
 * - 检测：仅首次调用时通过 ModList.isLoaded("iss_ponder") 检测一次并缓存，
 *   绝不每帧/每 tick 查询注册表或类加载；
 * - 触发：反射调用 ClientPreviewController.request(spellId, level)。
 *   iss_ponder 的这个方法会发送 StartPreview 网络包并关闭当前界面
 *   （Minecraft.setScreen(null)），随后由 iss_ponder 自身打开 SpellPreviewScreen，
 *   即「直接跳转到该模组的法术演示界面」。
 */
public final class PonderCompat {
    private static final String PONDER_MOD_ID = "iss_ponder";
    private static final String CONTROLLER_CLASS = "com.p1nero.iss_ponder.client.ClientPreviewController";
    private static final String PROJECTION_CLASS = "com.p1nero.iss_ponder.client.PreviewProjection";

    /** 缓存：是否检测到 iss_ponder。null = 未检测。 */
    private static Boolean loaded;
    /** 缓存的反射方法引用（首次触发时解析）。 */
    private static Method requestMethod;
    private static Method projectionStartMethod;
    private static Method projectionClearMethod;

    private PonderCompat() {
    }

    /** iss_ponder 是否已加载（结果缓存，不重复扫描）。 */
    public static boolean isPonderLoaded() {
        if (loaded == null) {
            boolean present = ModList.get() != null && ModList.get().isLoaded(PONDER_MOD_ID);
            loaded = present;
            RandomSpellPVP.LOGGER.info("iss_ponder integration {}", present ? "enabled" : "disabled");
        }
        return loaded;
    }

    /**
     * 请求打开某法术的 iss_ponder 预览（会关闭当前 GUI 并跳转到预览界面）。
     *
     * @return true 表示请求已成功发出；反射失败或模组未加载返回 false（供调用方兜底提示）。
     */
    public static boolean requestPreview(AbstractSpell spell, int level) {
        if (spell == null || !isPonderLoaded()) {
            return false;
        }
        try {
            ResourceLocation spellId = ResourceLocation.tryParse(spell.getSpellId());
            if (spellId == null) {
                return false;
            }
            Method method = requestMethod;
            if (method == null) {
                Class<?> clazz = Class.forName(CONTROLLER_CLASS);
                method = clazz.getMethod("request", ResourceLocation.class, int.class);
                requestMethod = method;
            }
            method.invoke(null, spellId, level);
            return true;
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.debug("Failed to request iss_ponder preview for {}: {}",
                    spell.getSpellId(), t.toString());
            return false;
        }
    }

    /**
     * 预热 iss_ponder 的投影场景（PonderLevel / 投影玩家 / 方块），不打开任何界面。
     * <p>
     * iss_ponder 真正打开预览（ClientPreviewController.open）时会调用 PreviewProjection.start()，
     * 该调用每次先 clear 再重建。长按期间提前 start 的净收益：
     * PonderLevel 方块模型、投影玩家贴图等资源首次加载后进入 MC 资源缓存，
     * 打开预览时的二次构建明显更快；同时完成 create/ponder 相关类的类加载。
     * start 幂等且异常安全（失败不影响后续 request）。
     *
     * @return true 表示预热已发出；模组未加载 / 玩家或关卡缺失 / 反射失败返回 false。
     */
    public static boolean prewarm() {
        if (!isPonderLoaded()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return false;
        }
        try {
            Method start = projectionStartMethod;
            if (start == null) {
                Class<?> clazz = Class.forName(PROJECTION_CLASS);
                start = clazz.getMethod("start", double.class, double.class, double.class);
                projectionStartMethod = start;
            }
            var pos = mc.player.position();
            start.invoke(null, pos.x, pos.y, pos.z);
            return true;
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.debug("iss_ponder prewarm failed: {}", t.toString());
            return false;
        }
    }

    /** 取消预热：清掉提前创建的投影场景（长按未达成时调用，避免场景残留）。 */
    public static void clearPrewarm() {
        if (!isPonderLoaded()) {
            return;
        }
        try {
            Method clear = projectionClearMethod;
            if (clear == null) {
                Class<?> clazz = Class.forName(PROJECTION_CLASS);
                clear = clazz.getMethod("clear");
                projectionClearMethod = clear;
            }
            clear.invoke(null);
        } catch (Throwable t) {
            RandomSpellPVP.LOGGER.debug("iss_ponder prewarm clear failed: {}", t.toString());
        }
    }
}