package com.randomspellpvp.client;

import com.randomspellpvp.RandomSpellPVP;
import com.randomspellpvp.client.gui.SpellConfigScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

@Mod.EventBusSubscriber(modid = RandomSpellPVP.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    // ---------------- 思索（iss_ponder 预览）返回状态机 ----------------
    //
    // 逆向 iss_ponder 1.0.3 的 ClientPreviewController.request() 后发现：
    //   1. request() 立即 setScreen(null)（关闭测试台），随后等服务端回包；
    //   2. 回包到达后才调用 open()：同步构建投影场景并 setScreen(new SpellPreviewScreen(...))
    //      （无条件覆盖当前任意 screen，不要求当前 screen == null）；
    //   3. 请求内部有 5s 超时（isPending 自动重置），超时后不会打开任何界面；
    //   4. SpellPreviewScreen.isPauseScreen() == false，按 ESC / 关闭动画结束会
    //      setScreen(null)，removed() 里发送 EndPreview 并清投影。
    //
    // 由此带来的两个 bug 与对策：
    //   - “白一下才到 iss_ponder”：request 与 open 之间的网络往返窗口 screen==null，
    //     会渲染游戏画面（白/闪）。对策：request 成功后立刻打开本模组的深色“加载遮罩”，
    //     被 iss_ponder 的 SpellPreviewScreen 无条件覆盖，全程不露游戏画面。
    //   - “思索后退出回游戏而非回测试台”：旧逻辑在 screen==null 时立刻重建测试台，
    //     与 iss_ponder 回包打开 SpellPreviewScreen 竞争（被覆盖后再关闭就回不去了）。
    //     对策：状态机等待 iss_ponder 接管（PONDER_OPEN），在其关闭后再恢复测试台。
    private enum Phase { IDLE, AWAITING_PONDER, PONDER_OPEN }

    private static Phase phase = Phase.IDLE;
    /** request 发出的时间戳（毫秒）。 */
    private static long ponderRequestedAtMs = 0;
    /** iss_ponder 内部 pending 超时为 5s；这里略大用于兜底恢复测试台。 */
    private static final long PONDER_TIMEOUT_MS = 6000L;
    /** AWAITING 期间 screen 变 null（玩家 ESC 关了遮罩等）的容忍窗口，过后按取消处理。 */
    private static final long PONDER_CANCEL_GRACE_MS = 400L;
    /** iss_ponder 所有客户端界面类名前缀（反射安全，无硬依赖）。 */
    private static final String PONDER_SCREEN_PREFIX = "com.p1nero.iss_ponder.client.";

    // ---------------- 思索返回状态（从测试台触发思索 → 关闭预览 → 回到测试台 + 恢复状态） ----------------

    /** 标记有保存的状态待恢复。 */
    private static boolean returnStatePending = false;
    /** 上次保存的选中法术 id（null 表示无选中）。 */
    @Nullable
    private static String pendingSpellId;
    /** 搜索词。 */
    @Nullable
    private static String pendingSearch;
    /** 列表滚动偏移。 */
    private static int pendingScroll;
    /** 折叠的分组集合。 */
    @Nullable
    private static Set<ResourceLocation> pendingCollapsed;

    /**
     * SpellConfigScreen.previewSelected 在调 PonderCompat.requestPreview 前调用：
     * 保存当前界面状态，等预览关闭后恢复。
     */
    public static void markReturn(SpellConfigScreen screen) {
        pendingSpellId = screen.getSelectedSpellId();
        pendingSearch = screen.getSearchText();
        pendingScroll = screen.getListScroll();
        pendingCollapsed = screen.getCollapsedGroups();
        returnStatePending = true;
    }

    /** SpellConfigScreen.onClose 主动关闭时清除标记，避免 ESC 后被错误恢复。 */
    public static void clearReturn() {
        returnStatePending = false;
        pendingSpellId = null;
        pendingSearch = null;
        pendingScroll = 0;
        pendingCollapsed = null;
    }

    /** SpellConfigScreen.init 末尾调用：应用状态并清空标记。返回 true 表示应用了状态。 */
    public static boolean consumeReturnState(SpellConfigScreen screen) {
        if (!returnStatePending) {
            return false;
        }
        returnStatePending = false;
        screen.applyReturnState(pendingSpellId, pendingSearch, pendingScroll,
                pendingCollapsed == null ? null : new HashSet<>(pendingCollapsed));
        pendingSpellId = null;
        pendingSearch = null;
        pendingScroll = 0;
        pendingCollapsed = null;
        return true;
    }

    /**
     * iss_ponder 的 request 已成功发出后调用：进入 AWAITING 并立刻打开加载遮罩。
     * request 已经把当前测试台 setScreen(null)，若不遮罩，网络往返窗口会露出游戏画面（白屏）。
     * iss_ponder 打开 SpellPreviewScreen 时会无条件覆盖该遮罩，无需手动关闭。
     */
    public static void beginPreviewWait() {
        if (phase != Phase.IDLE) {
            return;
        }
        ponderRequestedAtMs = System.currentTimeMillis();
        phase = Phase.AWAITING_PONDER;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new PreviewLoadingScreen());
        }
    }

    private static boolean isPonderScreen(@Nullable Screen screen) {
        return screen != null && screen.getClass().getName().startsWith(PONDER_SCREEN_PREFIX);
    }

    /** 重建测试台并恢复 markReturn 保存的状态；无保存状态则直接回游戏。 */
    private static void reopenConfigScreen() {
        if (returnStatePending) {
            // 新 Screen.init 会调用 consumeReturnState 应用状态
            Minecraft.getInstance().setScreen(new SpellConfigScreen());
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /** iss_ponder 超时/失败/用户取消：提示并恢复测试台。 */
    private static void failPreview() {
        phase = Phase.IDLE;
        Minecraft mc = Minecraft.getInstance();
        if (returnStatePending && mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("screen.randomspellbench.preview_failed_timeout"), true);
        }
        reopenConfigScreen();
    }

    public static void openConfigScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 按 K/F6 打开 GUI 时检查客户端可知的权限信号：本地创造模式 或 玩家级 bypass。
        // 全局 bypassCreativeOnly 客户端不可见（保守取 false），但该配置一般不开。
        if (!mc.player.isCreative() && !ClientConfigData.get().isBypassCreativeOnly()) {
            mc.player.displayClientMessage(
                    Component.translatable("command.randomspellbench.error.creative_only",
                            Component.translatable("command.randomspellbench.unlock.hint"))
                            .withStyle(ChatFormatting.RED),
                    true);
            return;
        }
        mc.setScreen(new SpellConfigScreen());
    }

    /**
     * 服务端已授权后打开的入口（供 /rsta config 的 S2COpenScreenPacket 使用）：
     * 不再做客户端本地权限校验。服务端 canUse 已通过，
     * 且客户端镜像可能因同步延迟尚未更新（如 unlock 后立刻 /rsta config）。
     */
    public static void openConfigScreenFromServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new SpellConfigScreen());
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();

        if (phase == Phase.AWAITING_PONDER) {
            Screen s = mc.screen;
            if (s != null && isPonderScreen(s)) {
                // iss_ponder 已接管：进入观察，等预览关闭后恢复测试台
                phase = Phase.PONDER_OPEN;
            } else if (s instanceof PreviewLoadingScreen) {
                // 加载遮罩仍在前台：等 iss_ponder 接管或超时兜底
                if (now - ponderRequestedAtMs > PONDER_TIMEOUT_MS) {
                    failPreview();
                }
            } else if (s == null) {
                // iss_ponder 不再额外打开界面（如玩家 ESC 关了遮罩）：短暂容忍后按失败处理
                if (now - ponderRequestedAtMs > PONDER_CANCEL_GRACE_MS) {
                    failPreview();
                }
            } else {
                // 出现其它界面（罕见）：放弃本次预览追踪，不做恢复
                phase = Phase.IDLE;
                returnStatePending = false;
            }
        } else if (phase == Phase.PONDER_OPEN) {
            Screen s = mc.screen;
            if (!isPonderScreen(s)) {
                // SpellPreviewScreen 已被关闭或替换
                phase = Phase.IDLE;
                if (s == null) {
                    // 正常关闭（ESC / 退出动画结束）→ 回到测试台并恢复原状态
                    reopenConfigScreen();
                    return;
                }
                // 切到了其它界面：放弃恢复
                returnStatePending = false;
            }
        }

        if (ClientSetup.OPEN_CONFIG.consumeClick() && phase == Phase.IDLE) {
            openConfigScreen();
        }
    }

    // ---------------- 加载遮罩（挡住 request→open 窗口的空白/游戏画面） ----------------

    /** 深色加载界面：仅占位，真正接管由 iss_ponder 的 SpellPreviewScreen 完成。 */
    private static final class PreviewLoadingScreen extends Screen {
        private PreviewLoadingScreen() {
            super(Component.translatable("screen.randomspellbench.preview_loading"));
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fillGradient(0, 0, this.width, this.height, 0xC00B0703, 0xD8160D04);
            String title = Component.translatable("screen.randomspellbench.preview_loading").getString();
            g.drawCenteredString(this.font, title, this.width / 2, this.height / 2 - 10, 0xFFFFB566);
            String sub = Component.translatable("screen.randomspellbench.preview_loading_sub").getString();
            g.drawCenteredString(this.font, sub, this.width / 2, this.height / 2 + 8, 0xFF80705A);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
