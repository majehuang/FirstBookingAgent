package io.agentharness.tui.render;

import io.agentharness.protocol.TurnPhase;
import io.agentharness.tui.state.UiState;

import java.time.Duration;
import java.time.Instant;

/**
 * 状态行渲染。纯函数：同一个 UiState 永远渲染出同一行，可以直接对着断言写测试。
 *
 * <p>布局是「左侧状态 + 右侧键位提示」，宽度不够时**先丢右侧提示，再截断左侧** ——
 * 窄终端下用户更需要知道当前是什么状态，而不是快捷键。
 */
public final class StatusLine {

    private static final String KEY_HINTS = "^C 停止  ^D 退出  /help";
    private static final String SEPARATOR = "  ·  ";
    private static final int MIN_WIDTH_FOR_HINTS = 60;

    private StatusLine() {
    }

    public static String render(UiState state, Instant now, int width) {
        String left = left(state, now);
        if (width <= 0) {
            return left;
        }
        if (width < MIN_WIDTH_FOR_HINTS) {
            return truncate(left, width);
        }

        int gap = width - left.length() - KEY_HINTS.length();
        if (gap < 2) {
            return truncate(left, width);
        }
        return left + " ".repeat(gap) + KEY_HINTS;
    }

    private static final int PENDING_PREVIEW_LENGTH = 16;

    private static String left(UiState state, Instant now) {
        StringBuilder sb = new StringBuilder();

        // 用户按下回车后不再本地回显，自己的话要等服务端推回来。
        // 这段空窗必须有反馈，否则看起来像卡住了。
        if (state.hasPendingInput()) {
            sb.append("⋯ 投递中 ").append(preview(state.pendingInput()));
            sb.append(SEPARATOR).append(state.session().shortLabel());
            sb.append(SEPARATOR).append(state.backendName());
            return sb.toString();
        }

        sb.append(icon(state.phase())).append(' ').append(phaseLabel(state));

        Duration elapsed = state.turnElapsed(now);
        if (!elapsed.isZero() && state.control().turnActive()) {
            sb.append(' ').append(formatElapsed(elapsed));
        }

        sb.append(SEPARATOR).append(state.session().shortLabel());
        sb.append(SEPARATOR).append("seq ").append(state.lastMsgSeq());
        sb.append(SEPARATOR).append(state.backendName());

        if (state.gapCount() > 0) {
            sb.append(SEPARATOR).append("空窗 ").append(state.gapCount());
        }
        return sb.toString();
    }

    private static String phaseLabel(UiState state) {
        if (!state.connection().canSend()) {
            return state.connection().label();
        }
        return state.phase().label();
    }

    private static String icon(TurnPhase phase) {
        return switch (phase) {
            case IDLE, DONE -> "⏵";
            case QUEUED -> "⋯";
            case THINKING -> "◐";
            case CALLING_TOOL -> "⚒";
            case WRITING -> "✎";
            case STOPPING -> "⏹";
            case FAILED -> "✗";
        };
    }

    static String preview(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= PENDING_PREVIEW_LENGTH
                ? "「" + single + "」"
                : "「" + single.substring(0, PENDING_PREVIEW_LENGTH) + "…」";
    }

    static String formatElapsed(Duration elapsed) {
        long totalSeconds = elapsed.toSeconds();
        if (totalSeconds < 60) {
            return String.format("%.1fs", elapsed.toMillis() / 1000.0);
        }
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static String truncate(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        return width <= 1 ? text.substring(0, width) : text.substring(0, width - 1) + "…";
    }
}
