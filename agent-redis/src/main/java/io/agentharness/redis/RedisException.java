package io.agentharness.redis;

/** Redis 操作失败。投递路径要靠它区分"该回 5xx"与"该重试"。 */
public class RedisException extends RuntimeException {

    public RedisException(String message) {
        super(message);
    }

    public RedisException(String message, Throwable cause) {
        super(message, cause);
    }
}
