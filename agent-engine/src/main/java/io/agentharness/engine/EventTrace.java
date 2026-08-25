package io.agentharness.engine;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.stream.Collectors;

/**
 * 把一个 {@link Event} 压成一行，供链路追踪打印。纯函数。
 *
 * <p>刻意<b>不</b>用 {@code event.toString()}：上游的默认实现会把整条消息连同
 * 全部元数据摊开，一个 step 就刷掉半屏，而追踪的用处恰恰是能一眼扫过一整轮。
 *
 * <p>与 {@link EventMapper} 分开，是因为两者的失败后果完全不同 ——
 * 映射错了用户会看到错的消息，这里错了只是日志难看一点。
 * 混在一起会诱使人为了日志好看去改映射。
 */
public final class EventTrace {

    /** 文本片段在追踪里的最大长度。 */
    private static final int SNIPPET = 32;

    private EventTrace() {
    }

    public static String describe(Event event) {
        if (event == null) {
            return "null";
        }
        String head = String.valueOf(event.getType());
        Msg message = event.getMessage();
        if (message == null || message.getContent() == null || message.getContent().isEmpty()) {
            return head;
        }
        return head + " " + message.getContent().stream()
                .map(EventTrace::describeBlock)
                .collect(Collectors.joining(" "));
    }

    private static String describeBlock(ContentBlock block) {
        return switch (block) {
            case TextBlock text -> "text=" + quote(text.getText());
            case ThinkingBlock thinking -> "thinking=" + quote(thinking.getThinking());
            case ToolUseBlock use -> "tool=" + use.getName() + " id=" + use.getId();
            case ToolResultBlock result -> "result=" + result.getName() + " id=" + result.getId();
            case null -> "null";
            default -> block.getClass().getSimpleName();
        };
    }

    /** 截断并转义换行 —— 追踪必须一条事件一行，否则并排看时会错位。 */
    static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        String single = text.replace("\n", "\\n").replace("\r", "");
        String cut = single.length() <= SNIPPET ? single : single.substring(0, SNIPPET) + "…";
        return '"' + cut + '"';
    }
}
