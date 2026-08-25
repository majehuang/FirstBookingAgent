package io.agentharness.store.datasource;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceConfigTest {

    @Test
    void 缺省超时被补上_而不是留一个0导致立刻超时() {
        DataSourceConfig config = new DataSourceConfig("jdbc:postgresql://x/agent", "u", "p",
                4, null, Duration.ZERO, Duration.ofSeconds(-1));

        assertThat(config.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.validationTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void 非法参数拒绝构造() {
        assertThatThrownBy(() -> DataSourceConfig.of("", "u", "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl");

        assertThatThrownBy(() -> DataSourceConfig.of("jdbc:postgresql://x/agent", "u", "p").withMaxPoolSize(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPoolSize");
    }

    @Test
    void 变更返回新实例() {
        DataSourceConfig original = DataSourceConfig.of("jdbc:postgresql://x/agent", "u", "p");
        DataSourceConfig bigger = original.withMaxPoolSize(32);

        assertThat(original.maxPoolSize()).isEqualTo(10);
        assertThat(bigger.maxPoolSize()).isEqualTo(32);
    }
}
