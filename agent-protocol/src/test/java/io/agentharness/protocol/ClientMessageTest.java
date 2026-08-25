package io.agentharness.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientMessageTest {

    private static final Instant AT = Instant.parse("2026-08-23T10:00:00Z");

    @Nested
    @DisplayName("构造校验")
    class Construction {

        @Test
        void 非法字段一律构造失败() {
            assertThatThrownBy(() -> ClientMessage.textDelta(0, "r1", "b1", "x", AT))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("msgSeq");

            assertThatThrownBy(() -> ClientMessage.textDelta(1, " ", "b1", "x", AT))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("replyId");

            assertThatThrownBy(() -> new ClientMessage(1, "r1", "b1", MessageRole.ASSISTANT, MessageType.CARD, null, Map.of(), AT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("fallbackText");
        }

        @Test
        void payload_构造后不可变_外部修改原始Map不影响消息() {
            Map<String, Object> mutable = new HashMap<>();
            mutable.put("city", "北京");
            ClientMessage card = new ClientMessage(1, "r1", "b1", MessageRole.ASSISTANT,
                    MessageType.CARD, "3 家酒店", mutable, AT);

            mutable.put("city", "上海");

            assertThat(card.payloadValue("city")).isEqualTo("北京");
            assertThatThrownBy(() -> card.payload().put("k", "v"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void payload_为null时退化为空Map() {
            ClientMessage m = new ClientMessage(1, "r1", "b1", MessageRole.ASSISTANT,
                    MessageType.IMAGE, "[图片]", null, AT);
            assertThat(m.payload()).isEmpty();
        }
    }

    @Nested
    @DisplayName("用户消息")
    class UserMessages {

        @Test
        void 用户消息是完整文本_不是增量() {
            ClientMessage message = ClientMessage.userText(1, "r1", "u-i1", "订一间酒店", AT);

            assertThat(message.role()).isEqualTo(MessageRole.USER);
            assertThat(message.type()).isEqualTo(MessageType.TEXT);
            assertThat(message.fromUser()).isTrue();
            assertThat(message.fallbackText()).isEqualTo("订一间酒店");
        }

        @Test
        void 助手与系统消息的role各就各位() {
            assertThat(ClientMessage.textDelta(1, "r1", "b1", "x", AT).role())
                    .isEqualTo(MessageRole.ASSISTANT);
            assertThat(ClientMessage.textEnd(1, "r1", "b1", AT).role())
                    .isEqualTo(MessageRole.ASSISTANT);
            assertThat(ClientMessage.system(1, "r1", "已接管", AT).role())
                    .isEqualTo(MessageRole.SYSTEM);
            assertThat(ClientMessage.error(1, "r1", "超时", AT).role())
                    .isEqualTo(MessageRole.SYSTEM);
        }

        @Test
        void 跨role不可合并_用户的话不会被并进助手的段落() {
            ClientMessage user = ClientMessage.userText(1, "r1", "b1", "问题", AT);
            ClientMessage assistant = ClientMessage.textDelta(2, "r1", "b1", "回答", AT);

            assertThat(user.mergeableWith(assistant)).isFalse();
            assertThat(assistant.mergeableWith(user)).isFalse();
        }
    }

    @Nested
    @DisplayName("相邻 delta 合并")
    class Merging {

        @Test
        void 同一blockId的相邻delta可合并且不修改原对象() {
            ClientMessage first = ClientMessage.textDelta(10, "r1", "b1", "你好", AT);
            ClientMessage second = ClientMessage.textDelta(11, "r1", "b1", "，世界", AT.plusMillis(80));

            ClientMessage merged = first.mergeWith(second);

            assertThat(merged.fallbackText()).isEqualTo("你好，世界");
            assertThat(merged.msgSeq()).isEqualTo(11);
            assertThat(first.fallbackText()).isEqualTo("你好");
        }

        @Test
        void 跨blockId或跨replyId不可合并() {
            ClientMessage a = ClientMessage.textDelta(10, "r1", "b1", "A", AT);
            ClientMessage otherBlock = ClientMessage.textDelta(11, "r1", "b2", "B", AT);
            ClientMessage otherReply = ClientMessage.textDelta(11, "r2", "b1", "B", AT);

            assertThat(a.mergeableWith(otherBlock)).isFalse();
            assertThat(a.mergeableWith(otherReply)).isFalse();
            assertThatThrownBy(() -> a.mergeWith(otherBlock)).isInstanceOf(ProtocolException.class);
        }

        @Test
        void 非TEXT_DELTA类型不可合并_卡片与文本混流时不能被吃掉() {
            ClientMessage delta = ClientMessage.textDelta(10, "r1", "b1", "A", AT);
            ClientMessage card = new ClientMessage(11, "r1", "b1", MessageRole.ASSISTANT,
                    MessageType.CARD, "3 家酒店", Map.of(), AT);

            assertThat(delta.mergeableWith(card)).isFalse();
            assertThat(card.mergeableWith(delta)).isFalse();
        }
    }
}
