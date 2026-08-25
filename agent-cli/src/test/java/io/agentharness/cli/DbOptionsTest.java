package io.agentharness.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接参数的取值优先级：命令行 → 环境变量 → 默认值。
 *
 * <p>这条链断了会很难查：字段默认值改成 null 之后，任何还在读裸字段的调用方
 * 都会静默拿到 null —— {@code migrate} 会带着 null 用户去连库，
 * {@code doctor} 会打印 "null"，而报错信息指向的是数据库而不是这里。
 */
class DbOptionsTest {

    @Test
    void 什么都不给时不会返回null() {
        DbOptions options = new DbOptions();

        // 环境变量可能在本机设着，所以只断言"不为空"，不断言具体值
        assertThat(options.resolveJdbcUrl()).isNotBlank();
        assertThat(options.resolveUser()).isNotBlank();
        assertThat(options.resolvePassword()).isNotNull();
    }

    @Test
    void 命令行优先于环境变量与默认值() {
        DbOptions options = new DbOptions();
        options.jdbcUrl = "jdbc:postgresql://db:5432/other";
        options.username = "someone";
        options.password = "从命令行来的";

        assertThat(options.resolveJdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/other");
        assertThat(options.resolveUser()).isEqualTo("someone");
        assertThat(options.resolvePassword()).isEqualTo("从命令行来的");
    }

    @Test
    @DisplayName("空串按「没给」处理 —— 否则 --db-user '' 会把用户名清成空")
    void 空串不算给了值() {
        DbOptions options = new DbOptions();
        options.jdbcUrl = "   ";
        options.username = "";

        assertThat(options.resolveJdbcUrl()).isNotBlank();
        assertThat(options.resolveUser()).isNotBlank();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_DB_USER", matches = ".+")
    @DisplayName("命令行留空时落到环境变量")
    void 环境变量兜底() {
        assertThat(new DbOptions().resolveUser()).isEqualTo(System.getenv("AGENT_DB_USER"));
    }
}
