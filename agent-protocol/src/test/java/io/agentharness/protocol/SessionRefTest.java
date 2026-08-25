package io.agentharness.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRefTest {

    @Test
    void 长id在状态行里截断展示() {
        assertThat(SessionRef.of("u1", "short").shortLabel()).isEqualTo("short");
        assertThat(SessionRef.of("u1", "0123456789abcdef").shortLabel()).isEqualTo("0123456789ab…");
    }

    @Test
    void 空id拒绝构造() {
        assertThatThrownBy(() -> SessionRef.of("", "s1")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> SessionRef.of("u1", null)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void 超长id拒绝构造() {
        String tooLong = "x".repeat(129);
        assertThatThrownBy(() -> SessionRef.of("u1", tooLong))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("超长");
    }
}
