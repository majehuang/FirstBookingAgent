package io.agentharness.protocol;

/** 协议层的校验与序列化失败。调用方应当把它映射为 4xx，而不是 5xx。 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
