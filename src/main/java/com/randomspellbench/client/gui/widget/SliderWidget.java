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

    private double minValue;
    private double maxValue;
    private final double stepSize;
    private final Function<Double, Component> displayMapper;
    private final Consumer<Double> onChange;
    @javax.annotation.Nullable
    private final Consumer<Double> onCommit;
    private double value;
    private double lastCommitted;

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

        // 底衬（暖色深灰）
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), enabled ? 0xCC1A1308 : 0x99110D06);

        // 标签 + 数值（左侧，永不与把手重叠）
        if (displayMapper != null) {
            Component label = displayMapper.apply(value);
            int textColor = enabled ? 0xFFE8D9BE : 0xFF6A6152;
            g.drawString(mc.font, mc.font.plainSubstrByWidth(label.getString(), labelWidth() - 4),
                    getX() + 3, getY() + (getHeight() - mc.font.lineHeight) / 2 + 1, textColor, false);
        }

        // 轨道（右侧）
        int tx0 = trackStart();
        int tx1 = trackEnd();
        int trackY = getY() + getHeight() / 2 - 1;
        g.fill(tx0, trackY, tx1, trackY + 2, enabled ? 0xFF3A2C14 : 0xFF241C0C);

        double ratio = (value - minValue) / Math.max(1e-6, maxValue - minValue);
        int usable = Math.max(1, tx1 - tx0 - 3);
        int fillWidth = (int) Math.round(usable * ratio);
        g.fill(tx0, trackY, tx0 + fillWidth, trackY + 2, enabled ? 0xFFFF8C00 : 0xFF7A4A12);

        // 把手（橙色）
        int knobX = tx0 + fillWidth;
        int knobColor = enabled ? (isHoveredOrFocused() ? 0xFFFFD699 : 0xFFFFA53D) : 0xFF66552E;
        g.fill(knobX, getY() + 2, knobX + 3, getY() + getHeight() - 2, knobColor);

        // 边框（暗橙）
        g.fill(getX(), getY(), getX() + getWidth(), getY() + 1, 0xFF6B3D0C);
        g.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), 0xFF6B3D0C);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        applyFromMouse(mouseX);
        onChange.accept(value);
        commit();
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        applyFromMouse(mouseX);
        onChange.accept(value);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        commit();
        return handled;
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
