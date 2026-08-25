package io.agentharness.store.jdbc;

import io.agentharness.store.StoreException;

import java.sql.SQLException;
import java.util.Set;

/**
 * SQLException 的分类 —— {@link Jdbc} 里唯一需要判断的地方，所以单独拎出来，
 * 这样不需要真实数据库就能把全部分支覆盖到。
 *
 * <p>为什么要区分：重试一个语法错误只会把同样的错再犯一遍；
 * 而连接抖动和序列化冲突不重试就等于把一次可恢复的失败上升成了用户可见的报错。
 */
public final class SqlErrors {

    /**
     * 可重试的 SQLState 类别（前两位）：
     * <ul>
     *   <li>{@code 08} 连接异常</li>
     *   <li>{@code 40} 事务回滚（含死锁、序列化失败）</li>
     *   <li>{@code 53} 资源不足</li>
     *   <li>{@code 57} 运维干预（如主库切换）</li>
     * </ul>
     * 其余一律不重试：{@code 23}（约束冲突）、{@code 42}（语法或对象不存在）重试没有意义。
     */
    private static final Set<String> RETRYABLE_CLASSES = Set.of("08", "40", "53", "57");

    private SqlErrors() {
    }

    public static boolean retryable(String sqlState) {
        return sqlState != null
                && sqlState.length() >= 2
                && RETRYABLE_CLASSES.contains(sqlState.substring(0, 2));
    }

    public static StoreException translate(String what, String sql, SQLException cause) {
        String state = cause.getSQLState() == null ? "" : cause.getSQLState();
        String detail = sql == null || sql.isEmpty() ? what : what + "：" + squash(sql);
        return new StoreException(detail + "（SQLState " + state + "）", cause, retryable(state));
    }

    /** 多行 SQL 压成一行，避免异常信息在日志里铺开十几行。 */
    static String squash(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }
}
