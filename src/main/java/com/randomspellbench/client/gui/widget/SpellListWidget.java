package com.randomspellbench.client.gui.widget;

import com.randomspellbench.capability.PlayerSpellConfig;
import com.randomspellbench.capability.SpellLevelRange;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 法术浏览器（可滚动）。
 *
 * - 按 SchoolType 分组，分组标题可折叠，accent 使用学派主题色；
 * - 支持搜索词与施法类型过滤；
 * - 标题行左侧有勾选框，可一键全选/取消整个流派；
 * - 法术行左侧显示法术图标，右侧显示等级范围；
 * - 右侧滚动条支持鼠标滚轮、点击跳转与按住拖拽。
 *
 * 渲染优化：
 * - 行渲染拆成「背景 / 图标 / 文字」三遍，避免逐行在 fill 与纹理之间反复切换
 *   （GuiGraphics 切换 RenderType 会触发 buffer flush，是 GUI 渲染的主要开销之一）；
 * - 每行的启用状态与等级文本在 rebuild 时缓存，渲染期不再重算。
 */
public class SpellListWidget extends AbstractWidget {
    public interface Delegate {
        boolean isSelected(AbstractSpell spell);

        void onSelect(AbstractSpell spell);

        void onToggle(AbstractSpell spell);

        /** 整组（流派）切换：由实现方决定「全开还是全关」。 */
        void onToggleGroup(List<AbstractSpell> spells);
    }

    public static final int ROW_HEIGHT = 16;

    /** 勾选框状态。 */
    private enum CheckState {
        NONE, PARTIAL, FULL
    }

    /**
     * 行模型：groupId != null 表示分组标题行。
     * enabled / levelText / icon 在 rebuild 时缓存（icon 为法术图标 ResourceLocation，
     * 避免渲染期每帧调用 getSpellIconResource() 重复解析注册表）。
     */
    private record Row(@Nullable AbstractSpell spell, @Nullable SchoolType school,
                       Component label, int accent, int count,
                       @Nullable ResourceLocation groupId, List<AbstractSpell> spells,
                       boolean enabled, String levelText, @Nullable ResourceLocation icon) {
        boolean isHeader() {
            return groupId != null;
        }
    }

    private static final ResourceLocation OTHER_GROUP =
            ResourceLocation.fromNamespaceAndPath("randomspellbench", "other");

    // 行内几何（勾选框 / 图标 / 名称 / 等级）
    private static final int CHECK_X_OFF = 2;
    private static final int CHECK_W = 10;
    private static final int ICON_X_OFF = 15;
    private static final int ICON_W = 16;
    private static final int LEVEL_W = 62;

    private List<AbstractSpell> all;
    private final List<AbstractSpell> filtered = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<ResourceLocation> collapsed = new HashSet<>();
    private final PlayerSpellConfig config;
    private final Delegate delegate;
    private int scroll;
    private String search = "";
    @Nullable
    private CastType castFilter;
    /** rebuild 次数，供外部缓存失效判断。 */
    private int rebuildCount;

    // 滚动条拖拽状态
    private boolean draggingScroll;
    private double dragGrabOffset;

    public SpellListWidget(int x, int y, int width, int height,
                           List<AbstractSpell> all, PlayerSpellConfig config, Delegate delegate) {
        super(x, y, width, height, Component.literal("spell_list"));
        this.all = all;
        this.config = config;
        this.delegate = delegate;
        rebuild();
    }

    public void setSearch(String search) {
        this.search = search == null ? "" : search.toLowerCase(Locale.ROOT);
        rebuild();
    }

    public void setCastFilter(@Nullable CastType castFilter) {
        this.castFilter = castFilter;
        rebuild();
    }

    /** 同步服务端 banned/whitelist 后整体换池（不新建 widget），并重建可见列表。 */
    public void setPool(List<AbstractSpell> all) {
        this.all = all;
        rebuild();
    }

    /** 过滤后的法术（不含分组标题），供「全选/清除」使用。 */
    public List<AbstractSpell> getVisible() {
        return filtered;
    }

    /** rebuild 次数：外部缓存（如勾选计数）据此判断是否需要重算。 */
    public int getRebuildCount() {
        return rebuildCount;
    }

    @Nullable
    public AbstractSpell getHovered(int mouseX, int mouseY) {
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return null;
        }
        // 列表实际绘制行只到 visibleBottom；其下方的区域（被列表底边/遮罩盖住的部分行）
        // 不触发 hover，否则鼠标扫过底部被遮罩挡住的法术时仍会弹出详情，与视觉不一致
        int visibleBottom = getY() + Math.min(visibleRows(), rows.size()) * ROW_HEIGHT;
        if (mouseY >= visibleBottom) {
            return null;
        }
        // tooltip 触发范围缩小到「图标 + 名称左侧 ~50px」，
        // 避免鼠标扫过右侧等级区或外部滑块时频繁触发 tooltip（每次 hover 会重建 lines 列表）
        int hoverLimit = getX() + ICON_X_OFF + ICON_W + 50;
        if (mouseX >= hoverLimit) {
            return null;
        }
        int row = rowIndexAt(mouseY);
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        return rows.get(row).spell();
    }

    private int rowIndexAt(double mouseY) {
        return (int) ((mouseY - getY()) / ROW_HEIGHT) + scroll;
    }

    public void rebuild() {
        rows.clear();
        filtered.clear();
        rebuildCount++;

        // 1) 按搜索词与施法类型过滤
        for (AbstractSpell spell : all) {
            if (castFilter != null && spell.getCastType() != castFilter) {
                continue;
            }
            if (!search.isEmpty() && !matches(spell, search)) {
                continue;
            }
            filtered.add(spell);
        }

        // 2) 按学派分组（按学派注册表顺序，保证顺序稳定）
        Map<SchoolType, List<AbstractSpell>> grouped = new LinkedHashMap<>();
        for (SchoolType school : SchoolRegistry.REGISTRY.get().getValues()) {
            grouped.put(school, new ArrayList<>());
        }
        List<AbstractSpell> others = new ArrayList<>();
        for (AbstractSpell spell : filtered) {
            List<AbstractSpell> bucket = grouped.get(spell.getSchoolType());
            if (bucket == null) {
                others.add(spell);
            } else {
                bucket.add(spell);
            }
        }

        // 3) 生成行：分组标题 + 该组法术（启用状态与等级文本在此缓存）
        for (Map.Entry<SchoolType, List<AbstractSpell>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            SchoolType school = entry.getKey();
            rows.add(new Row(null, school, school.getDisplayName(), accentOf(school),
                    entry.getValue().size(), school.getId(), entry.getValue(), true, "", null));
            if (!collapsed.contains(school.getId())) {
                for (AbstractSpell spell : entry.getValue()) {
                    rows.add(new Row(spell, null, spell.getDisplayName(Minecraft.getInstance().player),
                            accentOf(school), 0, null, List.of(),
                            config.getFilter(spell).isEnabled(), levelTextFor(spell), iconOf(spell)));
                }
            }
        }
        if (!others.isEmpty()) {
            rows.add(new Row(null, null, Component.translatable("screen.randomspellbench.school_other"),
                    0xFFAAAAAA, others.size(), OTHER_GROUP, others, true, "", null));
            if (!collapsed.contains(OTHER_GROUP)) {
                for (AbstractSpell spell : others) {
                    rows.add(new Row(spell, null, spell.getDisplayName(Minecraft.getInstance().player),
                            0xFFFFFF, 0, null, List.of(),
                            config.getFilter(spell).isEnabled(), levelTextFor(spell), iconOf(spell)));
                }
            }
        }
        clampScroll();
    }

    /** 法术图标资源位置：在行构建时解析一次并缓存，渲染期不再查注册表。 */
    @Nullable
    private static ResourceLocation iconOf(AbstractSpell spell) {
        try {
            return spell.getSpellIconResource();
        } catch (Throwable t) {
            return null;
        }
    }

    private String levelTextFor(AbstractSpell spell) {
        SpellLevelRange range = config.effectiveRange(spell);
        return range.getMinLevel() == range.getMaxLevel()
                ? "Lv " + range.getMinLevel()
                : "Lv " + range.getMinLevel() + "-" + range.getMaxLevel();
    }

    private boolean matches(AbstractSpell spell, String query) {
        String name = spell.getDisplayName(Minecraft.getInstance().player).getString().toLowerCase(Locale.ROOT);
        String id = spell.getSpellId().toLowerCase(Locale.ROOT);
        return name.contains(query) || id.contains(query);
    }

    private static int accentOf(SchoolType school) {
        TextColor color = school.getDisplayName().getStyle().getColor();
        return color == null ? 0xFFFFFF : color.getValue();
    }

    private int visibleRows() {
        return Math.max(1, getHeight() / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - visibleRows());
    }

    private void clampScroll() {
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    // ---------------- 滚动条几何 ----------------

    private int trackX() {
        return getX() + getWidth() - 6;
    }

    private int trackTop() {
        return getY();
    }

    private int trackBottom() {
        return getY() + visibleRows() * ROW_HEIGHT;
    }

    private int thumbHeight() {
        int trackH = Math.max(1, trackBottom() - trackTop());
        int rows = visibleRows();
        return Math.max(10, trackH * rows / Math.max(1, rows + maxScroll()));
    }

    private int thumbY() {
        int max = maxScroll();
        int trackH = Math.max(1, trackBottom() - trackTop());
        int thumbH = thumbHeight();
        if (max <= 0) {
            return trackTop();
        }
        return trackTop() + (int) Math.round((trackH - thumbH) * scroll / (double) max);
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        return mouseX >= trackX() - 1 && mouseX <= getX() + getWidth()
                && mouseY >= trackTop() && mouseY <= trackBottom();
    }

    private void applyDrag(double mouseY) {
        int max = maxScroll();
        if (max <= 0) {
            scroll = 0;
            return;
        }
        int top = trackTop();
        int trackH = Math.max(1, trackBottom() - top);
        int thumbH = thumbHeight();
        double newThumbY = Mth.clamp(mouseY - dragGrabOffset, top, Math.max(top, top + trackH - thumbH));
        scroll = (int) Math.round((newThumbY - top) * max / (double) Math.max(1, trackH - thumbH));
        clampScroll();
    }

    // ---------------- 渲染 ----------------

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        clampScroll();

        int rowsToDraw = Math.min(visibleRows(), rows.size());
        boolean hovered = isHovered();

        g.fill(getX(), getY(), getX() + getWidth(), getY() + rowsToDraw * ROW_HEIGHT, 0xE6120B04);

        // 取本次要绘制的行
        List<Row> drawRows = new ArrayList<>(rowsToDraw);
        for (int r = 0; r < rowsToDraw; r++) {
            int index = scroll + r;
            if (index < rows.size()) {
                drawRows.add(rows.get(index));
            }
        }

        // 第 1 遍：背景 + 勾选框（全部为 fill，不切换渲染类型）
        for (int r = 0; r < drawRows.size(); r++) {
            Row row = drawRows.get(r);
            int rowY = getY() + r * ROW_HEIGHT;
            boolean rowHovered = hovered && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX < trackX() - 1;
            if (row.isHeader()) {
                renderHeaderBg(g, row, rowY, rowHovered);
            } else {
                renderSpellBg(g, row, rowY, rowHovered);
            }
        }

        // 第 2 遍：法术图标（连续 blit，减少纹理绑定；icon 在 rebuild 时已缓存）
        for (int r = 0; r < drawRows.size(); r++) {
            Row row = drawRows.get(r);
            if (row.isHeader() || row.icon() == null) {
                continue;
            }
            renderIcon(g, row.icon(), getX() + ICON_X_OFF, getY() + r * ROW_HEIGHT);
        }

        // 第 3 遍：文字（连续 drawString）
        for (int r = 0; r < drawRows.size(); r++) {
            Row row = drawRows.get(r);
            int rowY = getY() + r * ROW_HEIGHT;
            if (row.isHeader()) {
                renderHeaderText(g, mc, row, rowY);
            } else {
                renderSpellText(g, mc, row, rowY);
            }
        }

        if (rows.isEmpty()) {
            g.drawCenteredString(mc.font, Component.translatable("screen.randomspellbench.empty").getString(),
                    getX() + getWidth() / 2, getY() + 4, 0x707070);
        }

        renderScrollbar(g, rowsToDraw);
        renderBorder(g, getY() + rowsToDraw * ROW_HEIGHT);
    }

    private void renderHeaderBg(GuiGraphics g, Row row, int rowY, boolean rowHovered) {
        int bg = rowHovered ? 0x663A2410 : 0x55281A08;
        g.fill(getX(), rowY, getX() + getWidth(), rowY + ROW_HEIGHT, bg);
        g.fill(getX(), rowY, getX() + 2, rowY + ROW_HEIGHT, 0xFF000000 | row.accent());

        int enabled = config.countEnabled(row.spells());
        CheckState state = enabled == 0 ? CheckState.NONE
                : (enabled == row.spells().size() ? CheckState.FULL : CheckState.PARTIAL);
        drawCheckbox(g, getX() + 5, rowY + (ROW_HEIGHT - 10) / 2, state);

        g.fill(getX(), rowY + ROW_HEIGHT - 1, getX() + getWidth(), rowY + ROW_HEIGHT, 0x885A3300);
    }

    private void renderHeaderText(GuiGraphics g, Minecraft mc, Row row, int rowY) {
        int enabled = config.countEnabled(row.spells());
        boolean collapsedState = row.groupId() != null && collapsed.contains(row.groupId());
        String arrow = collapsedState ? ">" : "v";
        String text = arrow + " " + row.label().getString() + " (" + enabled + "/" + row.count() + ")";
        int color = enabled == 0 ? 0xFF8A7A5A : row.accent();
        g.drawString(mc.font, mc.font.plainSubstrByWidth(text, getWidth() - 30),
                getX() + 20, rowY + (ROW_HEIGHT - mc.font.lineHeight) / 2 + 1, color, false);
    }

    private void renderSpellBg(GuiGraphics g, Row row, int rowY, boolean rowHovered) {
        AbstractSpell spell = row.spell();
        if (spell == null) {
            return;
        }
        boolean selected = delegate.isSelected(spell);
        int bg;
        if (selected) {
            bg = 0x99FF8C00;
        } else if (rowHovered) {
            bg = 0x26FFB566;
        } else {
            bg = 0x1A1A1206;
        }
        g.fill(getX(), rowY, getX() + getWidth(), rowY + ROW_HEIGHT, bg);

        if (selected) {
            g.fill(getX(), rowY, getX() + 2, rowY + ROW_HEIGHT, 0xFFFFA53D);
        }

        drawCheckbox(g, getX() + CHECK_X_OFF, rowY + (ROW_HEIGHT - 10) / 2,
                row.enabled() ? CheckState.FULL : CheckState.NONE);
    }

    private void renderSpellText(GuiGraphics g, Minecraft mc, Row row, int rowY) {
        if (row.spell() == null) {
            return;
        }
        int nameX = getX() + ICON_X_OFF + ICON_W + 1;
        int levelX = getX() + getWidth() - LEVEL_W;
        int nameWidth = Math.max(20, levelX - nameX - 2);
        int color = row.enabled() ? row.accent() : 0xFF7A6A4E;
        g.drawString(mc.font, mc.font.plainSubstrByWidth(row.label().getString(), nameWidth),
                nameX, rowY + (ROW_HEIGHT - mc.font.lineHeight) / 2 + 1, color, false);
        g.drawString(mc.font, row.levelText(),
                levelX + 2, rowY + (ROW_HEIGHT - mc.font.lineHeight) / 2 + 1, 0xFFC8B89A, false);
    }

    /**
     * 绘制法术图标（16x16）。
     * 关键点：GuiGraphics.blit 的 7 参数重载默认纹理尺寸为 256x256，
     * 对 16x16 的图标只会采样到左上角 1 个像素（拉伸后就是「纯色块」）。
     * 必须用 9 参数重载显式指定纹理尺寸 16x16，UV 才会覆盖整张图标。
     * 传入的 icon 为 rebuild 时缓存的资源位置，渲染期不再查注册表。
     */
    private static void renderIcon(GuiGraphics g, @Nullable ResourceLocation icon, int x, int y) {
        try {
            if (icon != null) {
                g.blit(icon, x, y, 0, 0, ICON_W, ICON_W, ICON_W, ICON_W);
            }
        } catch (Throwable ignored) {
            // 某些附属法术的图标可能缺失，忽略
        }
    }

    private void drawCheckbox(GuiGraphics g, int x, int y, CheckState state) {
        g.fill(x, y, x + 10, y + 10, 0xFF0E0903);
        g.fill(x + 1, y + 1, x + 9, y + 9, state == CheckState.NONE ? 0xFF2E2210 : 0xFF4A3414);
        g.fill(x + 1, y + 1, x + 9, y + 2, 0x33000000);
        if (state == CheckState.FULL) {
            g.fill(x + 2, y + 2, x + 8, y + 8, 0xFFFF8C00);
            g.fill(x + 3, y + 3, x + 7, y + 7, 0xFFE07A00);
        } else if (state == CheckState.PARTIAL) {
            g.fill(x + 2, y + 4, x + 8, y + 6, 0xFFB36600);
        }
        // 边框（暗橙）
        g.fill(x, y, x + 10, y + 1, 0xFF8A5A1E);
        g.fill(x, y + 9, x + 10, y + 10, 0xFF8A5A1E);
        g.fill(x, y, x + 1, y + 10, 0xFF8A5A1E);
        g.fill(x + 9, y, x + 10, y + 10, 0xFF8A5A1E);
    }

    private void renderScrollbar(GuiGraphics g, int rowsToDraw) {
        int max = maxScroll();
        int top = trackTop();
        int bottom = top + rowsToDraw * ROW_HEIGHT;
        int tx = trackX();
        g.fill(tx, top, tx + 5, bottom, 0x66302008);
        if (max > 0) {
            int thumbH = thumbHeight();
            int ty = thumbY();
            boolean hover = draggingScroll;
            g.fill(tx, ty, tx + 5, ty + thumbH, hover ? 0xFFFFA53D : 0xFFC8802A);
            g.fill(tx, ty, tx + 5, ty + 1, 0xFFFFC97A);
        }
    }

    private void renderBorder(GuiGraphics g, int bottom) {
        g.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY(), 0xFF7A4A12);
        g.fill(getX() - 1, bottom, getX() + getWidth() + 1, bottom + 1, 0xFF7A4A12);
        g.fill(getX() - 1, getY(), getX(), bottom, 0xFF7A4A12);
        g.fill(getX() + getWidth(), getY(), getX() + getWidth() + 1, bottom, 0xFF7A4A12);
    }

    // ---------------- 输入 ----------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX < getX() || mouseX >= getX() + getWidth()
                || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        // 滚动条：点击/开始拖拽（仅左键）
        if (button == 0 && overScrollbar(mouseX, mouseY) && maxScroll() > 0) {
            int ty = thumbY();
            int thumbH = thumbHeight();
            dragGrabOffset = (mouseY >= ty && mouseY <= ty + thumbH)
                    ? mouseY - ty
                    : thumbH / 2.0;
            draggingScroll = true;
            applyDrag(mouseY);
            return true;
        }

        int row = rowIndexAt(mouseY);
        if (row < 0 || row >= rows.size()) {
            return false;
        }
        Row r = rows.get(row);
        if (r.isHeader()) {
            // 左侧勾选框（精准点击，缩小 hit 范围到 CHECK_X_OFF+CHECK_W=12）：
            if (mouseX < getX() + 12) {
                delegate.onToggleGroup(r.spells());
            } else {
                ResourceLocation id = r.groupId();
                if (!collapsed.remove(id)) {
                    collapsed.add(id);
                }
            }
            rebuild();
            return true;
        }
        AbstractSpell spell = r.spell();
        if (spell == null) {
            return false;
        }
        if (button != 0) {
            return false;
        }
        // 勾选框仅在勾选框矩形内可点击（12px），其余区域选中法术
        if (mouseX < getX() + 12) {
            delegate.onToggle(spell);
        } else {
            delegate.onSelect(spell);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            applyDrag(mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }

    // ---------------- 状态访问（用于关闭思索后恢复选择/搜索/滚动位置） ----------------

    public String getSearch() {
        return search;
    }

    public int getScroll() {
        return scroll;
    }

    public Set<ResourceLocation> getCollapsed() {
        return new HashSet<>(collapsed);
    }

    public void setScroll(int s) {
        scroll = Mth.clamp(s, 0, maxScroll());
    }

    public void setCollapsed(Set<ResourceLocation> c) {
        collapsed.clear();
        collapsed.addAll(c);
        rebuild();
    }

    /**
     * 滚动到指定法术行附近（让该行出现在可视区中上部），用于状态恢复。
     */
    public void scrollToSpell(AbstractSpell target) {
        if (target == null) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (target.equals(rows.get(i).spell())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        int visible = visibleRows();
        int desired = Math.max(0, idx - Math.max(0, visible / 3));
        scroll = Mth.clamp(desired, 0, maxScroll());
    }
}
