package io.agentharness.store.eventlog;

import io.agentharness.protocol.SessionRef;
import io.agentharness.store.jdbc.Jdbc;

/** 冷存储的 PostgreSQL 实现。写入是同步的，异步与故障隔离由调用方负责。 */
public final class PostgresEventLogRepository implements EventLogRepository {

    private static final String INSERT_SQL = """
            INSERT INTO agent_event_log (session_id, user_id, reply_id, event_type, payload)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """;

    private final Jdbc jdbc;

    public PostgresEventLogRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(SessionRef session, String replyId, String eventType, String payloadJson) {
        jdbc.update(INSERT_SQL, session.sessionId(), session.userId(), replyId, eventType,
                payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
    }
}
