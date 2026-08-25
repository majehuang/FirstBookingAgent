package io.agentharness.store.schema;

import io.agentharness.store.StoreException;

import java.util.regex.Pattern;

/**
 * JDBC 连接串的最小解析 —— 只为了做一件事：把目标库名换成维护库，好去建库。
 *
 * <p>{@code CREATE DATABASE} 不能用绑定参数，库名必须拼进 SQL，
 * 所以 {@link #assertSafeIdentifier} 是<b>安全边界</b>而不是格式校验：
 * 库名来自命令行，不校验就等于开了一个注入口子。
 */
public record PostgresUrl(String prefix, String database, String query) {

    /** PostgreSQL 标识符：字母或下划线开头，最长 63 字节。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private static final String SCHEME = "jdbc:postgresql://";
    private static final String MAINTENANCE_DATABASE = "postgres";

    public static PostgresUrl parse(String url) {
        if (url == null || !url.startsWith(SCHEME)) {
            throw new StoreException("只支持 " + SCHEME + " 形式的连接串，实际为 " + url);
        }
        String rest = url.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new StoreException("连接串里没有库名：" + url);
        }

        String hostPort = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);

        int questionMark = tail.indexOf('?');
        String database = questionMark < 0 ? tail : tail.substring(0, questionMark);
        String query = questionMark < 0 ? "" : tail.substring(questionMark);

        if (database.isBlank()) {
            throw new StoreException("连接串里的库名为空：" + url);
        }
        return new PostgresUrl(SCHEME + hostPort, database, query);
    }

    /** 指向维护库 {@code postgres} 的连接串，用来执行 CREATE DATABASE。 */
    public String maintenanceUrl() {
        return prefix + "/" + MAINTENANCE_DATABASE + query;
    }

    public boolean isMaintenanceDatabase() {
        return MAINTENANCE_DATABASE.equals(database);
    }

    /**
     * 校验库名可以安全地拼进 SQL。
     *
     * @throws StoreException 含有标识符以外的字符时
     */
    public String assertSafeIdentifier() {
        if (!SAFE_IDENTIFIER.matcher(database).matches()) {
            throw new StoreException("库名 \"" + database
                    + "\" 含有非法字符。只允许字母、数字、下划线，且不能以数字开头");
        }
        return database;
    }
}
