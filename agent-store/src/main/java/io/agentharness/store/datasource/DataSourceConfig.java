package io.agentharness.store.datasource;

import java.time.Duration;
import java.util.Objects;

/**
 * 数据源配置。
 *
 * <p>默认值按本项目的负载形态定：连接大部分时间在等 PG 返回，而 PG 端的插入是亚毫秒级，
 * 所以池不需要很大 —— 池开太大只会把排队从应用挪到数据库，还更难观测。
 */
public record DataSourceConfig(
        String jdbcUrl,
        String username,
        String password,
        int maxPoolSize,
        Duration connectionTimeout,
        Duration maxLifetime,
        Duration validationTimeout) {

    private static final int DEFAULT_POOL_SIZE = 10;
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(30);
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(2);

    public DataSourceConfig {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl 不能为空");
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize 必须为正数，实际为 " + maxPoolSize);
        }
        connectionTimeout = orDefault(connectionTimeout, DEFAULT_CONNECTION_TIMEOUT);
        maxLifetime = orDefault(maxLifetime, DEFAULT_MAX_LIFETIME);
        validationTimeout = orDefault(validationTimeout, DEFAULT_VALIDATION_TIMEOUT);
    }

    public static DataSourceConfig of(String jdbcUrl, String username, String password) {
        return new DataSourceConfig(jdbcUrl, username, password,
                DEFAULT_POOL_SIZE, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_MAX_LIFETIME, DEFAULT_VALIDATION_TIMEOUT);
    }

    public DataSourceConfig withMaxPoolSize(int size) {
        return new DataSourceConfig(jdbcUrl, username, password, size,
                connectionTimeout, maxLifetime, validationTimeout);
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
