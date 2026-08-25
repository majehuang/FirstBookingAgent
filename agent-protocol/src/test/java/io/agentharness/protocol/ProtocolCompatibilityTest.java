package io.agentharness.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("协议向前兼容")
class ProtocolCompatibilityTest {

    private static final Instant AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    @DisplayName("未知消息类型降级为 UNKNOWN，而不是让整条流断掉")
    void 未知type不抛异常() {
        String futureMessage = """
                {"msgSeq":1,"replyId":"r1","blockId":"b1","role":"ASSISTANT",
                 "type":"HOLOGRAM","fallbackText":"[全息投影] 三家酒店",
                 "payload":{},"createdAt":"2026-08-24T10:00:00Z"}
                """;

        ClientMessage message = Json.read(futureMessage, ClientMessage.class);

        assertThat(message.type()).isEqualTo(MessageType.UNKNOWN);
        // 关键：降级之后正文还在，老客户端能把它当文本渲染出来
        assertThat(message.fallbackText()).isEqualTo("[全息投影] 三家酒店");
        assertThat(message.type().isTextLike()).isTrue();
    }

    @Test
    void 未知role同样降级() {
        String futureMessage = """
                {"msgSeq":1,"replyId":"r1","blockId":"b1","role":"SUBAGENT",
                 "type":"TEXT","fallbackText":"子任务回报","payload":{},
                 "createdAt":"2026-08-24T10:00:00Z"}
                """;

        ClientMessage message = Json.read(futureMessage, ClientMessage.class);

        assertThat(message.role()).isEqualTo(MessageRole.UNKNOWN);
        assertThat(message.fromUser()).isFalse();
    }

    @Test
    void 未知字段被忽略() {
        String futureMessage = """
                {"msgSeq":1,"replyId":"r1","blockId":"b1","role":"ASSISTANT","type":"TEXT",
                 "fallbackText":"hi","payload":{},"createdAt":"2026-08-24T10:00:00Z",
                 "priority":"HIGH","ttlSeconds":30}
                """;

        assertThatCode(() -> Json.read(futureMessage, ClientMessage.class)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("富消息必须带可用的 fallbackText —— 否则降级后是一行空白")
    void 富消息的空fallback被拒绝() {
        for (MessageType rich : new MessageType[]{MessageType.CARD, MessageType.IMAGE, MessageType.AUDIO}) {
            assertThatThrownBy(() -> new ClientMessage(1, "r1", "b1", MessageRole.ASSISTANT,
                    rich, "   ", Map.of(), AT))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("fallbackText 不能为空");
        }
    }

    @Test
    void 结构性消息允许空fallback() {
        // TEXT_END 只是块边界，没有正文
        assertThatCode(() -> ClientMessage.textEnd(1, "r1", "b1", AT)).doesNotThrowAnyException();
    }

    @Test
    void 协议版本可查() {
        assertThat(Protocol.VERSION).isEqualTo("1.1");
    }
}
