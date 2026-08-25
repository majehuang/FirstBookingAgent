package io.agentharness.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis 连接的持有者。
 *
 * <p><b>整个进程共用一条连接。</b>Lettuce 对非阻塞命令做多路复用，
 * 上千个并发订阅共享一条连接不是问题 —— 这正是禁止 {@code XREAD BLOCK} 的收益（INV-12）：
 * 一旦用了阻塞命令，每个订阅都得独占一条连接。
 */
public final class RedisRuntime implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisConfig config;

    private RedisRuntime(RedisClient client, StatefulRedisConnection<String, String> connection,
                         RedisConfig config) {
        this.client = client;
        this.connection = connection;
        this.config = config;
    }

    public static RedisRuntime open(RedisConfig config) {
        Objects.requireNonNull(config, "config");
        RedisClient client = RedisClient.create(config.uri());
        client.setDefaultTimeout(config.commandTimeout());
        try {
            return new RedisRuntime(client, client.connect(), config);
        } catch (RuntimeException e) {
            client.shutdown();
            throw new RedisException("连接 Redis 失败：" + config.uri(), e);
        }
    }

    public RedisReactiveCommands<String, String> commands() {
        return connection.reactive();
    }

    public RedisConfig config() {
        return config;
    }

    public Duration messagePollInterval() {
        return config.messagePollInterval();
    }

    public Duration controlPollInterval() {
        return config.controlPollInterval();
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
