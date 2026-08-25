package io.agentharness.trace;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 追踪行的格式化。纯函数 —— 给定同样的入参永远得到同一行，可以直接断言。
 *
 * <p>格式固定为定宽前缀 + 自由文本，因为最常见的用法是<b>两个终端并排看</b>：
 * 客户端一个、worker 一个，靠 sessionId 对齐成一条完整链路。列对不齐就得逐行找。
 *
 * <pre>
 * [10:32:14.802] tui     → inbox  s-local      i-3f2a {"kind":"MESSAGE",...}
 * [10:32:14.815] worker  ✦ ready  s-local      执行权已抢到
 * </pre>
 */
public final class TraceFormat {

    /**
     * 原始载荷的长度上限。
     *
     * <p>超了会截断并<b>显式标注被截掉多少</b>。静默截断在这里是有害的：
     * 追踪的用途就是看清原始内容，看到一半却不知道自己只看到一半，
     * 比没看到更糟。真需要完整载荷时应当去查 outbox 本身。
     */
    static final int MAX_DETAIL = 512;

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final int COMPONENT_WIDTH = 7;
    private static final int SESSION_WIDTH = 12;

    private TraceFormat() {
    }

    public static String line(Instant at, String component, TraceStage stage,
                              String sessionId, String detail) {
        return '[' + CLOCK.format(at) + "] "
                + pad(component, COMPONENT_WIDTH)
                + pad(stage.label(), TraceStage.LABEL_WIDTH)
                + pad(sessionId == null ? "-" : sessionId, SESSION_WIDTH)
                + clamp(detail);
    }

    /** 超长的原始载荷截断并标注。 */
    static String clamp(String detail) {
        if (detail == null) {
            return "";
        }
        // CRLF 要先整体换掉，否则会变成两个空格
        String single = detail.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (single.length() <= MAX_DETAIL) {
            return single;
        }
        return single.substring(0, MAX_DETAIL) + "…（还有 " + (single.length() - MAX_DETAIL) + " 字符）";
    }

    /** 右侧补空格到指定宽度；超宽的不截断 —— 对齐没有内容重要。 */
    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text + " ";
        }
        return text + " ".repeat(width - text.length() + 1);
    }
}
