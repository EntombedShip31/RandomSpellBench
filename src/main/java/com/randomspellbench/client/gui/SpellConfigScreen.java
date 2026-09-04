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
import com.randomspellbench.network.packet.C2SImbueSpellPacket;
import com.randomspellbench.network.packet.C2SRequestSyncPacket;
import com.randomspellbench.network.packet.C2SSpawnScrollPacket;
import com.randomspellbench.network.packet.C2STestActionPacket;
import com.randomspellbench.network.packet.C2SUpdateLevelRangePacket;
import com.randomspellbench.network.packet.C2SUpdateSettingsPacket;
import com.randomspellbench.network.packet.C2SUpdateSpellFilterPacket;
import com.randomspellbench.spell.ImbueTarget;
import com.randomspellbench.spell.SpellPoolManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
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
 * - 生成卷轴 / 长按学习 / 预览 / 复现上次
 * - 注入 / 拆卷轴：7 个槽位按钮一键直达（主 / 副 / 头 / 胸 / 腿 / 脚 / 书），
 *   把选中法术写进该槽位物品（书 = 写入饰品栏法术书，书满了会拒绝），
 *   或把该槽位物品上的法术拆成卷轴放进背包
 * - 分配结果在 actionbar 固定播报（v1.0.3 起移除聊天播报开关）
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

    /** 段间距：scroll 内容两段之间的纵向留白。 */
    private static final int SECTION_GAP = 10;
    /** 段标题占位高度（与 drawSectionLabel 配合）。 */
    private static final int SECTION_TITLE_H = 10;
    /** 操作按钮单行高度（与列表里其他滑块/按钮一致）。 */
    private static final int ROW_H = 14;
    /** 行间距。 */
    private static final int ROW_GAP = 3;
    /** 底部固定按钮区高度：随机分配 + 关闭 + 底距。 */
    private static final int BOTTOM_BAR_H = 20;
    /** 面板顶部标题区高度：留出 rightTitle 字 + 上沿空白，scroll 从其下方开始。 */
    private static final int TOP_BAR_H = 16;

    // ---------- 布局（init 时按屏幕尺寸计算） ----------
    private int leftPanelX0, leftPanelY0, leftPanelX1, leftPanelY1;
    private int rightPanelX0, rightPanelY0, rightPanelX1, rightPanelY1;
    private int listX, listY, listW, listH;

    /** 右面板 scroll 内容起始 Y：流式重排（reflowRightPanel）的原点。 */
    private int scrollY0;
    /**
     * 各段标题行的 Y，由 reflowRightPanel() 计算并写入。
     * 渲染时直接读这些字段，不再用「某控件.getY() - 偏移」反推——
     * 重排后控件 Y 会变，反推会让标题与内容错位。
     */
    private int sectionLevelY;
    private int sectionSpellY;
    private int sectionActionY;
    /** 「选中: 法术名」行的 Y。 */
    private int selectedNameY;
    /**
     * 固定等级模式下「选中法术」整段隐藏时，替代提示行（fixed_hint）的 Y。
     * 同样由 reflowRightPanel() 分配——此前该提示直接沿用 sectionSpellY，
     * 而固定等级下 sectionSpellY 不会被赋值（保留上一次重排的旧值），
     * 提示就被画到了旧位置，压在下方「操作」段的按钮上，即「右侧字体错位」。
     */
    private int fixedHintY;

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
    private Button repeatButton;

    /**
     * 注入 / 拆卷轴的目标槽位。纯客户端本地状态：服务端不保存该选择，
     * 重开界面回到默认的主手（与「记住上次选中法术」不同，槽位选择没有记忆价值）。
     */
    private ImbueTarget imbueTarget = ImbueTarget.MAINHAND;
    /**
     * 7 个槽位按钮并排（主 / 副 / 头 / 胸 / 腿 / 脚 / 书），一键直达目标槽位。
     * 早期版本是「单按钮循环切换」，选到最后一个部位要连点好几下；改成并排后一次点击即可。
     */
    private final Map<ImbueTarget, Button> imbueTargetButtons = new EnumMap<>(ImbueTarget.class);
    private Button imbueButton;
    /** 「拆下卷轴」：把当前选中槽位物品上的法术拆成卷轴（与「注入法术」并排，取代原「清除」）。 */
    private Button extractTargetButton;

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
                        // 先比引用（热路径，每帧对可见行调用），再按 id 兜底：
                        // 池重建 / 同步后即使拿到不同的包装实例，行高亮也不会与实际选中项错位
                        return selectedSpell == spell
                                || (selectedSpell != null && spell != null
                                && selectedSpell.getSpellId().equals(spell.getSpellId()));
                    }

                    @Override
                    public void onSelect(AbstractSpell spell) {
                        selectSpell(spell);
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
        // 整个右面板分三层：顶 [测试控制台 标题] / 中 [scroll: 全部功能] / 底 [随机分配 + 关闭]
        // scroll 区域大小自动适配屏宽/屏高——只固定底部按钮区高度即可
        int rx = rightPanelX0 + 8;
        int rw = rightPanelX1 - rightPanelX0 - 16;
        int panelInnerH = rightPanelY1 - rightPanelY0;
        scrollY0 = rightPanelY0 + TOP_BAR_H;
        int scrollH = Math.max(40, panelInnerH - TOP_BAR_H - BOTTOM_BAR_H);
        rightScrollPanel = new ScrollPanel(rx, scrollY0, rw, scrollH);
        addRenderableWidget(rightScrollPanel);

        // 所有控件先占位放在 scrollY0（Y 由 reflowRightPanel() 按可见性流式重排，
        // 隐藏控件不占位，下方控件自动上移补位，避免空缺处出现大片空白）
        countSlider = rightScrollPanel.addChild(new SliderWidget(rx, scrollY0, rw, ROW_H, 1,
                Math.max(1, ClientConfigData.getMaxSpells()), config.getSpellCount(), 1,
                v -> Component.translatable("screen.randomspellbench.spell_count").append(": " + (int) Math.round(v)),
                v -> config.setSpellCount(Math.max(1, (int) Math.round(v))),
                v -> sendSettings()));

        minOnePerSchoolButton = rightScrollPanel.addChild(Button.builder(Component.empty(), b -> toggleMinOnePerSchool())
                .bounds(rx, scrollY0, rw, 12).build());

        levelRangeButton = rightScrollPanel.addChild(Button.builder(Component.empty(), b -> setLevelMode(LevelMode.RANGE))
                .bounds(rx, scrollY0, (rw - 4) / 2, ROW_H).build());
        levelFixedButton = rightScrollPanel.addChild(Button.builder(Component.empty(), b -> setLevelMode(LevelMode.FIXED))
                .bounds(rx + (rw - 4) / 2 + 4, scrollY0, (rw - 4) / 2, ROW_H).build());

        globalMinSlider = rightScrollPanel.addChild(new SliderWidget(rx, scrollY0, rw, ROW_H, 1, 20,
                config.getGlobalRange().getMinLevel(), 1,
                v -> Component.translatable("screen.randomspellbench.min_level").append(": " + (int) Math.round(v)),
                v -> onGlobalRangeChanged(),
                v -> commitGlobalRange()));

        globalMaxSlider = rightScrollPanel.addChild(new SliderWidget(rx, scrollY0, rw, ROW_H, 1, 20,
                config.getGlobalRange().getMaxLevel(), 1,
                v -> Component.translatable("screen.randomspellbench.max_level").append(": " + (int) Math.round(v)),
                v -> onGlobalRangeChanged(),
                v -> commitGlobalRange()));

        fixedLevelSlider = rightScrollPanel.addChild(new SliderWidget(rx, scrollY0, rw, ROW_H, 1, 20,
                config.getFixedLevel(), 1,
                v -> Component.translatable("screen.randomspellbench.fixed_level").append(": " + (int) Math.round(v)),
                v -> {
                    config.setFixedLevel(Math.max(1, (int) Math.round(v)));
                    spellList.rebuild();
                },
                v -> sendSettings()));

        useGlobalButton = rightScrollPanel.addChild(Button.builder(Component.literal(""), b -> toggleUseGlobal())
                .bounds(rx, scrollY0, rw, 12).build());

        spellMinSlider = rightScrollPanel.addChild(new SliderWidget(rx, scrollY0, rw, ROW_H, 1, 20,
                1, 1,
                v -> Component.translatable("screen.randomspellbench.min_level").append(": " + (int) Math.round(v)),
                v -> onSpellRangeChanged(),
                v -> commitSpellRange()));

        spellMaxSlider = rightScrollPanel.addChild(new SliderWidget(rx, scrollY0, rw, ROW_H, 1, 20,
                1, 1,
                v -> Component.translatable("screen.randomspellbench.max_level").append(": " + (int) Math.round(v)),
                v -> onSpellRangeChanged(),
                v -> commitSpellRange()));

        // 注入区：两行搞定（早期版本是「循环按钮 + 注入 + 清除」三行，且选部位要连点）。
        // 刻意排在「生成卷轴」之前——注入与选中法术强相关，和卷轴是同一类操作。
        //   行 1：[主][副][头][胸][腿][脚][书] —— 并排小按钮，一次点击直达目标槽位
        //   行 2：[注入法术] [拆下卷轴] —— 横向平行；拆下卷轴取代原「清除」，按文本宽度自适应
        ImbueTarget[] slots = ImbueTarget.values();
        int slotGap = 3;
        int slotW = Math.max(16, (rw - slotGap * (slots.length - 1)) / slots.length);
        // 整除余下的几像素给最后一个按钮，让这一行右边缘与下方「注入 / 拆下卷轴」行对齐
        int lastSlotW = Math.max(16, rw - (slots.length - 1) * (slotW + slotGap));
        for (int i = 0; i < slots.length; i++) {
            ImbueTarget slot = slots[i];
            int w = (i == slots.length - 1) ? lastSlotW : slotW;
            imbueTargetButtons.put(slot, rightScrollPanel.addChild(
                    Button.builder(Component.empty(), b -> selectImbueTarget(slot))
                            .bounds(rx + i * (slotW + slotGap), scrollY0, w, ROW_H).build()));
        }

        Component extractMsg = Component.translatable("screen.randomspellbench.btn_extract_target");
        int extractW = Math.max(72, font.width(extractMsg) + 14);
        int imbueW = Math.max(64, rw - extractW - 4);
        imbueButton = rightScrollPanel.addChild(Button.builder(Component.translatable("screen.randomspellbench.btn_imbue"), b -> imbueSelected())
                .bounds(rx, scrollY0, imbueW, ROW_H).build());

        extractTargetButton = rightScrollPanel.addChild(Button.builder(extractMsg, b -> extractSelected())
                .bounds(rx + imbueW + 4, scrollY0, extractW, ROW_H).build());

        scrollButton = rightScrollPanel.addChild(Button.builder(Component.translatable("screen.randomspellbench.btn_scroll"), b -> spawnSelectedScroll())
                .bounds(rx, scrollY0, rw, ROW_H).build());

        learnButton = rightScrollPanel.addChild(Button.builder(Component.translatable("screen.randomspellbench.btn_learn"), b -> {/* 长按逻辑在 tick() */})
                .bounds(rx, scrollY0, rw, ROW_H).build());

        previewButton = rightScrollPanel.addChild(Button.builder(Component.translatable("screen.randomspellbench.btn_preview"), b -> previewSelected())
                .bounds(rx, scrollY0, rw, ROW_H).build());

        repeatButton = rightScrollPanel.addChild(Button.builder(Component.translatable("screen.randomspellbench.btn_repeat"), b -> repeatLast())
                .bounds(rx, scrollY0, rw, ROW_H).build());

        // —— 底部固定：随机分配 + 关闭（始终可见，不参与滚动）——
        int bottomY = rightPanelY1 - 18;
        randomizeButton = Button.builder(Component.translatable("screen.randomspellbench.randomize"), b -> randomize())
                .bounds(rx, bottomY, (rw - 4) / 2, 16).build();
        closeButton = Button.builder(Component.translatable("screen.randomspellbench.btn_close"), b -> this.onClose())
                .bounds(rx + (rw - 4) / 2 + 4, bottomY, (rw - 4) / 2, 16).build();
        addRenderableWidget(randomizeButton);
        addRenderableWidget(closeButton);

        addRenderableWidget(searchBox);
        addRenderableWidget(castCycleButton);
        addRenderableWidget(spellList);
        addRenderableWidget(selectAllButton);
        addRenderableWidget(clearAllButton);
        // 操作按钮已在 init() 中 addChild 到 rightScrollPanel（随 scroll 一起渲染）

        // refreshDetail() 末尾会调用 reflowRightPanel()：按当前可见性流式重排 Y 并重算 scroll 高度
        refreshModeButtons();
        refreshDetail();
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

    /**
     * 兜底转发：焦点未落在滚动面板上时，Screen 不会把拖动事件交给它，
     * 这里补一次，保证滑块拖动不会因焦点在别处而失效。
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return rightScrollPanel != null && rightScrollPanel.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /**
     * 兜底收尾：无论指针最后松在哪个位置，都强制结束滚动面板内的拖动。
     * 防止「拖出面板/窗口外松手」导致拖动状态残留，下一次拖动误改上一个控件。
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (rightScrollPanel != null) {
            rightScrollPanel.clearDragging();
        }
        return handled;
    }

    /**
     * 唯一的「选中法术」入口：行内任意位置（勾选框 / 图标 / 名称 / 空白）的点击都汇聚到这里，
     * 保证高亮行、右侧「选中: xxx」、等级滑块、生成卷轴 / 思索 / 长按学习用的是同一个法术。
     */
    private void selectSpell(@Nullable AbstractSpell spell) {
        selectedSpell = spell;
        refreshDetail();
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

    /** 直接选中注入 / 拆卷轴的目标槽位（并排按钮，一次点击直达）。 */
    private void selectImbueTarget(ImbueTarget target) {
        imbueTarget = target;
        refreshDetail();
    }

    /**
     * 注入选中的法术到目标槽位的物品。
     * 等级沿用「等级规则」的下限（固定等级模式即固定等级），与「点击思索」预览用同一取值，
     * 避免玩家在 GUI 里看到的等级和实际注入的等级不是同一个。
     *
     * 不关界面：注入结果走 actionbar，且玩家通常要连着换目标/换法术继续注。
     */
    private void imbueSelected() {
        if (selectedSpell == null) {
            return;
        }
        int level = config.effectiveRange(selectedSpell).getMinLevel();
        NetworkHandler.sendToServer(new C2SImbueSpellPacket(
                C2SImbueSpellPacket.Action.IMBUE, selectedSpell.getSpellId(), level, imbueTarget.key()));
    }

    /**
     * 「拆下卷轴」：把当前选中槽位物品上的法术拆成卷轴放进背包。
     * 不依赖选中法术，选中目标槽位即可触发；不关界面以便连续换目标操作
     * （结果提示由服务端发回，走 actionbar / 聊天栏）。
     */
    private void extractSelected() {
        NetworkHandler.sendToServer(new C2SImbueSpellPacket(
                C2SImbueSpellPacket.Action.EXTRACT, "", 0, imbueTarget.key()));
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

    /**
     * 是否显示「固定等级」提示行：固定等级模式下「选中法术」整段隐藏，
     * 需要一行文字说明该法术的独立等级范围已停用（未选法术时没有可说明的对象，不显示）。
     * 布局与渲染共用此判定，避免两边条件不一致导致文字画在没有占位的地方。
     */
    private boolean showFixedHint() {
        return config.getLevelMode() == LevelMode.FIXED && selectedSpell != null;
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
        // 注入需要选中法术；拆下卷轴不需要（只跟目标槽位有关），因此始终可用
        imbueButton.active = hasSelection;
        extractTargetButton.active = true;
        for (Button slot : imbueTargetButtons.values()) {
            slot.active = true;
        }

        // 条件按钮：不满足条件时隐藏（隐藏后由 reflowRightPanel 紧凑补位，不留空白）
        // - 长按学习：仅选中「远古巫术」学派法术时才有意义
        // - 点击思索：仅 iss_ponder 模组已加载时可用
        learnButton.visible = hasSelection && isEldritch(selectedSpell);
        previewButton.visible = PonderCompat.isPonderLoaded();
        scrollButton.visible = true;
        repeatButton.visible = true;
        for (Button slot : imbueTargetButtons.values()) {
            slot.visible = true;
        }
        imbueButton.visible = true;
        extractTargetButton.visible = true;

        minOnePerSchoolButton.setMessage(Component.literal(
                config.isMinOnePerSchool() ? "[x] " : "[ ] ")
                .append(Component.translatable("screen.randomspellbench.min_one_per_school")));

        // 部位按钮：选中的白字，其余灰色（选中态的橙色描边在渲染层补，见 drawImbueTargetHighlight）
        for (Map.Entry<ImbueTarget, Button> entry : imbueTargetButtons.entrySet()) {
            boolean current = entry.getKey() == imbueTarget;
            entry.getValue().setMessage(Component.translatable(entry.getKey().shortTranslationKey())
                    .withStyle(current ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        }

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

        // 长按学习按钮（仅远古巫术显示），提示按住时长（已删除 Shift=全部 批量逻辑）
        learnButton.setMessage(Component.translatable("screen.randomspellbench.btn_learn")
                .append(" (" + (LEARN_HOLD_MS / 1000) + "." + (LEARN_HOLD_MS % 1000 / 100) + "s)"));

        // 文案只显示「点击思索」，不再拼接时长后缀（长按行为本身保留）
        previewButton.setMessage(Component.translatable("screen.randomspellbench.btn_preview"));

        // 按当前可见性流式重排 Y：隐藏控件不占位，下方控件自动上移补位
        reflowRightPanel();
    }

    /**
     * 右面板流式重排：从 scrollY0 起按固定顺序摆放，只为「可见」控件分配 Y 并推进游标，
     * 隐藏控件直接跳过（不占任何纵向空间）。
     *
     * <p>解决的问题：过去每个控件的 Y 在 init() 里写死，一旦某按钮隐藏
     * （未加载 iss_ponder 的「点击思索」、非远古巫术的「长按学习」、
     * 未选法术/固定等级时整段隐藏的「选中法术」），原位置会留下大片空白，
     * 上下控件间距被撑得很开。现在隐藏即消失，下方控件自动贴上来。</p>
     *
     * <p>同时把各段标题行的 Y 写入 sectionLevelY / sectionSpellY / sectionActionY / selectedNameY，
     * 渲染层直接读这些字段，不再用「某控件.getY() - 偏移」反推（重排后反推会错位）。</p>
     */
    private void reflowRightPanel() {
        int y = scrollY0;

        // —— 法术数量 ——
        y = place(countSlider, y, ROW_H);
        y = place(minOnePerSchoolButton, y, 12);

        // —— 等级规则 ——
        y += SECTION_GAP;
        sectionLevelY = y;
        y += SECTION_TITLE_H;
        levelRangeButton.setY(y);
        levelFixedButton.setY(y); // 同行并排两个按钮
        y += ROW_H + ROW_GAP;
        y = place(globalMinSlider, y, ROW_H);
        y = place(globalMaxSlider, y, ROW_H);
        y = place(fixedLevelSlider, y, ROW_H);

        // —— 选中法术（整段可能隐藏：未选法术 或 固定等级模式）——
        if (useGlobalButton.visible) {
            y += SECTION_GAP;
            sectionSpellY = y;
            y += SECTION_TITLE_H;
            selectedNameY = y;
            y += SECTION_TITLE_H;
            y = place(useGlobalButton, y, 12);
            y = place(spellMinSlider, y, ROW_H);
            y = place(spellMaxSlider, y, ROW_H);
        } else if (showFixedHint()) {
            // 固定等级模式下整段隐藏：给「固定等级提示」单独占一行（位置与原区段标题一致）。
            // 不能沿用 sectionSpellY——本分支里它不会被赋值，保留的旧值会让提示压到下方内容上。
            y += SECTION_GAP;
            fixedHintY = y;
            y += SECTION_TITLE_H;
        }

        // —— 操作 ——
        y += SECTION_GAP;
        sectionActionY = y;
        y += SECTION_TITLE_H;
        // 注入区：部位 7 连排一行 + 注入/拆下卷轴并排一行（原「循环按钮 / 注入 / 清除」3 行 → 2 行）
        y = placeRow(y, imbueTargetButtons.values(), ROW_H);
        y = placeRow(y, List.of(imbueButton, extractTargetButton), ROW_H);
        y = place(scrollButton, y, ROW_H);
        y = place(learnButton, y, ROW_H);
        y = place(previewButton, y, ROW_H);
        y = place(repeatButton, y, ROW_H);

        // 重算内容高度与滚动范围（新 Y 立即生效）
        rightScrollPanel.layout();
    }

    /**
     * 若控件可见则摆到 y 并返回下一行 Y；不可见则原样返回 y（不占纵向空间）。
     */
    private static int place(AbstractWidget widget, int y, int height) {
        if (!widget.visible) {
            return y;
        }
        widget.setY(y);
        return y + height + ROW_GAP;
    }

    /**
     * 把一组控件摆在同一行，整组只占一个行高（任一可见即占位）。
     * 用于注入区：7 个槽位按钮并排、「注入法术 + 拆下卷轴」并排，
     * 避免同类操作各占一行把右面板撑长。
     */
    private static int placeRow(int y, Collection<? extends AbstractWidget> widgets, int height) {
        boolean anyVisible = false;
        for (AbstractWidget widget : widgets) {
            if (!widget.visible) {
                continue;
            }
            widget.setY(y);
            anyVisible = true;
        }
        return anyVisible ? y + height + ROW_GAP : y;
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

        int so = rightScrollPanel != null ? rightScrollPanel.getScrollOffset() : 0;
        // 滚动内容覆盖层（段标题 / 选中法术 / 状态文字）：裁剪严格限制在 ScrollPanel 区域内，
        // 不再溢出到上方[测试控制台]标题区 或 下方[随机分配/关闭]固定底栏——
        // 这是「选中法术」标题/法术名/操作按钮被那两个固定区盖住的根因。
        g.enableScissor(rightPanelX0, rightPanelY0, rightPanelX1, rightPanelY1 - BOTTOM_BAR_H);
        g.pose().pushPose();
        g.pose().translate(0, -so, 0);
        try {
            drawSectionLabels(g);
            drawLabels(g);
            drawStatus(g); // 状态文字与「操作」标题同行；放在这里以跟随 scroll 滚动
        } finally {
            g.pose().popPose();
            g.disableScissor();
        }

        // 长按学习 / 思索进度条画在 ScrollPanel 内（与对应按钮同位置）
        g.enableScissor(rightPanelX0, rightPanelY0, rightPanelX1, rightPanelY1 - BOTTOM_BAR_H);
        g.pose().pushPose();
        g.pose().translate(0, -so, 0);
        try {
            drawLongPressProgress(g);
            drawPreviewProgress(g);
            drawImbueTargetHighlight(g);
        } finally {
            g.pose().popPose();
            g.disableScissor();
        }

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

    /**
     * 右面板分区标题（跟随 rightScrollPanel 滚动）。由 render() 在内容板块内调用。
     * Y 直接读 reflowRightPanel() 算好的 sectionLevelY / sectionSpellY / sectionActionY，
     * 不再用「控件.getY() - 偏移」反推——重排后控件 Y 会变，反推会错位。
     */
    private void drawSectionLabels(GuiGraphics g) {
        int rx = rightPanelX0 + 8;
        drawSectionLabel(g, "screen.randomspellbench.section_level", rx, sectionLevelY);
        // 「选中法术」区段标题只在区段实际显示时绘制；区段隐藏（未选法术 / 固定等级）时若照旧绘制，
        // 会与下方被拉上来的操作按钮重叠——这正是「直接进入界面错位、点固定法术后消失」的根因。
        if (useGlobalButton.visible) {
            drawSectionLabel(g, "screen.randomspellbench.section_spell", rx, sectionSpellY);
        }
        if (showFixedHint()) {
            // 固定等级提示：说明「选中法术」整段（独立等级范围）为何隐藏。
            // Y 由 reflowRightPanel() 分配并占好位（fixedHintY），不再借用 sectionSpellY。
            g.drawString(font, Component.translatable("screen.randomspellbench.fixed_hint",
                    config.getFixedLevel()).getString(),
                    rx + 6, fixedHintY, 0xFFB08A55, false);
        }
        // 「操作」区段标题（与「状态」文字同一行，drawStatus 负责画状态）
        drawSectionLabel(g, "screen.randomspellbench.section_action", rx, sectionActionY);
    }

    private void drawLabels(GuiGraphics g) {
        // spell 区段隐藏时（未选法术或固定等级）不画「选中: ...」行，避免占用/重叠
        if (!useGlobalButton.visible) {
            return;
        }
        int rx = rightPanelX0 + 8;

        // 分两段绘制：先画「选中: 」前缀（灰色），再画法术名（学派色）
        String prefix = Component.translatable("screen.randomspellbench.selected_spell").getString() + ": ";
        int nameY = selectedNameY;
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

    /**
     * 部位按钮用的是「主 / 副 / 头 / 胸 / 腿 / 脚」这类短标签（6 个挤一行放不下全名），
     * 悬停时补一个完整槽位名的 tooltip。
     *
     * @return 命中部位按钮则为 true（此时不应再叠加法术 tooltip）
     */
    private boolean drawImbueSlotTooltip(GuiGraphics g, int screenX, int screenY) {
        for (Map.Entry<ImbueTarget, Button> entry : imbueTargetButtons.entrySet()) {
            Button slot = entry.getValue();
            if (slot.visible && slot.isHovered()) {
                g.renderComponentTooltip(font,
                        List.of(Component.translatable(entry.getKey().translationKey())), screenX, screenY);
                return true;
            }
        }
        return false;
    }

    /**
     * 当前注入部位的选中高亮。
     *
     * Minecraft 的 {@link Button} 只有 hover 态、没有「选中」态，
     * 6 个部位按钮并排后光靠文字颜色很难一眼看出哪一项生效，
     * 这里在按钮之上补一层橙色描边（画在 super.render() 之后，不会被按钮背景盖掉）。
     */
    private void drawImbueTargetHighlight(GuiGraphics g) {
        Button selected = imbueTargetButtons.get(imbueTarget);
        if (selected == null || !selected.visible) {
            return;
        }
        int x = selected.getX();
        int y = selected.getY();
        drawBorder(g, x, y, x + selected.getWidth(), y + selected.getHeight(), COLOR_ACCENT);
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
        // 左侧「已选 N/M」统计（清除全部按钮右侧）
        String countText = Component.translatable(
                "screen.randomspellbench.selected_count", selectedCount, pool.size()).getString();
        int countX = clearAllButton.getX() + clearAllButton.getWidth() + 6;
        int countY = clearAllButton.getY() + 3;
        int countW = Math.max(0, (leftPanelX1 - 8) - countX);
        g.drawString(font, font.plainSubstrByWidth(countText, countW), countX, countY, 0xFFC0B08A, false);

        // 右侧「操作」标题旁的状态文字：与「操作」同一行，位于其右侧
        // - 未分配：直接显示「未分配」（不带「状态:」前缀，避免视觉重心压在未操作状态）
        // - 已分配：显示「状态: 已分配」+「·上次 N 个」（有结果时）
        String status;
        if (ClientConfigData.isAssigned()) {
            status = Component.translatable("screen.randomspellbench.assigned_status",
                    Component.translatable("screen.randomspellbench.status_assigned").getString()).getString();
            int last = ClientConfigData.getLastResult().size();
            if (last > 0) {
                status += Component.translatable("screen.randomspellbench.last_result", last).getString();
            }
        } else {
            status = Component.translatable("screen.randomspellbench.status_pending").getString();
        }
        int rx = rightPanelX0 + 8;
        // 与「操作」标题同一行（sectionActionY 由 reflowRightPanel 计算）
        int titleY = sectionActionY;
        // drawSectionLabel 排版：色条 x..x+2，文字从 x+6 起，故状态从标题文字右侧 +10 开始
        String actionTitle = Component.translatable("screen.randomspellbench.section_action").getString();
        int statusX = rx + 6 + font.width(actionTitle) + 10;
        int statusW = Math.max(0, (rightPanelX1 - 8) - statusX);
        g.drawString(font, font.plainSubstrByWidth(status, statusW), statusX, titleY, 0xFFC0B08A, false);
    }

    private void drawHoveredTooltip(GuiGraphics g, int screenX, int screenY) {
        if (drawImbueSlotTooltip(g, screenX, screenY)) {
            return;
        }
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
   