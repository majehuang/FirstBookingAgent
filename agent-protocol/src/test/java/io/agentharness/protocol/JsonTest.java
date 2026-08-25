package io.agentharness.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void 消息往返序列化保真_含冻结的卡片payload() {
        ClientMessage original = new ClientMessage(42, "r1", "b1", MessageRole.ASSISTANT,
                MessageType.CARD, "3 家酒店", Map.of("city", "北京", "count", 3),
                Instant.parse("2026-08-23T10:00:00Z"));

        ClientMessage restored = Json.read(Json.write(original), ClientMessage.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void 未知字段被忽略_保证协议向前兼容() {
        String futureVersion = """
                {"msgSeq":1,"replyId":"r1","blockId":"b1","role":"ASSISTANT","type":"TEXT_DELTA",
                 "fallbackText":"hi","payload":{},"createdAt":"2026-08-23T10:00:00Z",
                 "someFieldFromV2":"客户端不认识这个"}
                """;

        ClientMessage m = Json.read(futureVersion, ClientMessage.class);

        assertThat(m.fallbackText()).isEqualTo("hi");
    }

    @Test
    void 用户消息往返保真() {
        ClientMessage user = ClientMessage.userText(7, "r1", "u-i1", "订一间酒店",
                Instant.parse("2026-08-23T10:00:00Z"));

        assertThat(Json.read(Json.write(user), ClientMessage.class)).isEqualTo(user);
    }

    @Test
    void 非法JSON抛协议异常而不是Jackson异常() {
        assertThatThrownBy(() -> Json.read("{ 这不是 json", ClientMessage.class))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("反序列化失败");
    }
}
