package io.agentharness.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("客户端能力协商")
class ClientCapabilitiesTest {

    private static final Instant AT = Instant.parse("2026-08-24T10:00:00Z");

    private ClientMessage card() {
        return new ClientMessage(1, "r1", "b1", MessageRole.ASSISTANT, MessageType.CARD,
                "为你找到 3 家酒店", Map.of("title", "3 家酒店", "items", java.util.List.of()), AT);
    }

    @Test
    @DisplayName("缺省能力只保证文本 —— 保守的默认值，新类型必须显式声明才下发")
    void 默认能力不含富消息() {
        ClientCapabilities defaults = ClientCapabilities.defaults();

        assertThat(defaults.supports(MessageType.TEXT)).isTrue();
        assertThat(defaults.supports(MessageType.TEXT_DELTA)).isTrue();
        assertThat(defaults.supports(MessageType.ERROR)).isTrue();
        assertThat(defaults.supports(MessageType.CARD)).isFalse();
        assertThat(defaults.supports(MessageType.IMAGE)).isFalse();
    }

    @Test
    void 不支持的类型被压成纯文本_内容不丢只是失去结构() {
        ClientMessage degraded = ClientCapabilities.defaults().degrade(card());

        assertThat(degraded.type()).isEqualTo(MessageType.TEXT);
        assertThat(degraded.fallbackText()).isEqualTo("为你找到 3 家酒店");
        assertThat(degraded.payload()).isEmpty();
        // 序号与归属不变：降级不能打乱顺序，否则空窗判定会失效
        assertThat(degraded.msgSeq()).isEqualTo(1);
        assertThat(degraded.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(degraded.replyId()).isEqualTo("r1");
    }

    @Test
    void 支持的类型原样通过_不产生新对象带来的开销() {
        ClientMessage original = card();

        assertThat(ClientCapabilities.full().degrade(original)).isSameAs(original);
    }

    @Test
    void 显式声明部分能力() {
        ClientCapabilities partial = ClientCapabilities.of("1.1",
                EnumSet.of(MessageType.TEXT, MessageType.TEXT_DELTA, MessageType.CARD));

        assertThat(partial.degrade(card()).type()).isEqualTo(MessageType.CARD);
        assertThat(partial.supports(MessageType.IMAGE)).isFalse();
    }

    @Test
    void 空能力集退回默认值_而不是把客户端饿死() {
        ClientCapabilities empty = ClientCapabilities.of("1.1", Set.of());

        assertThat(empty.supports(MessageType.TEXT)).isTrue();
    }

    @Test
    void 能力声明不可变() {
        EnumSet<MessageType> mutable = EnumSet.of(MessageType.TEXT);
        ClientCapabilities capabilities = ClientCapabilities.of("1.1", mutable);

        mutable.add(MessageType.CARD);

        assertThat(capabilities.supports(MessageType.CARD)).isFalse();
    }
}
