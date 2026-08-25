package io.agentharness.store.message;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.Json;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.StoreException;
import io.agentharness.store.jdbc.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link MessageRepository} 的 PostgreSQL 实现。 */
public final class PostgresMessageRepository implements MessageRepository {

    /**
     * 序号分配。
     *
     * <p>{@code ON CONFLICT DO UPDATE ... RETURNING} 是单条语句、单次往返，且在行锁内完成 ——
     * 并发的两个 worker 不可能拿到重叠的段。
     * 用「先 SELECT 再 UPDATE」实现的话就需要显式事务和重试，且很容易写成可重复读下的丢更新。
     */
    private static final String ALLOCATE_SQL = """
            INSERT INTO agent_msg_seq (session_id, last_seq)
            VALUES (?, ?)
            ON CONFLICT (session_id) DO UPDATE
                SET last_seq = agent_msg_seq.last_seq + EXCLUDED.last_seq,
                    updated_at = now()
            RETURNING last_seq
            """;

    private static final String APPEND_SQL = """
            INSERT INTO agent_message
                (session_id, msg_seq, user_id, reply_id, block_id, msg_role, msg_type,
                 fallback_text, payload, created_at, instruction_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    /** 确保分配器行存在，好让下面的 FOR UPDATE 有行可锁。 */
    private static final String ENSURE_ALLOCATOR_SQL = """
            INSERT INTO agent_msg_seq (session_id, last_seq) VALUES (?, 0)
            ON CONFLICT (session_id) DO NOTHING
            """;

    /**
     * 锁住本 session 的分配器行。
     *
     * <p>「查幂等键」与「分配序号」之间是一段 check-then-act：
     * 不加锁的话，N 个并发重试会同时查不到、同时各分配一个序号，
     * 最后唯一索引只让一个写进去 —— 另外 N-1 个序号被永久烧掉，序列里出现空洞。
     * 行锁把这段临界区串起来，后到的事务会看到已经写入的那条并直接复用。
     */
    private static final String LOCK_ALLOCATOR_SQL =
            "SELECT last_seq FROM agent_msg_seq WHERE session_id = ? FOR UPDATE";

    private static final String FIND_BY_INSTRUCTION_SQL = """
            SELECT msg_seq, reply_id, block_id, msg_role, msg_type, fallback_text, payload, created_at
            FROM agent_message
            WHERE session_id = ? AND instruction_id = ?
            """;

    private static final String SINCE_SQL = """
            SELECT msg_seq, reply_id, block_id, msg_role, msg_type, fallback_text, payload, created_at
            FROM agent_message
            WHERE session_id = ? AND msg_seq > ? AND superseded = false
            ORDER BY msg_seq
            LIMIT ?
            """;

    private static final String CLAIM_TURN_SQL = """
            UPDATE agent_message SET turn_claimed_at = now()
             WHERE session_id = ? AND instruction_id = ? AND turn_claimed_at IS NULL
            """;

    private static final String LAST_SEQ_SQL =
            "SELECT COALESCE(MAX(msg_seq), 0) AS last_seq FROM agent_message WHERE session_id = ?";

    // msg_role <> 'USER'：重跑作废助手输出，但用户说过的话不会因此变成没说过
    private static final String SUPERSEDE_SQL = """
            UPDATE agent_message SET superseded = true
             WHERE session_id = ? AND reply_id = ? AND msg_role <> 'USER'
            """;

    private static final int MAX_LIMIT = 1000;

    private final Jdbc jdbc;

    public PostgresMessageRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long allocate(SessionRef session, int count) {
        if (count <= 0) {
            throw new StoreException("分配数量必须为正数，实际为 " + count);
        }
        long last = jdbc.queryOne(ALLOCATE_SQL, rs -> rs.getLong("last_seq"),
                        session.sessionId(), (long) count)
                .orElseThrow(() -> new StoreException("序号分配没有返回结果：" + session));
        return last - count + 1;
    }

    @Override
    public List<ClientMessage> append(SessionRef session, List<PendingMessage> pending) {
        if (pending.isEmpty()) {
            return List.of();
        }
        // 分配与写入同一个事务：写入失败时分配器一起回滚，不会烧掉序号（INV-10）
        return jdbc.inTransaction(connection -> {
            long first = allocateIn(connection, session, pending.size());

            List<ClientMessage> assigned = new ArrayList<>(pending.size());
            List<Object[]> rows = new ArrayList<>(pending.size());
            long seq = first;
            for (PendingMessage draft : pending) {
                ClientMessage message = new ClientMessage(seq, draft.replyId(), draft.blockId(),
                        draft.role(), draft.type(), draft.fallbackText(), draft.payload(),
                        draft.createdAt());
                assigned.add(message);
                rows.add(toRow(session, message, null));
                seq++;
            }

            jdbc.batch(connection, APPEND_SQL, rows);
            return List.copyOf(assigned);
        });
    }

    private long allocateIn(java.sql.Connection connection, SessionRef session, int count) {
        long last = jdbc.queryOne(connection, ALLOCATE_SQL, rs -> rs.getLong("last_seq"),
                        session.sessionId(), (long) count)
                .orElseThrow(() -> new StoreException("序号分配没有返回结果：" + session));
        return last - count + 1;
    }

    private static Object[] toRow(SessionRef session, ClientMessage message, String instructionId) {
        return new Object[]{
                session.sessionId(),
                message.msgSeq(),
                session.userId(),
                message.replyId(),
                message.blockId(),
                message.role().name(),
                message.type().name(),
                message.fallbackText(),
                Json.write(message.payload()),
                Timestamp.from(message.createdAt()),
                instructionId
        };
    }

    @Override
    public UserMessageOutcome appendUserMessage(SessionRef session, String replyId, String blockId,
                                                String text, String instructionId) {
        // 查、分配、写三步同一个事务：
        // 分开的话，写入失败时分配器已经推进，序列里就留下一个永远补不上的洞
        return jdbc.inTransaction(connection -> {
            // ⓪ 先锁住本 session 的分配器行，把「查」与「分配」纳入同一个临界区。
            //    少了这一步，并发重试会各自分配一个序号，而唯一索引只放行一个 ——
            //    其余的序号被烧掉，序列里留下空洞
            jdbc.update(connection, ENSURE_ALLOCATOR_SQL, session.sessionId());
            jdbc.queryOne(connection, LOCK_ALLOCATOR_SQL, rs -> rs.getLong("last_seq"),
                    session.sessionId());

            // ① 查幂等键。命中就直接复用，一个序号都不分配
            Optional<ClientMessage> existing = jdbc.queryOne(connection, FIND_BY_INSTRUCTION_SQL,
                    PostgresMessageRepository::mapMessage, session.sessionId(), instructionId);
            if (existing.isPresent()) {
                return new UserMessageOutcome(existing.get(), false);
            }

            // ② 没命中才分配序号并写入
            long seq = allocateIn(connection, session, 1);
            ClientMessage message = ClientMessage.userText(seq, replyId, blockId, text, Instant.now());
            int inserted = jdbc.update(connection, APPEND_SQL,
                    toRow(session, message, instructionId));

            if (inserted == 0) {
                // 唯一索引挡下了并发重试。lease 之下不该发生 ——
                // 真发生了说明有两个 worker 在跑同一个 session，那是 INV-3 被违反
                return jdbc.queryOne(connection, FIND_BY_INSTRUCTION_SQL,
                                PostgresMessageRepository::mapMessage,
                                session.sessionId(), instructionId)
                        .map(found -> new UserMessageOutcome(found, false))
                        .orElseThrow(() -> new StoreException(
                                "用户消息既没写入也查不到，instructionId=" + instructionId));
            }
            return new UserMessageOutcome(message, true);
        });
    }

    @Override
    public List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return jdbc.queryList(SINCE_SQL, PostgresMessageRepository::mapMessage,
                session.sessionId(), Math.max(sinceSeq, 0L), bounded);
    }

    @Override
    public Optional<ClientMessage> findByInstruction(SessionRef session, String instructionId) {
        return jdbc.queryOne(FIND_BY_INSTRUCTION_SQL, PostgresMessageRepository::mapMessage,
                session.sessionId(), instructionId);
    }

    @Override
    public boolean claimTurn(SessionRef session, String instructionId) {
        return jdbc.update(CLAIM_TURN_SQL, session.sessionId(), instructionId) == 1;
    }

    @Override
    public long lastSeq(SessionRef session) {
        return jdbc.queryOne(LAST_SEQ_SQL, rs -> rs.getLong("last_seq"), session.sessionId())
                .orElse(0L);
    }

    @Override
    public int markSuperseded(SessionRef session, String replyId) {
        return jdbc.update(SUPERSEDE_SQL, session.sessionId(), replyId);
    }

    @SuppressWarnings("unchecked")
    private static ClientMessage mapMessage(ResultSet rs) throws SQLException {
        String payloadJson = rs.getString("payload");
        Map<String, Object> payload = payloadJson == null || payloadJson.isBlank()
                ? Map.of()
                : Json.read(payloadJson, Map.class);

        return new ClientMessage(
                rs.getLong("msg_seq"),
                rs.getString("reply_id"),
                rs.getString("block_id"),
                MessageRole.valueOf(rs.getString("msg_role")),
                MessageType.valueOf(rs.getString("msg_type")),
                rs.getString("fallback_text"),
                payload,
                rs.getTimestamp("created_at").toInstant());
    }
}
