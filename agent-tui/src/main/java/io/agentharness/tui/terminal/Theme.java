package io.agentharness.tui.terminal;

import io.agentharness.tui.render.LineKind;
import io.agentharness.tui.render.RenderedLine;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * 语义类别到终端样式的映射 —— 唯一知道颜色的地方。
 *
 * <p>用色克制：正文保持终端默认前景色，只有需要被一眼扫到的东西才上色
 * （工具调用、卡片边框、错误）。满屏彩色等于没有重点。
 */
final class Theme {

    private static final AttributedStyle USER = AttributedStyle.DEFAULT.bold();
    private static final AttributedStyle ASSISTANT = AttributedStyle.DEFAULT;
    private static final AttributedStyle TOOL = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle CARD = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    private static final AttributedStyle SYSTEM = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
    private static final AttributedStyle ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();
    private static final AttributedStyle HINT = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle LIVE_TAIL = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle STATUS = AttributedStyle.DEFAULT.faint();

    private Theme() {
    }

    static AttributedString style(RenderedLine line) {
        return new AttributedString(line.text(), styleFor(line.kind()));
    }

    static AttributedString liveTail(String text) {
        return new AttributedString(text, LIVE_TAIL);
    }

    static AttributedString status(String text) {
        return new AttributedString(text, STATUS);
    }

    private static AttributedStyle styleFor(LineKind kind) {
        return switch (kind) {
            case USER -> USER;
            case ASSISTANT -> ASSISTANT;
            case TOOL -> TOOL;
            case CARD -> CARD;
            case SYSTEM -> SYSTEM;
            case ERROR -> ERROR;
            case HINT -> HINT;
        };
    }
}
