package io.agentharness.store.schema;

import io.agentharness.store.StoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresUrlTest {

    @Test
    void 解析出库名并换成维护库() {
        PostgresUrl url = PostgresUrl.parse("jdbc:postgresql://localhost:5432/agent");

        assertThat(url.database()).isEqualTo("agent");
        assertThat(url.maintenanceUrl()).isEqualTo("jdbc:postgresql://localhost:5432/postgres");
    }

    @Test
    void 查询参数被保留_否则ssl等设置会在建库那步丢掉() {
        PostgresUrl url = PostgresUrl.parse("jdbc:postgresql://db:5432/agent?ssl=true&connectTimeout=5");

        assertThat(url.database()).isEqualTo("agent");
        assertThat(url.maintenanceUrl())
                .isEqualTo("jdbc:postgresql://db:5432/postgres?ssl=true&connectTimeout=5");
    }

    @Test
    void 不带端口也能解析() {
        assertThat(PostgresUrl.parse("jdbc:postgresql://localhost/agent").database()).isEqualTo("agent");
    }

    @Test
    void 识别维护库自身_拒绝在它上面建表() {
        assertThat(PostgresUrl.parse("jdbc:postgresql://localhost:5432/postgres").isMaintenanceDatabase())
                .isTrue();
        assertThat(PostgresUrl.parse("jdbc:postgresql://localhost:5432/agent").isMaintenanceDatabase())
                .isFalse();
    }

    @Test
    @DisplayName("CREATE DATABASE 不能绑参数，库名校验是安全边界而不是格式检查")
    void 库名含注入片段时拒绝() {
        assertThatThrownBy(() -> PostgresUrl
                .parse("jdbc:postgresql://localhost:5432/agent\";DROP DATABASE prod;--")
                .assertSafeIdentifier())
                .isInstanceOf(StoreException.class)
                .hasMessageContaining("非法字符");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1agent", "agent-db", "agent db", "agent$", "中文库"})
    void 非法标识符一律拒绝(String database) {
        assertThatThrownBy(() -> PostgresUrl
                .parse("jdbc:postgresql://localhost:5432/" + database)
                .assertSafeIdentifier())
                .isInstanceOf(StoreException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent", "agent_prod", "_internal", "A1"})
    void 合法标识符放行(String database) {
        assertThat(PostgresUrl.parse("jdbc:postgresql://localhost:5432/" + database)
                .assertSafeIdentifier()).isEqualTo(database);
    }

    @Test
    void 格式不认识时明确报错_而不是猜() {
        assertThatThrownBy(() -> PostgresUrl.parse("jdbc:mysql://localhost:3306/agent"))
                .isInstanceOf(StoreException.class)
                .hasMessageContaining("只支持");
        assertThatThrownBy(() -> PostgresUrl.parse("jdbc:postgresql://localhost:5432"))
                .isInstanceOf(StoreException.class)
                .hasMessageContaining("没有库名");
        assertThatThrownBy(() -> PostgresUrl.parse(null))
                .isInstanceOf(StoreException.class);
    }
}
