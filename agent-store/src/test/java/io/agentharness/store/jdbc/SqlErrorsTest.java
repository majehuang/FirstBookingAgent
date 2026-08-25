package io.agentharness.store.jdbc;

import io.agentharness.store.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class SqlErrorsTest {

    @ParameterizedTest(name = "SQLState {0} → 可重试 {1}")
    @CsvSource({
            "08006, true",   // 连接失败
            "08003, true",
            "40001, true",   // 序列化失败
            "40P01, true",   // 死锁
            "53300, true",   // 连接数打满
            "57P01, true",   // 管理员关闭连接（主备切换）
            "23505, false",  // 唯一约束冲突 —— 重试只会再冲突一次
            "42P01, false",  // 表不存在 —— 重试没有意义
            "22P02, false"
    })
    void 按SQLState类别决定是否重试(String state, boolean expected) {
        assertThat(SqlErrors.retryable(state)).isEqualTo(expected);
    }

    @Test
    void 缺失或过短的SQLState一律不重试() {
        assertThat(SqlErrors.retryable(null)).isFalse();
        assertThat(SqlErrors.retryable("")).isFalse();
        assertThat(SqlErrors.retryable("0")).isFalse();
    }

    @Test
    void 异常信息里带上SQLState_排查时不用再翻堆栈() {
        StoreException e = SqlErrors.translate("更新失败", "UPDATE t SET a = 1", new SQLException("boom", "40001"));

        assertThat(e).hasMessageContaining("更新失败")
                .hasMessageContaining("UPDATE t SET a = 1")
                .hasMessageContaining("40001");
        assertThat(e.retryable()).isTrue();
    }

    @Test
    void 多行SQL被压成一行_异常信息不铺开十几行() {
        String multiline = """
                SELECT a,
                       b
                  FROM t
                """;
        assertThat(SqlErrors.squash(multiline)).isEqualTo("SELECT a, b FROM t");
    }

    @Test
    void 没有SQL上下文时只报动作() {
        StoreException e = SqlErrors.translate("事务执行失败", "", new SQLException("boom", "23505"));

        assertThat(e).hasMessageContaining("事务执行失败");
        assertThat(e.retryable()).isFalse();
    }
}
