package io.agentharness.task.coldstore;

import io.agentharness.protocol.Json;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.eventlog.EventLogRepository;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件冷存储的<b>旁路</b>写入。
 *
 * <p>三条性质缺一不可（INV-5 后半句）：
 * <ul>
 *   <li><b>异步</b> —— 冷存储卡 1 秒不能让实时消息也卡 1 秒</li>
 *   <li><b>失败不上抛</b> —— 归档挂了不该让用户的对话失败</li>
 *   <li><b>单条失败不影响后续</b> —— 每条独立提交，异常不会终止旁路本身</li>
 * </ul>
 *
 * <p>刻意<b>不</b>做成 Flux 的一个算子挂在主链路上：那样一次异常就会终止整条流，
 * 而且背压会从冷存储传导回模型输出。这里是彻底的 fire-and-forget。
 */
public final class ColdStorageBypass {

    private static final Logger log = LoggerFactory.getLogger(ColdStorageBypass.class);

    private final EventLogRepository repository;
    private final AtomicLong failures = new AtomicLong();

    public ColdStorageBypass(EventLogRepository repository) {
        this.repository = repository;
    }

    /** 记一条事件。永不抛异常，永不阻塞调用方。 */
    public void record(SessionRef session, String replyId, Event event) {
        if (event == null) {
            return;
        }
        String eventType = event.getType() == null ? "UNKNOWN" : event.getType().name();
        String payload = describe(event);

        Schedulers.boundedElastic().schedule(() -> {
            try {
                repository.record(session, replyId, eventType, payload);
            } catch (RuntimeException e) {
                // 计量而不是上抛。冷存储是可以随便挂的那一层，
                // 一旦它的失败能让 turn 失败，就不再是旁路了
                long total = failures.incrementAndGet();
                log.warn("冷存储写入失败（累计 {} 次），session={} replyId={}",
                        total, session.sessionId(), replyId, e);
            }
        });
    }

    /** 失败计数，供可观测使用。 */
    public long failureCount() {
        return failures.get();
    }

    /**
     * 把事件压成可归档的 JSON。
     *
     * <p>只取排查需要的字段，不整条序列化 {@code Msg}：后者带多态内容块，
     * 换个 mapper 就可能失败，而<b>归档不该因为序列化细节而丢数据</b>。
     */
    private static String describe(Event event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getType() == null ? null : event.getType().name());
        payload.put("last", event.isLast());
        payload.put("messageId", event.getMessageId());

        Msg message = event.getMessage();
        if (message != null) {
            payload.put("role", message.getRole() == null ? null : message.getRole().name());
            payload.put("text", safeText(message));
            payload.put("blocks", message.getContent() == null ? 0 : message.getContent().size());
        }
        try {
            return Json.write(payload);
        } catch (RuntimeException e) {
            return "{\"type\":\"" + payload.get("type") + "\",\"serializationFailed\":true}";
        }
    }

    private static String safeText(Msg message) {
        try {
            return message.getTextContent();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
