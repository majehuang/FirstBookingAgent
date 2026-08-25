package io.agentharness.tui;

import io.agentharness.protocol.SessionRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TuiConfigTest {

    private static final SessionRef SESSION = SessionRef.of("u1", "s1");

    @Test
    void 默认配置带历史文件与200ms状态刷新() {
        TuiConfig config = TuiConfig.of(SESSION);

        assertThat(config.statusTick()).isEqualTo(Duration.ofMillis(200));
        assertThat(config.forcePlain()).isFalse();
        assertThat(config.historyFile()).isNotNull();
    }

    @Test
    void statusTick为null时退回默认值_而不是让定时器炸掉() {
        TuiConfig config = new TuiConfig(SESSION, Path.of("/tmp/h"), false, null);

        assertThat(config.statusTick()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 变更返回新实例() {
        TuiConfig original = TuiConfig.of(SESSION);
        TuiConfig plain = original.withForcePlain(true);
        TuiConfig switched = original.withSession(SessionRef.of("u1", "s2"));

        assertThat(original.forcePlain()).isFalse();
        assertThat(plain.forcePlain()).isTrue();
        assertThat(switched.session().sessionId()).isEqualTo("s2");
        assertThat(original.session().sessionId()).isEqualTo("s1");
    }

    @Test
    void session为null时拒绝构造() {
        assertThatThrownBy(() -> new TuiConfig(null, null, false, null))
                .isInstanceOf(NullPointerException.class);
    }
}
