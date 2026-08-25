package io.agentharness.engine;

import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;

import java.util.Map;
import java.util.Objects;

/**
 * 尚未分配序号的客户端消息。
 *
 * <p>把"内容长什么样"和"序号是多少"切开，是为了让 {@link EventMapper} 保持纯函数：
 * 序号要访问数据库，一旦混进映射逻辑，这段最容易出错的代码就没法用单测覆盖了。
 *
 * @param blockKey 同一个文本块的标识。相邻且同 blockKey 的 delta 可以安全合并
 */
public record MessageDraft(String blockKey, MessageType type, String text, Map<String, Object> payload) {

    public MessageDraft {
        Objects.requireNonNull(blockKey, "blockKey");
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /**
     * 归属方。
     *
     * <p>引擎产出的只有助手内容与系统提示 —— 用户消息不走这条路，
     * 它由投递侧与 Worker 各自幂等地落库。
     */
    public MessageRole role() {
        return type == MessageType.SYSTEM || type == MessageType.ERROR
                ? MessageRole.SYSTEM
                : MessageRole.ASSISTANT;
    }

    public static MessageDraft text(String blockKey, String text) {
        return new MessageDraft(blockKey, MessageType.TEXT_DELTA, text, Map.of());
    }

    public static MessageDraft endOfBlock(String blockKey) {
        return new MessageDraft(blockKey, MessageType.TEXT_END, "", Map.of());
    }
}
