package io.agentharness.tui.render;

import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.tui.state.UiState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 状态行渲染。纯函数：同一个 UiState 永远渲染出同一行，可以直接对着断言写测试。
 *
 * <p>布局是「左侧状态 + 右侧键位提示」。宽度不够时<b>按优先级逐段丢弃</b>，
 * 而不是从右边一刀截断 —— 截断会先砍掉排在最后的段，而那纯粹是书写顺序决定的，
 * 与它值不值得看毫无关系。窄终端上最该留下的是当前阶段和在途的控制指令。
 *
 * <p>控制状态之所以摆在这里：控制通道（快照 + 水位 + 重放）是本项目最难自证正确的一条链路，
 * 它出错的典型表现是重连后 {@code turnActive} 翻转，而那在滚动区里<b>看不见</b>。
 * 把 activeReplyId 与 ctrlId 水位摆在眼前，这类问题才有可能被当场发现。
 */
public final class StatusLine {

    private static final String KEY_HINTS = "^C 停止  ^D 退出  /help";
    private static final String SEPARATOR = "  ·  ";
    private static final int MIN_WIDTH_FOR_HINTS = 60;
    private static final int PENDING_PREVIEW_LENGTH = 16;

    /** ctrlId 尾部保留的长度。Redis 条目 id 形如 {@code 1724502938471-0}，尾部足以区分。 */
    private static final int CTRL_TAIL = 8;

    private StatusLine() {
    }

    /**
     * 一个可丢弃的状态段。
     *
     * @param priority 数字越大越先被丢掉
     */
    private record Segment(String text, int priority) {
    }

    public static String render(UiState state, Instant now, int width) {
        if (width <= 0) {
            return join(segments(state, now));
        }

        String left = fit(segments(state, now), width);
        if (width < MIN_WIDTH_FOR_HINTS) {
            return left;
        }

        int gap = width - DisplayWidth.of(left) - DisplayWidth.of(KEY_HINTS);
        if (gap < 2) {
            return left;
        }
        return left + " ".repeat(gap) + KEY_HINTS;
    }

    /** 从优先级最低的段开始丢，直到放得下。头段永远不丢。 */
    private static String fit(List<Segment> segments, int width) {
        List<Segment> kept = new ArrayList<>(segments);
        String rendered = join(kept);

        while (DisplayWidth.of(rendered) > width && kept.size() > 1) {
            Segment victim = kept.stream()
                    .skip(1)
                    .max(Comparator.comparingInt(Segment::priority))
                    .orElseThrow();
            kept.remove(victim);
            rendered = join(kept);
        }
        // 只剩头段还是放不下，才不得不截断
        return DisplayWidth.truncate(rendered, width);
    }

    private static String join(List<Segment> segments) {
        return segments.stream().map(Segment::text).collect(Collectors.joining(SEPARATOR));
    }

    private static List<Segment> segments(UiState state, Instant now) {
        List<Segment> segments = new ArrayList<>();

        // 用户按下回车后不再本地回显，自己的话要等服务端推回来。
        // 这段空窗必须有反馈，否则看起来像卡住了
        if (state.hasPendingInput()) {
            segments.add(new Segment("⋯ 投递中 " + preview(state.pendingInput()), 0));
            segments.add(new Segment(state.session().shortLabel(), 2));
            segments.add(new Segment(state.backendName(), 6));
            return segments;
        }

        segments.add(new Segment(head(state, now), 0));
        segments.addAll(control(state));
        segments.add(new Segment(state.session().shortLabel(), 2));
        segments.add(new Segment("seq " + state.lastMsgSeq(), 4));
        segments.add(new Segment(state.backendName(), 6));

        if (state.gapCount() > 0) {
            segments.add(new Segment("空窗 " + state.gapCount(), 2));
        }
        return segments;
    }

    private static String head(UiState state, Instant now) {
        StringBuilder sb = new StringBuilder()
                .append(icon(state.phase())).append(' ').append(phaseLabel(state));

        Duration elapsed = state.turnElapsed(now);
        if (!elapsed.isZero() && state.control().turnActive()) {
            sb.append(' ').append(formatElapsed(elapsed));
        }
        return sb.toString();
    }

    /** 控制通道的可见状态。 */
    private static List<Segment> control(UiState state) {
        ControlFrame frame = state.control();
        List<Segment> segments = new ArrayList<>();

        // 已发出但服务端还没认下的控制指令。优先级最高：
        // 用户此刻最想知道的就是"我刚按的那下到底有没有生效"
        if (state.hasPendingControl()) {
            segments.add(new Segment("⇱ " + state.pendingControl() + " 已发出", 1));
        }

        // 没有 turn 在跑却不让输入 —— 这是卡住了，不是正常状态。
        // turn 进行中不显示：那时输入本就该锁着，摆出来只是噪音
        if (!frame.inputAllowed() && !frame.turnActive() && state.connection().canSend()) {
            segments.add(new Segment("⌾ 输入锁定", 1));
        }

        if (frame.activeReplyId() != null) {
            segments.add(new Segment("⌁ " + frame.activeReplyId(), 3));
        }
        if (frame.ctrlId() != null) {
            segments.add(new Segment("ctrl " + shortCtrlId(frame.ctrlId()), 5));
        }
        return segments;
    }

    /** 只留尾部：条目 id 的前缀是毫秒时间戳，同一会话内几乎不变，看尾部才分得出先后。 */
    static String shortCtrlId(String ctrlId) {
        return ctrlId.length() <= CTRL_TAIL ? ctrlId
                : "…" + ctrlId.substring(ctrlId.length() - CTRL_TAIL);
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
}
