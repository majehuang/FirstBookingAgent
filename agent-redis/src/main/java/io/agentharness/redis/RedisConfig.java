package io.agentharness.redis;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis 连接与轮询配置。
 *
 * <p>两个轮询间隔分开配置是有原因的：
 * <ul>
 *   <li><b>消息流 50ms</b> —— OutboxWriter 每 80ms 合批一次，轮询慢于它就会把相邻批次并成一坨，
 *       用户看到的更新频率掉下来（PERF-001 要 10–20 次/秒）</li>
 *   <li><b>控制流 200ms</b> —— 控制帧稀疏，快轮询只是浪费往返</li>
 * </ul>
 *
 * <p>两者都必须是<b>非阻塞</b>轮询。{@code XREAD BLOCK} 会独占连接，
 * Redis 连接数将随 SSE 连接数线性放大，几千即打满（INV-12）。
 */
public record RedisConfig(
        String uri,
        Duration commandTimeout,
        Duration messagePollInterval,
        Duration controlPollInterval,
        int readBatchSize) {

    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_MESSAGE_POLL = Duration.ofMillis(50);
    private static final Duration DEFAULT_CONTROL_POLL = Duration.ofMillis(200);
    private static final int DEFAULT_BATCH = 256;

    public RedisConfig {
        Objects.requireNonNull(uri, "uri");
        commandTimeout = positive(commandTimeout, DEFAULT_COMMAND_TIMEOUT);
        messagePollInterval = positive(messagePollInterval, DEFAULT_MESSAGE_POLL);
        controlPollInterval = positive(controlPollInterval, DEFAULT_CONTROL_POLL);
        readBatchSize = readBatchSize > 0 ? readBatchSize : DEFAULT_BATCH;
    }

    public static RedisConfig of(String uri) {
        return new RedisConfig(uri, DEFAULT_COMMAND_TIMEOUT, DEFAULT_MESSAGE_POLL,
                DEFAULT_CONTROL_POLL, DEFAULT_BATCH);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
