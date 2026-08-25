package io.agentharness.store;

/** 存储层失败。调用方需要区分"重试有用"与"重试没用"时看 {@link #retryable()}。 */
public class StoreException extends RuntimeException {

    private final boolean retryable;

    public StoreException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public StoreException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public StoreException(String message) {
        this(message, null, false);
    }

    /** 连接超时、死锁、序列化冲突这类可以重试；约束冲突、SQL 语法错误重试没有意义。 */
    public boolean retryable() {
        return retryable;
    }
}
