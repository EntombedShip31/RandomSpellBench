package com.randomspellpvp.client.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单的滚动面板：内容超出可视区域时启用滚轮 + 滚动条。
 *
 * <ul>
 *   <li>内部 children <b>不</b> addRenderableWidget（由 ScrollPanel 自己转发鼠标事件），
 *       避免 Screen 双重渲染。</li>
 *   <li>渲染时对所有 child 应用 translate(0, -scrollOffset) + scissor(自身区域)，
 *       溢出部分被裁剪，不污染相邻面板。</li>
 *   <li>无对象分配热路径。</li>
 * </ul>
 */
public class ScrollPanel extends AbstractWidget {

    private final List<AbstractWidget> children = new ArrayList<>();
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private boolean showScrollbar = false;

    public ScrollPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal(""));
    }

    /** 添加子控件并返回它（便于链式 = scrollPanel.addChild(widget)）。 */
    public <T extends AbstractWidget> T addChild(T widget) {
        children.add(widget);
        return widget;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    /**
     * 计算内容总高度，更新滚动条可见性，clamp scrollOffset。
     * 在 addChild 之后、首次渲染之前调用一次；reflow 后再调。
     */
    public void layout() {
        if (children.isEmpty()) {
            contentHeight = 0;
            showScrollbar = false;
            scrollOffset = 0;
            return;
        }
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        boolean any = false;
        for (AbstractWidget w : children) {
            if (!w.visible) {
                continue; // 隐藏控件不占滚动空间（如未选中法术时隐藏的 spell 区段滑块）
            }
            any = true;
            top = Math.min(top, w.getY());
            bottom = Math.max(bottom, w.getY() + w.getHeight());
        }
        if (!any) {
            contentHeight = 0;
            showScrollbar = false;
            scrollOffset = 0;
            return;
        }
        contentHeight = Math.max(0, bottom - top);
        int visible = getHeight();
        showScrollbar = contentHeight > visible;
        int maxOffset = Math.max(0, contentHeight - visible);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!showScrollbar || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int visible = getHeight();
        int maxOffset = Math.max(0, contentHeight - visible);
        scrollOffset = Mth.clamp(scrollOffset - (int) Math.signum(delta) * 15, 0, maxOffset);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        double ty = mouseY + scrollOffset;
        for (AbstractWidget w : children) {
            if (w.mouseClicked(mouseX, ty, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        double ty = mouseY + scrollOffset;
        for (AbstractWidget w : children) {
            if (w.mouseDragged(mouseX, ty, button, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        double ty = mouseY + scrollOffset;
        for (AbstractWidget w : children) {
            if (w.mouseReleased(mouseX, ty, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 每帧同步内容高度/滚动条：任何时刻的控件可见性与 y 重排（如 reflow 操作按钮、
        // 区段显隐）都立即反映到滚动范围，避免“内容溢出却滚不到底”的显示不全问题
        layout();
        // 内容裁剪到本面板区域，避免溢出
        g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        g.pose().pushPose();
        g.pose().translate(0, -scrollOffset, 0);
        try {
            for (AbstractWidget w : children) {
                w.render(g, mouseX, mouseY + scrollOffset, partialTick);
            }
        } finally {
            g.pose().popPose();
            g.disableScissor();
        }
        if (showScrollbar) {
            renderScrollbar(g);
        }
    }

    private void renderScrollbar(GuiGraphics g) {
        int tx = getX() + getWidth() - 5;
        int ty = getY();
        int th = getHeight();
        g.fill(tx, ty, tx + 5, ty + th, 0x66302008);
        int visible = getHeight();
        int thumbH = Math.max(10, th * visible / Math.max(1, contentHeight));
        int maxOffset = Math.max(1, contentHeight - visible);
        int thumbY = ty + (int) Math.round((double) (th - thumbH) * scrollOffset / maxOffset);
        g.fill(tx, thumbY, tx + 5, thumbY + thumbH, 0xFFFFA53D);
        g.fill(tx, thumbY, tx + 5, thumbY + 1, 0xFFFFC97A);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}