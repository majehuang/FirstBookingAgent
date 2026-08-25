package io.agentharness.comm.egress;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientCapabilities;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P2 的 UI-006：**仅文本客户端要能收到服务端降级后的消息**。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <p>降级发生在<b>读取侧</b>。这条测试同时钉住那个决定：
 * outbox 里存的必须是完整卡片，两个能力不同的客户端各自看到各自能渲染的形态。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class CapabilityDegradationIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DRAIN = Duration.ofMillis(300);

    private static RedisRuntime runtime;

    private final SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());

    @BeforeAll
    static void connect() {
        runtime = RedisRuntime.open(RedisConfig.of(System.getenv("AGENT_IT_REDIS_URI")));
    }

    @AfterAll
    static void disconnect() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @AfterEach
    void cleanup() {
        runtime.commands().del(KeyNamespace.outbox(session.sessionId())).block(TIMEOUT);
    }

    /** 直接往 outbox 里放一条完整卡片，绕开 Worker。 */
    private ClientMessage publishCard() {
        ClientMessage card = new ClientMessage(1, "r-1", "b-1", MessageRole.ASSISTANT,
                MessageType.CARD, "为你找到 1 家酒店：北京国贸大酒店 ¥1,280",
                Map.of("title", "为你找到 1 家酒店",
                        "items", List.of(Map.of("name", "北京国贸大酒店", "price", "¥1,280")),
                        "dataAsOf", "2026-08-25 10:00"),
                Instant.parse("2026-08-25T10:00:00Z"));

        runtime.commands().xadd(KeyNamespace.outbox(session.sessionId()),
                StreamPayload.of(card)).block(TIMEOUT);
        return card;
    }

    private List<ClientMessage> read(ClientCapabilities capabilities) {
        return new RedisMessageSubscriber(runtime, capabilities)
                .messages(session).take(DRAIN).collectList().block(TIMEOUT);
    }

    @Test
    @DisplayName("仅文本客户端收到压成 TEXT 的降级消息，而不是空组件")
    void 仅文本客户端收到降级消息() {
        ClientMessage original = publishCard();

        ClientMessage received = read(ClientCapabilities.defaults()).get(0);

        assertThat(received.type()).isEqualTo(MessageType.TEXT);
        assertThat(received.fallbackText()).isEqualTo(original.fallbackText());
        assertThat(received.payload()).isEmpty();
        // 序号与归属不变 —— 降级不能打乱顺序，否则客户端的空窗判定会失效
        assertThat(received.msgSeq()).isEqualTo(original.msgSeq());
        assertThat(received.replyId()).isEqualTo(original.replyId());
        assertThat(received.role()).isEqualTo(MessageRole.ASSISTANT);
    }

    @Test
    void 全能力客户端收到完整卡片() {
        ClientMessage original = publishCard();

        ClientMessage received = read(ClientCapabilities.full()).get(0);

        assertThat(received).isEqualTo(original);
        assertThat(received.payload()).containsEntry("dataAsOf", "2026-08-25 10:00");
    }

    @Test
    @DisplayName("降级在读取侧 —— outbox 里存的仍是完整卡片")
    void 降级不改变已落盘的内容() {
        publishCard();

        read(ClientCapabilities.defaults());
        ClientMessage stillFull = read(ClientCapabilities.full()).get(0);

        // 仅文本客户端读过一遍之后，全能力客户端仍然拿得到卡片。
        // 写入侧降级的话，这里就只剩纯文本了 —— 而且新客户端升级后也拿不回历史里的富消息
        assertThat(stillFull.type()).isEqualTo(MessageType.CARD);
    }

    @Test
    void 文本消息不受降级影响() {
        ClientMessage text = ClientMessage.textDelta(1, "r-1", "b-1", "你好",
                Instant.parse("2026-08-25T10:00:00Z"));
        runtime.commands().xadd(KeyNamespace.outbox(session.sessionId()),
                StreamPayload.of(text)).block(TIMEOUT);

        assertThat(read(ClientCapabilities.defaults()).get(0)).isEqualTo(text);
    }
}
