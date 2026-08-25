package io.agentharness.protocol;

import java.time.Instant;
import java.util.Map;

/**
 * 用户可见消息。**这是消息表里那一行的内存形态**，不是推理事件。
 *
 * <p>关键约束（见 开发规划.md B 节）：
 * <ul>
 *   <li>{@code msgSeq} 每 session 独立、单调、无洞 —— 客户端的空窗判定完全依赖它（INV-10）</li>
 *   <li>{@code fallbackText} 必填 —— 客户端不认识 {@code type} 时按文本降级</li>
 *   <li>{@code payload} 构造时深拷贝为不可变 Map —— 消息一旦生成不再变化，卡片内容已冻结</li>
 *   <li><b>{@code role} 覆盖用户自己的消息</b>：用户发的话也落库、也进流，
 *       客户端收到推送后才回显。这样一个会话里只有一套顺序来源</li>
 * </ul>
 */
public record ClientMessage(
        long msgSeq,
        String replyId,
        String blockId,
        MessageRole role,
        MessageType type,
        String fallbackText,
        Map<String, Object> payload,
        Instant createdAt) {

    private static final int MAX_FALLBACK_LENGTH = 8192;
    /** 与消息表的列宽一致。上游 id 的长度不由我们控制，所以必须在入口处就卡住。 */
    public static final int MAX_BLOCK_ID_LENGTH = 128;
    public static final int MAX_REPLY_ID_LENGTH = 64;

    public ClientMessage {
        Validate.positive(msgSeq, "msgSeq");
        Validate.notBlank(replyId, "replyId");
        Validate.notBlank(blockId, "blockId");
        Validate.maxLength(replyId, MAX_REPLY_ID_LENGTH, "replyId");
        Validate.maxLength(blockId, MAX_BLOCK_ID_LENGTH, "blockId");
        Validate.required(role, "role");
        Validate.required(type, "type");
        Validate.notNull(fallbackText, "fallbackText");
        Validate.maxLength(fallbackText, MAX_FALLBACK_LENGTH, "fallbackText");
        // 富消息的 fallbackText 不能为空：降级路径上它是唯一剩下的东西，
        // 空的话老客户端或不支持该类型的客户端会看到一行空白，比看到粗糙的文本更糟
        if (type != null && type.isRich() && fallbackText.isBlank()) {
            throw new ProtocolException(type + " 是富消息，fallbackText 不能为空 —— "
                    + "客户端不支持该类型时只剩它可渲染");
        }
        Validate.required(createdAt, "createdAt");
        // 深冻结：只冻顶层的话，嵌套 items 仍可被外部引用改掉，
        // 而消息表里那一行早就写完了 —— 内存与库会悄悄对不上
        payload = Immutables.freeze(payload);
    }

    /** 用户发的一条消息。**由服务端落库后推流，客户端不本地回显。** */
    public static ClientMessage userText(long msgSeq, String replyId, String blockId,
                                         String text, Instant at) {
        return new ClientMessage(msgSeq, replyId, blockId, MessageRole.USER,
                MessageType.TEXT, text, Map.of(), at);
    }

    public static ClientMessage textDelta(long msgSeq, String replyId, String blockId,
                                          String text, Instant at) {
        return new ClientMessage(msgSeq, replyId, blockId, MessageRole.ASSISTANT,
                MessageType.TEXT_DELTA, text, Map.of(), at);
    }

    public static ClientMessage textEnd(long msgSeq, String replyId, String blockId, Instant at) {
        return new ClientMessage(msgSeq, replyId, blockId, MessageRole.ASSISTANT,
                MessageType.TEXT_END, "", Map.of(), at);
    }

    public static ClientMessage system(long msgSeq, String replyId, String text, Instant at) {
        return new ClientMessage(msgSeq, replyId, "sys", MessageRole.SYSTEM,
                MessageType.SYSTEM, text, Map.of(), at);
    }

    public static ClientMessage error(long msgSeq, String replyId, String reason, Instant at) {
        return new ClientMessage(msgSeq, replyId, "err", MessageRole.SYSTEM,
                MessageType.ERROR, reason, Map.of(), at);
    }

    public boolean fromUser() {
        return role == MessageRole.USER;
    }

    /** 同一个文本块的相邻 delta 才允许合并 —— 合批时的唯一判据。 */
    public boolean mergeableWith(ClientMessage other) {
        return other != null
                && type == MessageType.TEXT_DELTA
                && other.type == MessageType.TEXT_DELTA
                && role == other.role
                && replyId.equals(other.replyId)
                && blockId.equals(other.blockId);
    }

    /** 合并相邻 delta，序号取后者（后者更靠近水位）。不修改任何一方。 */
    public ClientMessage mergeWith(ClientMessage next) {
        if (!mergeableWith(next)) {
            throw new ProtocolException("不可合并的消息：" + blockId + " vs "
                    + (next == null ? "null" : next.blockId()));
        }
        return new ClientMessage(next.msgSeq, replyId, blockId, role, MessageType.TEXT_DELTA,
                fallbackText + next.fallbackText, Map.of(), next.createdAt);
    }

    public Object payloadValue(String key) {
        return payload.get(key);
    }
}
