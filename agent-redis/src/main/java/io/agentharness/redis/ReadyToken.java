package io.agentharness.redis;

import io.agentharness.protocol.SessionRef;

/**
 * 唤醒令牌。
 *
 * <p>带上 {@code userId} 而不是只带 sessionId：Worker 需要完整的 {@code (userId, sessionId)}
 * 才能构造 RuntimeContext 与访问消息表，而 ready 是全局队列、没有别的地方能补出用户身份。
 */
public record ReadyToken(String userId, String sessionId) {

    public static ReadyToken of(SessionRef session) {
        return new ReadyToken(session.userId(), session.sessionId());
    }

    public SessionRef toSession() {
        return SessionRef.of(userId, sessionId);
    }
}
