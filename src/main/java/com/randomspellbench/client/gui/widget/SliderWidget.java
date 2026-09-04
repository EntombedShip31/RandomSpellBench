package com.randomspellbench.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 轻量滑块控件（不依赖 Forge 的 ForgeSlider，规避版本 API 差异）。
 *
 * 布局：左侧固定比例的区域显示「标签: 数值」，右侧才是轨道。
 * 这样数值文字永远不会和滑块把手重叠。
 *
 * 节流：onChange 在拖动过程中连续触发（仅做本地更新），
 * onCommit 只在「点击」或「拖动结束」时触发一次，用来发网络包，
 * 避免拖一次滑块就发出几十个数据包。
 */
public class SliderWidget extends AbstractWidget {
    /** 标签区占宽度的比例。 */
    private static final double LABEL_RATIO = 0.56;
    /** 把手尺寸：小方块，不再做成占满行高的「大按钮」（与左侧法术池勾选框的修法一致）。 */
    private static final int KNOB_W = 3;
    private static final int KNOB_H = 6;

    private double minValue;
    private double maxValue;
    private final double stepSize;
    private final Function<Double, Component> displayMapper;
    private final Consumer<Double> onChange;
    @javax.annotation.Nullable
    private final Consumer<Double> onCommit;
    private double value;
    private double lastCommitted;
    /**
     * 自持拖动状态：不复用父类 AbstractWidget 的 dragging 字段。
     *
     * 父类那套状态机只有 AbstractSlider 会正确收尾（mouseReleased 里置回 false），
     * 自定义滑块一旦用错，拖动结束后标志会残留为 true；
     * 而事件是被容器广播的，残留者会抢走后续所有拖动
     * （表现为：拖动下面任意滑块，实际改的却是法术数量）。
     */
    private boolean dragging = false;

    public SliderWidget(int x, int y, int width, int height,
                        double minValue, double maxValue, double currentValue, double stepSize,
                        Function<Double, Component> displayMapper, Consumer<Double> onChange,
                        @javax.annotation.Nullable Consumer<Double> onCommit) {
        super(x, y, width, height, Component.literal(""));
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.stepSize = stepSize;
        this.displayMapper = displayMapper;
        this.onChange = onChange;
        this.onCommit = onCommit;
        this.value = clamp(currentValue);
        this.lastCommitted = this.value;
    }

    public double getValue() {
        return value;
    }

    public int getIntValue() {
        return (int) Math.round(value);
    }

    public void setValue(double v) {
        this.value = clamp(v);
        this.lastCommitted = this.value;
    }

    public void setBounds(double minValue, double maxValue, double currentValue) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        setValue(currentValue);
    }

    /** 提交当前值（供外部在需要时主动提交）。 */
    public void commit() {
        if (onCommit != null && Double.compare(value, lastCommitted) != 0) {
            lastCommitted = value;
            onCommit.accept(value);
        }
    }

    private double clamp(double v) {
        v = Mth.clamp(v, minValue, maxValue);
        if (stepSize > 0) {
            v = minValue + Math.round((v - minValue) / stepSize) * stepSize;
        }
        return v;
    }

    private int labelWidth() {
        return (int) Math.round(getWidth() * LABEL_RATIO);
    }

    private int trackStart() {
        return getX() + labelWidth() + 2;
    }

    private int trackEnd() {
        return getX() + getWidth() - 3;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        boolean enabled = this.active;

        // 注意：这里不再画「整行底衬 + 上下边框」。
        // 那两层让滑块看起来像个大方块按钮，既压住下方控件、也不符合滑条应有的细长外观。

        // 标签 + 数值（左侧，永不与把手重叠）
        if (displayMapper != null) {
            Component label = displayMapper.apply(value);
            int textColor = enabled ? 0xFFE8D9BE : 0xFF6A6152;
            g.drawString(mc.font, mc.font.plainSubstrByWidth(label.getString(), labelWidth() - 4),
                    getX() + 3, getY() + (getHeight() - mc.font.lineHeight) / 2 + 1, textColor, false);
        }

        // 轨道（右侧细线）
        int tx0 = trackStart();
        int tx1 = trackEnd();
        int trackY = getY() + getHeight() / 2 - 1;
        g.fill(tx0, trackY, tx1, trackY + 2, enabled ? 0xFF3A2C14 : 0xFF241C0C);

        double ratio = (value - minValue) / Math.max(1e-6, maxValue - minValue);
        int usable = Math.max(1, tx1 - tx0 - KNOB_W);
        int fillWidth = (int) Math.round(usable * ratio);
        g.fill(tx0, trackY, tx0 + fillWidth, trackY + 2, enabled ? 0xFFFF8C00 : 0xFF7A4A12);

        // 把手：与轨道居中的小方块，不再占满整行高度
        int knobX = tx0 + fillWidth;
        int knobY = trackY + 1 - KNOB_H / 2;
        int knobColor = enabled ? (isHoveredOrFocused() ? 0xFFFFD699 : 0xFFFFA53D) : 0xFF66552E;
        g.fill(knobX, knobY, knobX + KNOB_W, knobY + KNOB_H, knobColor);
    }

    /**
     * 按下：只有「本控件可见可用 + 指针落在本矩形内 + 落在轨道上」才开始拖动。
     *
     * 不再走父类的 onClick，避免父类在 onClick 之外额外改动 dragging 状态。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible) {
            return false;
        }
        if (!isMouseOver(mouseX, mouseY) || !isInTrack(mouseX)) {
            return false;
        }
        this.dragging = true;
        applyFromMouse(mouseX);
        onChange.accept(value);
        commit();
        return true;
    }

    /** 点击位置是否落在轨道内（含少量边距，便于点中端点）。 */
    private boolean isInTrack(double mouseX) {
        return mouseX >= trackStart() - 2 && mouseX <= trackEnd() + 2;
    }

    /**
     * 拖动：只在本控件处于拖动中时响应，且不再要求指针停在轨道内——
     * 拖出轨道/面板只是把数值 clamp 到端点，不会「丢手」。
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!this.dragging) {
            return false;
        }
        applyFromMouse(mouseX);
        onChange.accept(value);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!this.dragging) {
            return false;
        }
        this.dragging = false;
        commit();
        return true;
    }

    /** 兜底：容器/界面异常收尾时强制结束拖动，避免状态残留。 */
    public void cancelDrag() {
        if (this.dragging) {
            this.dragging = false;
            commit();
        }
    }

    private void applyFromMouse(double mouseX) {
        int tx0 = trackStart();
        int tx1 = trackEnd();
        double ratio = Mth.clamp((mouseX - tx0) / Math.max(1, tx1 - tx0 - 3), 0, 1);
        setValue0(minValue + ratio * (maxValue - minValue));
    }

    /** 设置数值但不改变 lastCommitted（拖动中使用）。 */
    private void setValue0(double v) {
        this.value = clamp(v);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput);
    }
}
