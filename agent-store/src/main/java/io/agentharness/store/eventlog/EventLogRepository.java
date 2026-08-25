package io.agentharness.store.eventlog;

import io.agentharness.protocol.SessionRef;

/**
 * 事件冷存储 —— 全量 {@code AgentEvent} 的归档。
 *
 * <p><b>只用于排查与分析，任何在线逻辑都不许读它。</b>
 * 在线路径一旦依赖冷存储，冷存储的抖动就会变成用户可见的故障，
 * 而它本来是可以随便挂的那一层（COLD-006 就是在守这条）。
 */
public interface EventLogRepository {

    void record(SessionRef session, String replyId, String eventType, String payloadJson);
}
