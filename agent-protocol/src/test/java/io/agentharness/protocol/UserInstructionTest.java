package io.agentharness.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserInstructionTest {

    private static final Instant AT = Instant.parse("2026-08-23T10:00:00Z");

    @Test
    void 消息指令走QUEUED_控制指令走IMMEDIATE() {
        assertThat(UserInstruction.message("i1", "订酒店", AT).priority())
                .isEqualTo(DeliveryPriority.QUEUED);
        assertThat(UserInstruction.cancel("i2", "r1", AT).priority())
                .isEqualTo(DeliveryPriority.IMMEDIATE);
    }

    @Test
    void 消息指令必须有正文() {
        assertThatThrownBy(() -> UserInstruction.message("i1", "  ", AT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("text");
    }

    @Test
    void 控制指令必须指明targetReplyId_否则会误杀新turn() {
        assertThatThrownBy(() -> UserInstruction.cancel("i1", null, AT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("targetReplyId");
    }

    @Test
    void instructionId为空时拒绝_幂等键不能由服务端补() {
        assertThatThrownBy(() -> UserInstruction.message("", "hi", AT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("instructionId");
    }
}
