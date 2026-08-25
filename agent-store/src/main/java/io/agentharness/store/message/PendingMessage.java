package io.agentharness.store.message;

import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.Immutables;
import io.agentharness.protocol.MessageType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一条<b>尚未分配序号</b>的消息。
 *
 * <p>序号由消息表在事务内分配 —— 调用方拿不到、也不需要拿"先分配好的序号"。
 * 这样设计是为了让「分配」与「写入」无法被拆开：
 * 拆开之后写入失败就会烧掉序号，在消息序列里留下永久空洞（INV-10）。
 */
public record PendingMessage(
        String replyId,
        String blockId,
        MessageRole role,
        MessageType type,
        String fallbackText,
        Map<String, Object> payload,
        Instant createdAt) {

    public PendingMessage {
        Objects.requireNonNull(replyId, "replyId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        fallbackText = fallbackText == null ? "" : fallbackText;
        // 与 ClientMessage 同一套深冻结：草稿阶段就冻住，
        // 免得落库前还有人能改到卡片内容
        payload = Immutables.freeze(payload);
    }
}
