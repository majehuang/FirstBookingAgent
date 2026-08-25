package io.agentharness.task.worker;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内存版消息表，只为把 {@link io.agentharness.task.worker.SessionWorker} 跑起来。
 *
 * <p><b>不是 PostgreSQL 实现的替身</b>：序号分配的并发正确性、jsonb 往返、
 * 行锁这些都由 {@code PostgresMessageRepositoryIntegrationTest} 守着。
 * 这里只需要"同一 instructionId 只落一次"这一条语义成立，
 * 否则 turn 会跑两遍、追踪断言也就没意义了。
 */
final class InMemoryMessageRepository implements MessageRepository {

    private final List<ClientMessage> messages = new ArrayList<>();
    private final Map<String, ClientMessage> byInstruction = new HashMap<>();
    private final Set<String> claimed = new HashSet<>();
    private long seq;

    @Override
    public synchronized long allocate(SessionRef session, int count) {
        seq += count;
        return seq - count + 1;
    }

    @Override
    public synchronized List<ClientMessage> append(SessionRef session, List<PendingMessage> pending) {
        List<ClientMessage> appended = new ArrayList<>(pending.size());
        for (PendingMessage draft : pending) {
            ClientMessage message = new ClientMessage(++seq, draft.replyId(), draft.blockId(),
                    draft.role(), draft.type(), draft.fallbackText(), draft.payload(), draft.createdAt());
            messages.add(message);
            appended.add(message);
        }
        return appended;
    }

    @Override
    public synchronized UserMessageOutcome appendUserMessage(SessionRef session, String replyId,
                                                             String blockId, String text,
                                                             String instructionId) {
        ClientMessage existing = byInstruction.get(instructionId);
        if (existing != null) {
            return new UserMessageOutcome(existing, false);
        }
        ClientMessage message = new ClientMessage(++seq, replyId, blockId, MessageRole.USER,
                MessageType.TEXT, text, Map.of(), Instant.now());
        messages.add(message);
        byInstruction.put(instructionId, message);
        return new UserMessageOutcome(message, true);
    }

    @Override
    public synchronized List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
        return messages.stream().filter(m -> m.msgSeq() > sinceSeq).limit(limit).toList();
    }

    @Override
    public synchronized long lastSeq(SessionRef session) {
        return seq;
    }

    @Override
    public synchronized Optional<ClientMessage> findByInstruction(SessionRef session,
                                                                  String instructionId) {
        return Optional.ofNullable(byInstruction.get(instructionId));
    }

    @Override
    public synchronized boolean claimTurn(SessionRef session, String instructionId) {
        return claimed.add(instructionId);
    }

    @Override
    public synchronized int markSuperseded(SessionRef session, String replyId) {
        return 0;
    }
}
