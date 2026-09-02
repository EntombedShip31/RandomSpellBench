package com.randomspellbench.client.gui;

import com.randomspellbench.Config;
import com.randomspellbench.capability.AssignMode;
import com.randomspellbench.capability.LevelMode;
import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.capability.SpellFilter;
import com.randomspellbench.capability.SpellLevelRange;
import com.randomspellbench.client.ClientConfigData;
import com.randomspellbench.client.ClientEvents;
import com.randomspellbench.client.PonderCompat;
import com.randomspellbench.client.gui.widget.ScrollPanel;
import com.randomspellbench.client.gui.widget.SliderWidget;
import com.randomspellbench.client.gui.widget.SpellListWidget;
import com.randomspellbench.network.NetworkHandler;
import com.randomspellbench.network.packet.C2SRequestRandomizePacket;
import com.randomspellbench.network.packet.C2SRequestSyncPacket;
import com.randomspellbench.network.packet.C2SSpawnScrollPacket;
import com.randomspellbench.network.packet.C2STestActionPacket;
import com.randomspellbench.network.packet.C2SUpdateLevelRangePacket;
import com.randomspellbench.network.packet.C2SUpdateSettingsPacket;
import com.randomspellbench.network.packet.C2SUpdateSpellFilterPacket;
import com.randomspellbench.spell.SpellPoolManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * 随机法术测试台 GUI（全屏单页控制台，无分页）。
 *
 * 主题：整体橙色风格，全屏铺满，空白区域为深色半透明。
 *
 * 功能：
 * - 法术池管理（搜索 / 施法类型过滤 / 全选清除 / 每学派至少 1 个）
 * - 等级规则（范围随机 / 固定等级）
 * - 选中法术的独立等级范围（fixed 模式下滑块置灰并给出提示）
 * - 生成卷轴 / 长按学习（Shift 可批量学习全部已选远古巫术）/ 预览 / 聊天播报 / 复现上次
 * - 长按学习进度条附带百分比提示
 */
public class SpellConfigScreen extends Screen {
    private static final int MARGIN = 10;
    private static final int TITLE_BAR_H = 22;
    private static final int PANEL_GAP = 8;

    // ---------- 橙色主题 ----------
    private static final int COLOR_ACCENT = 0xFFFF8C00;
    private static final int COLOR_ACCENT_DARK = 0xFFB35C00;
    private static final int COLOR_LABEL = 0xFFFFB566;
    private static final int COLOR_TEXT = 0xFFF5E6D0;
    private static final int COLOR_FRAME = 0xFF8A4F12;
    private static final int COLOR_PANEL_BG = 0xE01A1108;

    private static final long LEARN_HOLD_MS = 500L; // 长按学习 0.5 秒（仅远古巫术学派法术）
    /** 长按思索时长：0.5 秒，达到后正式打开 iss_ponder 预览。 */
    private static final long PREVIEW_HOLD_MS = 500L;
    /** 长按思索进度达到该比例时提前预热 iss_ponder 投影场景（不打开界面）。 */
    private static final float PREVIEW_PREWARM_FRACTION = 0.4f;

    /** 操作按钮行高与行距。 */
    private static final int ACTION_ROW_HEIGHT = 14;
    private static final int ACTION_ROW_GAP = 1;

    /**
     * 底部固定区总高度（从上到下）：
     * 状态文字(10) + 间距(4) + 操作标题(10) + 间距(4) + 操作按钮列(74, 最多 5 行) + 间距(4) + 随机分配/关闭(16) + 底距(4) = 126。
     * 同时把「操作」分区从 scroll 区搬到固定区，与状态文字同层，避免与「选中法术」标题视觉上同级。
     */
    private static final int FIXED_BOTTOM_RESERVED = 126;

    // ---------- 布局（init 时按屏幕尺寸计算） ----------
    private int leftPanelX0, leftPanelY0, leftPanelX1, leftPanelY1;
    private int rightPanelX0, rightPanelY0, rightPanelX1, rightPanelY1;
    private int listX, listY, listW, listH;
    private int actionAreaYStart;

    private final PlayerSpellConfig config = ClientConfigData.get();
    /**
     * 客户端 GUI 用法术池：服务端下发的 bannedSpells/schoolWhitelist 二次过滤，
     * 保证 UI 可见 = 服务端 randomize 实际可分配。在 init() 中按 ClientConfigData 重建一次。
     */
    private List<AbstractSpell> pool;
    /** 选中法术：初始为 null，需点击列表中具体法术才会选中。 */
    @Nullable
    private AbstractSpell selectedSpell;

    private EditBox searchBox;
    private Button castCycleButton;
    private SpellListWidget spellList;
    @Nullable
    private CastType castFilter;

    private SliderWidget countSlider;
    private SliderWidget globalMinSlider;
    private SliderWidget globalMaxSlider;
    private SliderWidget fixedLevelSlider;
    private SliderWidget spellMinSlider;
    private SliderWidget spellMaxSlider;
    private Button useGlobalButton;
    private Button minOnePerSchoolButton;
    private Button levelRangeButton;
    private Button levelFixedButton;

    private Button scrollButton;
    private Button learnButton;
    private Button previewButton;
    private Button chatResultButton;
    private Button repeatButton;

    private Button randomizeButton;
    private Button closeButton;
    private Button selectAllButton;
    private Button clearAllButton;

    /** 长按学习的开始时间戳（毫秒）。0 表示未按下。 */
    private long learnPressStartMs = 0;
    /** 学习发送后冷却结束时间（毫秒）。 */
    private long learnCooldownUntilMs = 0;
    /** 当前长按进度 0~1，用于渲染进度条。 */
    private float learnProgress = 0f;

    /** 长按思索的按下时间戳（毫秒）。0 表示未按下。 */
    private long previewPressStartMs = 0;
    /** 长按思索进度 0~1，用于渲染进度条。 */
    private float previewProgress = 0f;
    /** 是否已预热 iss_ponder 投影场景（避免重复预热）。 */
    private boolean previewPrewarmed = false;
    /** 预览触发后冷却结束时间（毫秒）。 */
    private long previewCooldownUntilMs = 0;

    private int lastSyncVersion = -1;
    private int cachedCountVersion = -1;
    private int cachedEnabledCount = 0;

    /** 右控制台滚动面板（内容溢出时启用滚轮 + 滚动条） */
    private ScrollPanel rightScrollPanel;

    public SpellConfigScreen() {
        super(Component.translatable("screen.randomspellbench.title"));
        // 初始不选：玩家需点击列表行才会选中，避免「默认选中第一个」带来的误操作
    }

    // ======================= 初始化 =======================

    @Override
    protected void init() {
        layoutPanels();
        pool = buildClientPool();
        NetworkHandler.sendToServer(new C2SRequestSyncPacket());

        // ---------- 左：法术池管理 ----------
        int leftInnerX = leftPanelX0 + 8;
        int leftInnerW = leftPanelX1 - leftPanelX0 - 16;
        int searchW = Math.max(60, leftInnerW - 60 - 6);
        searchBox = new EditBox(font, leftInnerX, leftPanelY0 + 16, searchW, 14, Component.literal("search"));
        searchBox.setMaxLength(32);
        castCycleButton = Button.builder(Component.literal(castLabel()), b -> cycleCastFilter())
                .bounds(leftInnerX + searchW + 6, leftPanelY0 + 16, 60, 14).build();

        spellList = new SpellListWidget(listX, listY, listW, listH, pool, config,
                new SpellListWidget.Delegate() {
                    @Override
                    public boolean isSelected(AbstractSpell spell) {
                        return selectedSpell == spell;
                    }

                    @Override
                    public void onSelect(AbstractSpell spell) {
                        selectedSpell = spell;
                        refreshDetail();
                    }

                    @Override
                    public void onToggle(AbstractSpell spell) {
                        SpellFilter filter = config.getFilter(spell);
                        filter.setEnabled(!filter.isEnabled());
                        NetworkHandler.sendToServer(new C2SUpdateSpellFilterPacket(spell.getSpellId(), filter));
                        spellList.rebuild();
                    }

                    @Override
                    public void onToggleGroup(List<AbstractSpell> spells) {
                        toggleGroup(spells);
                    }
                });
        searchBox.setResponder(spellList::setSearch);

        // 全选/清除按钮宽度自适应文本：固定 100 宽在窄屏/大字号下会把中文截成残缺字，
        // 宽度按「文本宽度 + 内边距」计算，确保文字完整显示
        Component selectAllMsg = Component.translatable("screen.randomspellbench.select_all");
        Component clearAllMsg = Component.translatable("screen.randomspellbench.clear_all");
        int selectAllW = Math.max(100, font.width(selectAllMsg) + 16);
        int clearAllW = Math.max(100, font.width(clearAllMsg) + 16);
        selectAllButton = Button.builder(selectAllMsg, b -> setAllEnabled(true))
                .bounds(leftInnerX, leftPanelY1 - 18, selectAllW, 14).build();
        clearAllButton = Button.builder(clearAllMsg, b -> setAllEnabled(false))
                .bounds(leftInnerX + selectAllW + 4, leftPanelY1 - 18, clearAllW, 14).build();

        // ---------- 右：测试控制台（ScrollPanel 包装，溢出时启用滚轮） ----------
        int rx = rightPanelX0 + 8;
        int rw = rightPanelX1 - rightPanelX0 - 16;
        int ry = rightPanelY0 + 16;
        // 高度预留底部固定区（状态文字 + 操作标题 + 操作按钮列 + 随机分配/关闭），让这些按钮固定在面板底部不参与滚动
        rightScrollPanel = new ScrollPanel(rx, ry, rw, rightPanelY1 - rightPanelY0 - FIXED_BOTTOM_RESERVED);
        addRenderableWidget(rightScrollPanel);

        countSlider = rightScrollPanel.addChild(new SliderWidget(rx, ry, rw, 14, 1,
                Math.max(1, ClientConfigData.getMaxSpells()), config.getSpellCount(), 1,
                v -> Component.translatable("screen.randomspellbench.spell_count").append(": " + (int) Math.round(v)),
                v -> config.setSpellCount(Math.max(1, (int) Math.round(v))),
                v -> sendSettings()));
        ry += 17;

        minOnePerSchoolButton = rightScrollPanel.addChild(Button.builder(Component.empty(), b -> toggleMinOnePerSchool())
                .bounds(rx, ry, rw, 12).build());
        ry += 15;

        // 等级规则 section 标题（drawFrame 里画）
        ry += 10;

        levelRangeButton = rightScrollPanel.addChild(Button.builder(Component.empty(), b -> setLevelMode(LevelMode.RANGE))
                .bounds(rx, ry, (rw - 4) / 2, 14).build());
        levelFixedButton = rightScrollPanel.addChild(Button.builder(Component.empty(), b -> setLevelMode(LevelMode.FIXED))
                .bounds(rx + (rw - 4) / 2 + 4, ry, (rw - 4) / 2, 14).build());
        ry += 17;

        globalMinSlider = rightScrollPanel.addChild(new SliderWidget(rx, ry, rw, 14, 1, 20,
                config.getGlobalRange().getMinLevel(), 1,
                v -> Component.translatable("screen.randomspellbench.min_level").append(": " + (int) Math.round(v)),
                v -> onGlobalRangeChanged(),
                v -> commitGlobalRange()));
        ry += 17;

        globalMaxSlider = rightScrollPanel.addChild(new SliderWidget(rx, ry, rw, 14, 1, 20,
                config.getGlobalRange().getMaxLevel(), 1,
                v -> Component.translatable("screen.randomspellbench.max_level").append(": " + (int) Math.round(v)),
                v -> onGlobalRangeChanged(),
                v -> commitGlobalRange()));
        ry += 17;

        fixedLevelSlider = rightScrollPanel.addChild(new SliderWidget(rx, ry, rw, 14, 1, 20,
                config.getFixedLevel(), 1,
                v -> Component.translatable("screen.randomspellbench.fixed_level").append(": " + (int) Math.round(v)),
                v -> {
                    config.setFixedLevel(Math.max(1, (int) Math.round(v)));
                    spellList.rebuild();
                },
                v -> sendSettings()));
        ry += 19;

        // 选中法术 section 标题 + 法术名 两行占位
        ry += 10;
        ry += 10;

        useGlobalButton = rightScrollPanel.addChild(Button.builder(Component.literal(""), b -> toggleUseGlobal())
                .bounds(rx, ry, rw, 12).build());
        ry += 15;

        spellMinSlider = rightScrollPanel.addChild(new SliderWidget(rx, ry, rw, 14, 1, 20,
                1, 1,
                v -> Component.translatable("screen.randomspellbench.min_level").append(": " + (int) Math.round(v)),
                v -> onSpellRangeChanged(),
                v -> commitSpellRange()));
        ry += 17;

        spellMaxSlider = rightScrollPanel.addChild(new SliderWidget(rx, ry, rw, 14, 1, 20,
                1, 1,
                v -> Component.translatable("screen.randomspellbench.max_level").append(": " + (int) Math.round(v)),
                v -> onSpellRangeChanged(),
                v -> commitSpellRange()));
        ry += 19;

        // 操作按钮已移至底部固定区（详见下方），不再添加到 ScrollPanel：
        // 这样「操作」标题与按钮都在状态文字下方同一渲染层，不再与「选中法术」标题视觉上同级。

        int bottomY = rightPanelY1 - 20;
        randomizeButton = Button.builder(Component.translatable("screen.randomspellbench.randomize"), b -> randomize())
                .bounds(rx, bottomY, (rw - 4) / 2, 16).build();
        closeButton = Button.builder(Component.translatable("screen.randomspellbench.btn_close"), b -> this.onClose())
                .bounds(rx + (rw - 4) / 2 + 4, bottomY, (rw - 4) / 2, 16).build();
        // 底部按钮固定在面板底部（不加入 ScrollPanel，滚动时保持可见）
        addRenderableWidget(randomizeButton);
        addRenderableWidget(closeButton);

        // 操作按钮：固定在面板底部，位于「操作」标题与「随机分配/关闭」之间，不随滚动。
        // 顶部按钮 Y = scroll panel bottom + 4 + 状态文字(10) + 间距(4) + 操作标题(10) + 间距(4)
        //              = rightPanelY1 - FIXED_BOTTOM_RESERVED + 28
        actionAreaYStart = rightPanelY1 - FIXED_BOTTOM_RESERVED + 28;
        scrollButton = Button.builder(Component.translatable("screen.randomspellbench.btn_scroll"), b -> spawnSelectedScroll())
                .bounds(rx, actionAreaYStart, rw, ACTION_ROW_HEIGHT).build();
        learnButton = Button.builder(Component.translatable("screen.randomspellbench.btn_learn"), b -> {/* 长按逻辑在 tick() */})
                .bounds(rx, actionAreaYStart, rw, ACTION_ROW_HEIGHT).build();
        previewButton = Button.builder(Component.translatable("screen.randomspellbench.btn_preview"), b -> previewSelected())
                .bounds(rx, actionAreaYStart, rw, ACTION_ROW_HEIGHT).build();
        chatResultButton = Button.builder(Component.empty(), b -> toggleChatResult())
                .bounds(rx, actionAreaYStart, rw, ACTION_ROW_HEIGHT).build();
        repeatButton = Button.builder(Component.translatable("screen.randomspellbench.btn_repeat"), b -> repeatLast())
                .bounds(rx, actionAreaYStart, rw, ACTION_ROW_HEIGHT).build();
        addRenderableWidget(scrollButton);
        addRenderableWidget(learnButton);
        addRenderableWidget(previewButton);
        addRenderableWidget(chatResultButton);
        addRenderableWidget(repeatButton);

        addRenderableWidget(searchBox);
        addRenderableWidget(castCycleButton);
        addRenderableWidget(spellList);
        addRenderableWidget(selectAllButton);
        addRenderableWidget(clearAllButton);
        // 右控制台 widgets 由 rightScrollPanel 转发鼠标事件（已 addRenderableWidget），这里不再重复添加

        refreshModeButtons();
        refreshDetail();
        reflowActionButtons();
        rightScrollPanel.layout();
        // 1) 从 iss_ponder 预览返回：恢复思索前保存的界面状态（优先）
        boolean restored = ClientEvents.consumeReturnState(this);
        // 2) 普通打开（按 K/F6）：恢复上次关闭时记住的列表位置（滚动/搜索/选中/折叠）
        if (!restored) {
            applyRememberedState();
        }
    }

    /** 按屏幕尺寸铺满全屏：左=法术池(60%)，右=控制台(余下)。 */
    private void layoutPanels() {
        leftPanelX0 = MARGIN;
        leftPanelY0 = TITLE_BAR_H + MARGIN;
        leftPanelX1 = (int) (this.width * 0.60);
        leftPanelY1 = this.height - MARGIN;

        rightPanelX0 = leftPanelX1 + PANEL_GAP;
        rightPanelY0 = leftPanelY0;
        rightPanelX1 = this.width - MARGIN;
        rightPanelY1 = this.height - MARGIN;

        listX = leftPanelX0 + 8;
        listY = leftPanelY0 + 34;
        listW = leftPanelX1 - leftPanelX0 - 16;
        listH = Math.max(40, (leftPanelY1 - 24) - listY);
    }

    /**
     * 按服务端下发的 bannedSpells / schoolWhitelist 二次过滤。
     * 解决多人下客户端本地 server.toml 与服务端不同导致的 UI 可见 ≠ 服务端可分配的问题。
     */
    private List<AbstractSpell> buildClientPool() {
        List<AbstractSpell> base = SpellPoolManager.getAvailableSpells();
        List<String> banned = ClientConfigData.getServerBannedSpells();
        List<String> whitelist = ClientConfigData.getServerSchoolWhitelist();
        if (banned.isEmpty() && whitelist.isEmpty()) {
            return base;
        }
        List<AbstractSpell> filtered = new ArrayList<>(base.size());
        for (AbstractSpell spell : base) {
            if (banned.contains(spell.getSpellId())) {
                continue;
            }
            if (!whitelist.isEmpty()) {
                ResourceLocation schoolId = spell.getSchoolType() == null
                        ? null : spell.getSchoolType().getId();
                if (schoolId == null || !SpellPoolManager.schoolMatches(whitelist, schoolId)) {
                    continue;
                }
            }
            filtered.add(spell);
        }
        return filtered;
    }

    // ======================= tick =======================

    @Override
    public void tick() {
        super.tick();
        int version = ClientConfigData.getSyncVersion();
        if (version != lastSyncVersion) {
            lastSyncVersion = version;
            applySyncedConfig();
        }
        tickLongPress();
        tickPreviewLongPress();
    }

    /** 每帧处理长按学习的进度 / 触发。 */
    private void tickLongPress() {
        if (selectedSpell == null || !learnButton.active) {
            learnPressStartMs = 0;
            learnProgress = 0f;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (now < learnCooldownUntilMs) {
            return;
        }
        // 直接用 GLFW API 查询鼠标左键状态，避免依赖 Minecraft.mouseHelper 字段
        boolean leftDown = GLFW.glfwGetMouseButton(
                client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (learnButton.isHovered() && leftDown) {
            if (learnPressStartMs == 0) {
                learnPressStartMs = now;
            }
            float p = Math.min(1f, (now - learnPressStartMs) / (float) LEARN_HOLD_MS);
            learnProgress = p;
            if (p >= 1f) {
                NetworkHandler.sendToServer(new C2STestActionPacket(
                        C2STestActionPacket.Action.LEARN_SPELL, selectedSpell.getSpellId()));
                learnProgress = 0f;
                learnPressStartMs = 0;
                learnCooldownUntilMs = now + 3000; // 3 秒冷却，避免反复触发
            }
        } else {
            learnPressStartMs = 0;
            learnProgress = 0f;
        }
    }

    /** 每帧处理长按思索（预览）的进度 / 预热 / 触发。 */
    private void tickPreviewLongPress() {
        if (selectedSpell == null || !previewButton.visible || !previewButton.active) {
            cancelPreviewHold();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (now < previewCooldownUntilMs) {
            return;
        }
        boolean leftDown = GLFW.glfwGetMouseButton(
                client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (previewButton.isHovered() && leftDown) {
            if (previewPressStartMs == 0) {
                previewPressStartMs = now;
                previewProgress = 0f;
            }
            float p = Math.min(1f, (now - previewPressStartMs) / (float) PREVIEW_HOLD_MS);
            previewProgress = p;
            // 思索过半：提前预热 iss_ponder 投影场景（不打开界面），让真正打开时更流畅
            if (!previewPrewarmed && p >= PREVIEW_PREWARM_FRACTION) {
                previewPrewarmed = PonderCompat.prewarm();
            }
            if (p >= 1f) {
                // 长按完成：正式请求预览（iss_ponder 会关闭本界面并进入演示）。
                // 先清标志再请求：iss_ponder 的 request 可能「同步」关闭本界面并触发 removed()，
                // 若此时 previewPrewarmed 仍为 true，removed() 会把刚交给 iss_ponder 的投影场景清掉。
                boolean wasPrewarmed = previewPrewarmed;
                previewPrewarmed = false;
                previewPressStartMs = 0;
                previewProgress = 0f;
                previewCooldownUntilMs = now + 1500; // 1.5 秒冷却，避免误触重复请求
                if (!previewSelected() && wasPrewarmed) {
                    // 请求失败（界面未关闭）：清掉已预热的投影场景，避免残留
                    PonderCompat.clearPrewarm();
                }
            }
        } else {
            cancelPreviewHold();
        }
    }

    /** 长按未达成或中途松开：清掉已预热的投影场景。 */
    private void cancelPreviewHold() {
        if (previewPrewarmed) {
            PonderCompat.clearPrewarm();
        }
        previewPrewarmed = false;
        previewPressStartMs = 0;
        previewProgress = 0f;
    }

    private void applySyncedConfig() {
        // 服务端 banned/whitelist 可能刚下发完，必须重建客户端池（否则列表仍是旧池，P0-2 形同虚设）
        pool = buildClientPool();
        spellList.setPool(pool);
        countSlider.setBounds(1, Math.max(1, ClientConfigData.getMaxSpells()), config.getSpellCount());
        globalMinSlider.setValue(config.getGlobalRange().getMinLevel());
        globalMaxSlider.setValue(config.getGlobalRange().getMaxLevel());
        fixedLevelSlider.setValue(config.getFixedLevel());
        refreshModeButtons();
        refreshDetail();
        spellList.rebuild();
    }

    // ======================= 交互 =======================

    /** Shift + 点击「长按学习」：一次性学习全部已启用的远古巫术。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Shift+长按批量学习全部远古巫术的功能已移除（合规与公平性考量）。
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void setLevelMode(LevelMode levelMode) {
        config.setLevelMode(levelMode);
        sendSettings();
        refreshModeButtons();
        refreshDetail();
        spellList.rebuild();
    }

    private void sendSettings() {
        NetworkHandler.sendToServer(new C2SUpdateSettingsPacket(
                Math.max(1, countSlider.getIntValue()),
                AssignMode.RANDOM,
                config.getLevelMode(),
                fixedLevelSlider.getIntValue(),
                config.isMinOnePerSchool()));
    }

    private void toggleMinOnePerSchool() {
        config.setMinOnePerSchool(!config.isMinOnePerSchool());
        sendSettings();
        refreshDetail();
    }

    private void onGlobalRangeChanged() {
        double min = globalMinSlider.getValue();
        if (min > globalMaxSlider.getValue()) {
            globalMaxSlider.setValue(min);
        }
        config.setGlobalRange(new SpellLevelRange(globalMinSlider.getIntValue(), globalMaxSlider.getIntValue()));
        spellList.rebuild();
    }

    private void commitGlobalRange() {
        NetworkHandler.sendToServer(new C2SUpdateLevelRangePacket(null, config.getGlobalRange(), false));
    }

    private void onSpellRangeChanged() {
        if (selectedSpell == null) {
            return;
        }
        double min = spellMinSlider.getValue();
        if (min > spellMaxSlider.getValue()) {
            spellMaxSlider.setValue(min);
        }
        SpellFilter filter = config.getFilter(selectedSpell);
        filter.setUseGlobalRange(false);
        filter.setMinLevel(spellMinSlider.getIntValue());
        filter.setMaxLevel(spellMaxSlider.getIntValue());
        spellList.rebuild();
    }

    private void commitSpellRange() {
        if (selectedSpell == null) {
            return;
        }
        SpellFilter filter = config.getFilter(selectedSpell);
        NetworkHandler.sendToServer(new C2SUpdateLevelRangePacket(selectedSpell.getSpellId(),
                new SpellLevelRange(filter.getMinLevel(), filter.getMaxLevel()), false));
    }

    private void toggleUseGlobal() {
        if (selectedSpell == null) {
            return;
        }
        SpellFilter filter = config.getFilter(selectedSpell);
        filter.setUseGlobalRange(!filter.isUseGlobalRange());
        NetworkHandler.sendToServer(new C2SUpdateLevelRangePacket(selectedSpell.getSpellId(),
                new SpellLevelRange(filter.getMinLevel(), filter.getMaxLevel()), filter.isUseGlobalRange()));
        refreshDetail();
        spellList.rebuild();
    }

    private void setAllEnabled(boolean enabled) {
        for (AbstractSpell spell : spellList.getVisible()) {
            SpellFilter filter = config.getFilter(spell);
            if (filter.isEnabled() != enabled) {
                filter.setEnabled(enabled);
                NetworkHandler.sendToServer(new C2SUpdateSpellFilterPacket(spell.getSpellId(), filter));
            }
        }
        spellList.rebuild();
    }

    private void toggleGroup(List<AbstractSpell> spells) {
        if (spells.isEmpty()) {
            return;
        }
        boolean allEnabled = config.countEnabled(spells) == spells.size();
        boolean target = !allEnabled;
        for (AbstractSpell spell : spells) {
            SpellFilter filter = config.getFilter(spell);
            if (filter.isEnabled() == target) {
                continue;
            }
            filter.setEnabled(target);
            NetworkHandler.sendToServer(new C2SUpdateSpellFilterPacket(spell.getSpellId(), filter));
        }
        spellList.rebuild();
    }

    private void cycleCastFilter() {
        castFilter = switch (castFilter == null ? "" : castFilter.name()) {
            case "" -> CastType.INSTANT;
            case "INSTANT" -> CastType.CONTINUOUS;
            case "CONTINUOUS" -> CastType.LONG;
            default -> null;
        };
        castCycleButton.setMessage(Component.literal(castLabel()));
        spellList.setCastFilter(castFilter);
    }

    private String castLabel() {
        if (castFilter == null) {
            return Component.translatable("screen.randomspellbench.cast_all").getString();
        }
        return switch (castFilter) {
            case INSTANT -> Component.translatable("screen.randomspellbench.cast_instant").getString();
            case CONTINUOUS -> Component.translatable("screen.randomspellbench.cast_continuous").getString();
            case LONG -> Component.translatable("screen.randomspellbench.cast_long").getString();
            default -> Component.translatable("screen.randomspellbench.cast_all").getString();
        };
    }

    private void randomize() {
        NetworkHandler.sendToServer(new C2SRequestRandomizePacket());
        closeIfConfigured();
    }

    private void repeatLast() {
        NetworkHandler.sendToServer(new C2STestActionPacket(C2STestActionPacket.Action.REPEAT_LAST, ""));
        closeIfConfigured();
    }

    private void spawnSelectedScroll() {
        if (selectedSpell == null) {
            return;
        }
        NetworkHandler.sendToServer(new C2SSpawnScrollPacket(selectedSpell.getSpellId(), 0));
        closeIfConfigured();
    }

    /** 长按思索完成：请求 iss_ponder 打开选中法术的演示界面；失败给兜底提示并返回 false。 */
    private boolean previewSelected() {
        if (selectedSpell == null) {
            return false;
        }
        int level = config.effectiveRange(selectedSpell).getMinLevel();
        // 保存界面状态（关闭预览后恢复）
        ClientEvents.markReturn(this);
        boolean ok = PonderCompat.requestPreview(selectedSpell, level);
        if (ok) {
            // request 内部已把当前界面 setScreen(null)；立刻打开深色加载遮罩挡住网络往返窗口，
            // 后续 iss_ponder 接管/预览关闭/超时由 ClientEvents 状态机统一处理
            ClientEvents.beginPreviewWait();
        } else {
            ClientEvents.clearReturn();
            mc().player.displayClientMessage(
                    Component.translatable("screen.randomspellbench.preview_failed"), true);
        }
        return ok;
    }

    /** 是否为远古巫术（Eldritch）学派的法术（该学派法术在创造模式下也需要特殊学习）。 */
    private static boolean isEldritch(AbstractSpell spell) {
        try {
            if (spell == null || spell.getSchoolType() == null) {
                return false;
            }
            return io.redspace.ironsspellbooks.api.registry.SchoolRegistry.ELDRITCH_RESOURCE
                    .equals(spell.getSchoolType().getId());
        } catch (Throwable t) {
            return false;
        }
    }

    private void toggleChatResult() {
        config.setShowResultInChat(!config.isShowResultInChat());
        NetworkHandler.sendToServer(new C2STestActionPacket(
                C2STestActionPacket.Action.TOGGLE_CHAT, ""));
        refreshDetail();
    }

    private void closeIfConfigured() {
        if (Config.CLIENT.closeScreenAfterRandomize.get()) {
            this.onClose();
        }
    }

    // ======================= 状态刷新 =======================

    private void refreshModeButtons() {
        // 分配模式固定 RANDOM，只刷新等级模式
        LevelMode levelMode = config.getLevelMode();
        levelRangeButton.setMessage(modeLabel("screen.randomspellbench.level_range", levelMode == LevelMode.RANGE));
        levelFixedButton.setMessage(modeLabel("screen.randomspellbench.level_fixed", levelMode == LevelMode.FIXED));
    }

    private static MutableComponent modeLabel(String key, boolean active) {
        return Component.translatable(key).withStyle(active ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private void refreshDetail() {
        boolean hasSelection = selectedSpell != null;
        boolean fixed = config.getLevelMode() == LevelMode.FIXED;
        // spell 区段（选中法术专属的等级/全局开关）只在「有选中法术 且 不是固定等级」时显示，
        // 否则即便置灰也会占用 layout 把下方按钮挤出 panel——这是 UI 错位的根因。
        boolean showSpellSection = hasSelection && !fixed;

        globalMinSlider.visible = !fixed;
        globalMaxSlider.visible = !fixed;
        fixedLevelSlider.visible = fixed;

        countSlider.active = true; // 没有 ALL 模式了，数量永远可调

        useGlobalButton.visible = showSpellSection;
        spellMinSlider.visible = showSpellSection;
        spellMaxSlider.visible = showSpellSection;
        useGlobalButton.active = showSpellSection;
        spellMinSlider.active = showSpellSection;
        spellMaxSlider.active = showSpellSection;

        scrollButton.active = hasSelection;
        previewButton.active = hasSelection;
        learnButton.active = hasSelection && isEldritch(selectedSpell);
        repeatButton.active = !ClientConfigData.getLastResult().isEmpty();

        // 操作按钮已固定在面板底部（init() 中已按 FIXED_BOTTOM_RESERVED 算出 actionAreaYStart），
        // spell 区段显隐不再影响其 Y 位置；reflow 只根据当前可见的按钮（远古/ponder）紧凑排列。
        reflowActionButtons();

        minOnePerSchoolButton.setMessage(Component.literal(
                config.isMinOnePerSchool() ? "[x] " : "[ ] ")
                .append(Component.translatable("screen.randomspellbench.min_one_per_school")));

        chatResultButton.setMessage(Component.translatable("screen.randomspellbench.chat_result",
                Component.translatable(config.isShowResultInChat()
                        ? "screen.randomspellbench.on" : "screen.randomspellbench.off")));

        if (hasSelection) {
            SpellFilter filter = config.getFilter(selectedSpell);
            SpellLevelRange effective = config.effectiveRange(selectedSpell);
            int maxLevel = Math.max(1, selectedSpell.getMaxLevel());
            spellMinSlider.setBounds(1, maxLevel, effective.getMinLevel());
            spellMaxSlider.setBounds(1, maxLevel, effective.getMaxLevel());
            useGlobalButton.setMessage(Component.literal(filter.isUseGlobalRange() ? "[x] " : "[ ] ")
                    .append(Component.translatable("screen.randomspellbench.use_global")));
        } else {
            useGlobalButton.setMessage(Component.literal("[ ] ")
                    .append(Component.translatable("screen.randomspellbench.use_global")));
        }

        // 长按学习按钮（仅远古巫术显示），提示按住时长；Shift 可批量
        learnButton.setMessage(Component.translatable("screen.randomspellbench.btn_learn")
                .append(" (" + (LEARN_HOLD_MS / 1000) + "." + (LEARN_HOLD_MS % 1000 / 100) + "s)")
                .append(Component.literal(" ").append(Component.translatable("screen.randomspellbench.btn_learn_shift"))));

        // 文案只显示「点击思索」，不再拼接时长后缀（长按行为本身保留）
        previewButton.setMessage(Component.translatable("screen.randomspellbench.btn_preview"));
    }

    /**
     * 操作区按钮按可见性自动补位：不显示的按钮跳过，下方按钮紧贴上来。
     */
    private void reflowActionButtons() {
        int y = actionAreaYStart;
        scrollButton.setY(y);
        scrollButton.visible = true;
        y += ACTION_ROW_HEIGHT + ACTION_ROW_GAP;

        boolean eldritch = selectedSpell != null && isEldritch(selectedSpell);
        if (eldritch) {
            learnButton.setY(y);
            learnButton.visible = true;
            y += ACTION_ROW_HEIGHT + ACTION_ROW_GAP;
        } else {
            learnButton.visible = false;
        }

        boolean ponderLoaded = PonderCompat.isPonderLoaded();
        if (ponderLoaded) {
            previewButton.setY(y);
            previewButton.visible = true;
            y += ACTION_ROW_HEIGHT + ACTION_ROW_GAP;
        } else {
            previewButton.visible = false;
        }

        chatResultButton.setY(y);
        chatResultButton.visible = true;
        y += ACTION_ROW_HEIGHT + ACTION_ROW_GAP;

        repeatButton.setY(y);
        repeatButton.visible = true;
    }

    // ======================= 渲染 =======================

    /** 全屏深色半透明背景（空白处）。 */
    @Override
    public void renderBackground(GuiGraphics g) {
        g.fillGradient(0, 0, this.width, this.height, 0xC00B0703, 0xD8160D04);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        drawFrame(g);
        super.render(g, mouseX, mouseY, partialTick);

        // 右面板跟滚动的覆盖层（分区标题 / 选中法术 / 长按进度）：
        // 跟随 ScrollPanel 的滚动偏移，并裁剪在右面板内，避免溢出污染相邻区域
        int so = rightScrollPanel != null ? rightScrollPanel.getScrollOffset() : 0;
        g.enableScissor(rightPanelX0, rightPanelY0, rightPanelX1, rightPanelY1);
        g.pose().pushPose();
        g.pose().translate(0, -so, 0);
        try {
            drawSectionLabels(g);
            drawLabels(g);
            drawLongPressProgress(g);
            drawPreviewProgress(g);
        } finally {
            g.pose().popPose();
            g.disableScissor();
        }

        // 状态文字 + 操作标题 固定在面板底部（随机分配/关闭按钮上方），不随滚动
        drawActionHeader(g);
        drawStatus(g);
        drawHoveredTooltip(g, mouseX, mouseY);
    }

    private void drawFrame(GuiGraphics g) {
        // 顶部标题栏
        g.fill(0, 0, width, TITLE_BAR_H, 0xFF1A1108);
        g.fill(0, TITLE_BAR_H - 1, width, TITLE_BAR_H, COLOR_ACCENT);
        g.drawCenteredString(font,
                Component.translatable("screen.randomspellbench.title").getString(),
                width / 2, 6, 0xFFFFD699);

        fillPanel(g, leftPanelX0, leftPanelY0, leftPanelX1, leftPanelY1);
        fillPanel(g, rightPanelX0, rightPanelY0, rightPanelX1, rightPanelY1);
        g.drawString(font, Component.translatable("screen.randomspellbench.left_title").getString(),
                leftPanelX0 + 8, leftPanelY0 + 4, COLOR_ACCENT, false);
        g.drawString(font, Component.translatable("screen.randomspellbench.right_title").getString(),
                rightPanelX0 + 8, rightPanelY0 + 4, COLOR_ACCENT, false);
        if (searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            g.drawString(font, Component.translatable("screen.randomspellbench.search").getString(),
                    searchBox.getX() + 4, searchBox.getY() + 3, 0xFF80705A, false);
        }
    }

    /** 右面板分区标题（跟随 rightScrollPanel 滚动）。由 render() 在内容板块内调用。 */
    private void drawSectionLabels(GuiGraphics g) {
        int rx = rightPanelX0 + 8;
        drawSectionLabel(g, "screen.randomspellbench.section_level", rx, globalMinSlider.getY() - 27);
        // 「选中法术」区段标题只在区段实际显示时绘制；区段隐藏（未选法术 / 固定等级）时若照旧绘制，
        // 会与下方被拉上来的操作按钮重叠——这正是「直接进入界面错位、点固定法术后消失」的根因。
        if (useGlobalButton.visible) {
            drawSectionLabel(g, "screen.randomspellbench.section_spell", rx, useGlobalButton.getY() - 20);
        }
        if (config.getLevelMode() == LevelMode.FIXED && selectedSpell != null) {
            // 固定等级提示：说明为什么下方滑块置灰（画在分区标题右侧，不额外占行）
            g.drawString(font, Component.translatable("screen.randomspellbench.fixed_hint",
                    config.getFixedLevel()).getString(),
                    rx + 66, useGlobalButton.getY() - 20, 0xFFB08A55, false);
        }
        // 「操作」分区标题已移至固定区（drawActionHeader），不再在 scroll 区绘制，
        // 避免与「选中法术」标题视觉上同级。
    }

    private void drawLabels(GuiGraphics g) {
        // spell 区段隐藏时（未选法术或固定等级）不画「选中: ...」行，避免占用/重叠
        if (!useGlobalButton.visible) {
            return;
        }
        int rx = rightPanelX0 + 8;

        // 分两段绘制：先画「选中: 」前缀（灰色），再画法术名（学派色）
        String prefix = Component.translatable("screen.randomspellbench.selected_spell").getString() + ": ";
        int nameY = useGlobalButton.getY() - 10;
        g.drawString(font, prefix, rx, nameY, COLOR_TEXT, false);
        int prefixWidth = font.width(prefix);
        int rw = rightPanelX1 - rightPanelX0 - 16;

        String selectedText;
        int nameColor = COLOR_TEXT;
        if (selectedSpell == null) {
            selectedText = Component.translatable("screen.randomspellbench.no_selection").getString();
        } else {
            selectedText = selectedSpell.getDisplayName(mc().player).getString();
            TextColor tc = selectedSpell.getSchoolType().getDisplayName().getStyle().getColor();
            if (tc != null) {
                nameColor = tc.getValue();
            }
        }
        int available = Math.max(0, rw - prefixWidth - 4);
        g.drawString(font, font.plainSubstrByWidth(selectedText, available),
                rx + prefixWidth, nameY, nameColor, false);
    }

    private void drawLongPressProgress(GuiGraphics g) {
        if (learnProgress <= 0f || selectedSpell == null) {
            return;
        }
        int x = learnButton.getX();
        int y = learnButton.getY();
        int w = (int) (learnButton.getWidth() * learnProgress);
        g.fill(x, y + learnButton.getHeight() - 2, x + w, y + learnButton.getHeight(), 0xFFFF8C00);
        // 百分比提示
        int pct = (int) (learnProgress * 100);
        g.drawString(font, pct + "%", x + learnButton.getWidth() + 4, y + 2, 0xFFFFB566, false);
    }

    private void drawPreviewProgress(GuiGraphics g) {
        if (previewProgress <= 0f || selectedSpell == null || !previewButton.visible) {
            return;
        }
        int x = previewButton.getX();
        int y = previewButton.getY();
        int w = (int) (previewButton.getWidth() * previewProgress);
        g.fill(x, y + previewButton.getHeight() - 2, x + w, y + previewButton.getHeight(), 0xFFFF8C00);
        int pct = (int) (previewProgress * 100);
        g.drawString(font, pct + "%", x + previewButton.getWidth() + 4, y + 2, 0xFFFFB566, false);
    }

    private void drawSectionLabel(GuiGraphics g, String key, int x, int y) {
        g.fill(x, y + 1, x + 2, y + 8, COLOR_ACCENT_DARK);
        g.drawString(font, Component.translatable(key).getString(), x + 6, y, COLOR_LABEL, false);
    }

    private void drawStatus(GuiGraphics g) {
        int version = spellList.getRebuildCount();
        if (version != cachedCountVersion) {
            cachedCountVersion = version;
            cachedEnabledCount = config.enabledSpellCount(pool);
        }
        int selectedCount = cachedEnabledCount;
        // 统计移到「清除全部」右侧的空位，避免与左下角按钮重叠
        String countText = Component.translatable(
                "screen.randomspellbench.selected_count", selectedCount, pool.size()).getString();
        int countX = clearAllButton.getX() + clearAllButton.getWidth() + 6;
        int countY = clearAllButton.getY() + 3;
        int countW = Math.max(0, (leftPanelX1 - 8) - countX);
        g.drawString(font, font.plainSubstrByWidth(countText, countW), countX, countY, 0xFFC0B08A, false);

        String statusKey = ClientConfigData.isAssigned()
                ? "screen.randomspellbench.status_assigned"
                : "screen.randomspellbench.status_pending";
        String status = Component.translatable("screen.randomspellbench.assigned_status",
                Component.translatable(statusKey).getString()).getString();
        int last = ClientConfigData.getLastResult().size();
        if (last > 0) {
            status += Component.translatable("screen.randomspellbench.last_result", last).getString();
        }
        int rx = rightPanelX0 + 8;
        int rw = rightPanelX1 - rightPanelX0 - 16;
        // 状态文字固定在固定区顶部（操作标题上方），不随滚动
        int statusY = rightPanelY1 - FIXED_BOTTOM_RESERVED;
        g.drawString(font, font.plainSubstrByWidth(status, rw), rx, statusY, 0xFFC0B08A, false);
    }

    /**
     * 右面板「操作」分区标题：固定在底部固定区，状态文字下方、操作按钮列上方（不随滚动）。
     * 与 drawSectionLabels() 中绘制的「等级规则」「选中法术」分区标题不在同一渲染层，
     * 避免视觉上同级。
     */
    private void drawActionHeader(GuiGraphics g) {
        int rx = rightPanelX0 + 8;
        // 操作标题 Y = 状态文字底部 + 4px 间距
        int y = rightPanelY1 - FIXED_BOTTOM_RESERVED + 10 + 4;
        drawSectionLabel(g, "screen.randomspellbench.section_action", rx, y);
    }

    private void drawHoveredTooltip(GuiGraphics g, int screenX, int screenY) {
        AbstractSpell hovered = spellList.getHovered(screenX, screenY);
        if (hovered == null) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        int color = 0xFFFFFF;
        TextColor tc = hovered.getSchoolType().getDisplayName().getStyle().getColor();
        if (tc != null) {
            color = tc.getValue();
        }
        lines.add(hovered.getDisplayName(mc().player).copy().withStyle(Style.EMPTY.withColor(color)));
        lines.add(hovered.getSchoolType().getDisplayName());

        int previewLevel = config.effectiveRange(hovered).getMinLevel();
        lines.add(Component.translatable("screen.randomspellbench.tip_max_level", hovered.getMaxLevel()));
        lines.add(Component.translatable("screen.randomspellbench.tip_mana",
                hovered.getManaCost(previewLevel), previewLevel));
        lines.add(Component.translatable("screen.randomspellbench.tip_cooldown", cooldownText(hovered)));
        lines.add(Component.translatable("screen.randomspellbench.tip_cast", castTypeText(hovered)));
        lines.add(Component.translatable("screen.randomspellbench.tip_cast_time", castTimeText(hovered, previewLevel)));
        lines.add(Component.translatable("screen.randomspellbench.tip_rarity", rarityText(hovered, previewLevel)));

        try {
            List<MutableComponent> unique = hovered.getUniqueInfo(previewLevel, mc().player);
            if (unique != null) {
                for (int i = 0; i < Math.min(6, unique.size()); i++) {
                    lines.add(unique.get(i));
                }
            }
        } catch (Exception ignored) {
        }

        g.renderComponentTooltip(font, lines, screenX, screenY);
    }

    private static String cooldownText(AbstractSpell spell) {
        int raw = spell.getSpellCooldown();
        if (raw <= 0) return "-";
        return raw > 120 ? String.format("%.1f", raw / 20.0f) : String.valueOf(raw);
    }

    private static String castTimeText(AbstractSpell spell, int level) {
        int ct = spell.getCastTime(level);
        if (ct <= 0) return "-";
        return String.format("%.1f", ct / 20.0f);
    }

    private static String rarityText(AbstractSpell spell, int level) {
        try { return spell.getRarity(level).getDisplayName().getString(); }
        catch (Exception e) { return "-"; }
    }

    private String castTypeText(AbstractSpell spell) {
        CastType type = spell.getCastType();
        if (type == null) return "-";
        return switch (type) {
            case INSTANT -> Component.translatable("screen.randomspellbench.cast_instant").getString();
            case CONTINUOUS -> Component.translatable("screen.randomspellbench.cast_continuous").getString();
            case LONG -> Component.translatable("screen.randomspellbench.cast_long").getString();
            default -> type.name();
        };
    }

    private static void fillPanel(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, COLOR_PANEL_BG);
        drawBorder(g, x0, y0, x1, y1, COLOR_FRAME);
    }

    private static void drawBorder(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    /**
     * 界面被移除时（按 ESC 关闭 / 被 iss_ponder 接管）清理长按思索的预热状态。
     * 玩家在长按途中关闭界面后 tick 不再运行，已 prewarm 的投影场景会残留，这里统一清掉；
     * 预览已成功交给 iss_ponder 时 previewPrewarmed 已为 false，不会误清正在使用的场景。
     */
    @Override
    public void removed() {
        rememberCurrentState();
        cancelPreviewHold();
        super.removed();
    }

    @Override
    public void onClose() {
        // 主动关闭：清除「关闭后回到本界面」标记，避免 ESC 后被错误恢复
        ClientEvents.clearReturn();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return Config.CLIENT.pauseScreen.get();
    }

    // ---------------- 状态访问（供 ClientEvents 保存/恢复） ----------------

    @Nullable
    public String getSelectedSpellId() {
        return selectedSpell == null ? null : selectedSpell.getSpellId();
    }

    public String getSearchText() {
        return searchBox == null ? "" : searchBox.getValue();
    }

    public int getListScroll() {
        return spellList.getScroll();
    }

    public Set<ResourceLocation> getCollapsedGroups() {
        return spellList.getCollapsed();
    }

    /**
     * 从 iss_ponder 预览关闭后恢复界面状态（由 ClientEvents.consumeReturnState 调用）。
     */
    public void applyReturnState(@Nullable String spellId, @Nullable String search,
                                 int scroll, @Nullable Set<ResourceLocation> collapsed) {
        if (search != null && !search.isEmpty() && searchBox != null) {
            searchBox.setValue(search);
            spellList.setSearch(search);
        }
        if (collapsed != null) {
            spellList.setCollapsed(collapsed);
        }
        if (spellId != null && pool != null) {
            for (AbstractSpell sp : pool) {
                if (spellId.equals(sp.getSpellId())) {
                    selectedSpell = sp;
                    spellList.scrollToSpell(sp);
                    break;
                }
            }
        }
        if (scroll > 0) {
            spellList.setScroll(scroll);
        }
        spellList.rebuild();
        refreshDetail();
        if (rightScrollPanel != null) {
            rightScrollPanel.layout();
        }
    }

    // ---------------- 记住上次打开时的界面位置（跨多次打开，重启游戏前有效） ----------------

    @Nullable
    private static String rememberedSpellId;
    @Nullable
    private static String rememberedSearch;
    private static int rememberedScroll;
    @Nullable
    private static Set<ResourceLocation> rememberedCollapsed;

    /** removed() 时保存当前列表位置，供下次打开恢复。 */
    private void rememberCurrentState() {
        if (spellList == null) {
            return;
        }
        rememberedSpellId = selectedSpell == null ? null : selectedSpell.getSpellId();
        rememberedSearch = getSearchText();
        rememberedScroll = getListScroll();
        rememberedCollapsed = getCollapsedGroups();
    }

    /** 普通打开（非思索返回）时恢复到上次记住的位置；无记录则保持初始。 */
    private void applyRememberedState() {
        if (rememberedScroll <= 0
                && (rememberedSearch == null || rememberedSearch.isEmpty())
                && rememberedSpellId == null
                && (rememberedCollapsed == null || rememberedCollapsed.isEmpty())) {
            return; // 没有历史记录（首次打开）
        }
        applyReturnState(rememberedSpellId, rememberedSearch, rememberedScroll, rememberedCollapsed);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
   