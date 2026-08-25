package io.agentharness.tui.loopback;

import java.util.List;
import java.util.Map;

/**
 * 脚本化回复的一步。密封类型，由 {@link ScriptedReply} 生成、
 * 由 {@link LoopbackBackend} 翻译成协议消息。
 *
 * <p>这样切开是为了让"回复长什么样"完全可测（纯数据），
 * 而"怎么按时序吐出去"留在后端里（有副作用）。
 */
public sealed interface ReplyStep {

    /** 一段文本，会被拆成更小的块模拟逐字生成。 */
    record Text(String content) implements ReplyStep {
    }

    /** 工具开始执行。 */
    record ToolCall(String tool, Map<String, Object> args) implements ReplyStep {

        public ToolCall {
            args = Map.copyOf(args);
        }
    }

    /** 工具结束，summary 是给用户看的一句话。 */
    record ToolResult(String summary) implements ReplyStep {
    }

    /** 富消息卡片。内容在这一刻冻结，之后不再变化。 */
    record Card(String title, List<Map<String, Object>> items, String dataAsOf) implements ReplyStep {

        public Card {
            items = items.stream().map(Map::copyOf).toList();
        }
    }

    /** 本轮出错。 */
    record Failure(String reason) implements ReplyStep {
    }
}
