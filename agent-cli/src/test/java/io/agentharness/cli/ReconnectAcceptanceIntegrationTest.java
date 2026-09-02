package io.agentharness.cli;

import io.agentharness.comm.egress.RedisMessageSubscriber;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.trace.TraceSink;
import io.agentharness.tui.state.SeqRule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-7 断网重连验收（G4 门禁的两条硬指标），跨三个模块：
 * 写入端（{@code OutboxStream}）→ 订阅端（{@code RedisMessageSubscriber}）→
 * 客户端三规则（{@code SeqRule}，即交付给前端的参考实现）。
 *
 * <ul>
 *   <li><b>turn 进行中反复断连重连</b>：最终文本与不断连时逐字节相同；</li>
 *   <li><b>离线超过 outbox 窗口</b>：重连后从消息表无缝续上，不出现空洞也不出现重复。</li>
 * </ul>
 *
 * <p>"断网"以取消订阅再重订模拟 —— 这正是 SSE 断开重连在服务端看到的形态
 * （新建连、不带任何位置参数、全量重放窗口）。进程级 / 网络级的断网属于人工验收，
 * 与 P3 混沌的口径一致。
 *
 * <p>消息表用内存替身：{@code since} 语义已由
 * {@code PostgresMessageRepositoryIntegrationTest} 在真库上验证，
 * 这里验的是三规则与重放的<b>配合</b>，不是存储。
 * 写入顺序遵守 INV-5：先入"表"，后 {@code XADD}。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class ReconnectAcceptanceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 测试窗口 300ms，让"离线超过窗口"在毫秒级可造。 */
    private static final Duration WINDOW = Duration.ofMillis(300);

    private static RedisRuntime runtime;

    private final SessionRef session = SessionRef.of("it-user", "it-rc-" + UUID.randomUUID());
    private final List<ClientMessage> table = new ArrayList<>();

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

    @Test
    @DisplayName("turn 进行中反复断连重连，最终文本与不断连时逐字节相同（G4）")
    void turn中反复断连重连_最终文本逐字节相同() {
        OutboxStream outbox = new OutboxStream(runtime, WINDOW, TraceSink.disabled());
        SimClient client = new SimClient(table);
        StringBuilder expected = new StringBuilder();

        // turn 进行中：保护窗口生效，重放不受时间窗影响
        outbox.publishTurnStart(session, publishToTable(expected, 1)).block(TIMEOUT);
        long seq = 1;
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 9; i++) {
                outbox.publish(session, publishToTable(expected, ++seq)).block(TIMEOUT);
            }
            // "断网重连"：上一轮的订阅已取消，这里新建连接、全量重放、读到当前水位为止
            connectAndDrainTo(client, seq);
        }

        assertThat(client.text.toString())
                .as("六次断连重连后的最终文本必须与发布的完全一致 —— 逐字节")
                .isEqualTo(expected.toString());
        assertThat(client.renderCounts.values())
                .as("重放的重复必须全部被 DISCARD，一条都不能渲染两遍")
                .allMatch(count -> count == 1);
        assertThat(client.gaps)
                .as("窗口未过期，重连应当全靠重放收敛，不需要走历史")
                .isZero();
    }

    @Test
    @DisplayName("离线超过 outbox 窗口，重连后从消息表续上 —— 无空洞、无重复（G4）")
    void 超窗离线重连_从消息表续上_无空洞无重复() {
        OutboxStream outbox = new OutboxStream(runtime, WINDOW, TraceSink.disabled());
        SimClient client = new SimClient(table);
        StringBuilder expected = new StringBuilder();

        // 第一段在线收完
        for (long seq = 1; seq <= 20; seq++) {
            outbox.publish(session, publishToTable(expected, seq)).block(TIMEOUT);
        }
        connectAndDrainTo(client, 20);

        // 离线期间又发了 40 条，然后窗口过期 —— 它们从 outbox 里消失，只剩消息表
        for (long seq = 21; seq <= 60; seq++) {
            outbox.publish(session, publishToTable(expected, seq)).block(TIMEOUT);
        }
        sleep(WINDOW.plusMillis(200));

        // 回到在线时又有新消息进来，裁剪随写入发生
        for (long seq = 61; seq <= 70; seq++) {
            outbox.publish(session, publishToTable(expected, seq)).block(TIMEOUT);
        }
        connectAndDrainTo(client, 70);

        assertThat(client.gaps)
                .as("重放窗口起点已经越过本地水位，必须走一次 GAP → 历史补齐")
                .isEqualTo(1);
        assertThat(client.text.toString())
                .as("空窗部分由消息表补齐后，最终文本仍然逐字节完整")
                .isEqualTo(expected.toString());
        assertThat(client.renderCounts.keySet())
                .as("70 条一条不少 —— 无空洞")
                .hasSize(70);
        assertThat(client.renderCounts.values())
                .as("无重复")
                .allMatch(count -> count == 1);
    }

    /** 生成一条消息：先入消息表（INV-5 的顺序），再交给调用方去 XADD。 */
    private ClientMessage publishToTable(StringBuilder expected, long seq) {
        String piece = "段" + seq + "｜";
        expected.append(piece);
        ClientMessage message = ClientMessage.textDelta(seq, "r-1", "b-1", piece, Instant.now());
        table.add(message);
        return message;
    }

    /** 新建连接（不带任何位置参数），读到目标水位后取消 —— 即一次"断网前的在线时段"。 */
    private void connectAndDrainTo(SimClient client, long targetSeq) {
        List<ClientMessage> received = new RedisMessageSubscriber(runtime)
                .messages(session)
                .takeUntil(message -> message.msgSeq() >= targetSeq)
                .collectList()
                .block(TIMEOUT);
        received.forEach(client::onMessage);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 客户端三规则的最小忠实实现：丢弃 / 追加 / 清空→拉取→重建。
     * 判定逻辑直接用生产的 {@link SeqRule} —— 测的是真规则，不是测试自己的复述。
     */
    private static final class SimClient {

        private final List<ClientMessage> table;
        final StringBuilder text = new StringBuilder();
        final Map<Long, Integer> renderCounts = new HashMap<>();
        long lastSeq;
        int gaps;

        SimClient(List<ClientMessage> table) {
            this.table = table;
        }

        void onMessage(ClientMessage message) {
            switch (SeqRule.judge(lastSeq, message.msgSeq())) {
                case DISCARD -> {
                }
                case APPEND -> render(message);
                case GAP -> {
                    gaps++;
                    long cursor = SeqRule.historyCursorAfterGap(lastSeq);
                    // "清空"之后必须跟着"拉取"：从消息表把 (cursor, incoming) 之间补齐
                    table.stream()
                            .filter(row -> row.msgSeq() > cursor
                                    && row.msgSeq() < message.msgSeq())
                            .sorted(Comparator.comparingLong(ClientMessage::msgSeq))
                            .forEach(this::render);
                    render(message);
                }
            }
        }

        private void render(ClientMessage message) {
            renderCounts.merge(message.msgSeq(), 1, Integer::sum);
            text.append(message.fallbackText());
            lastSeq = message.msgSeq();
        }
    }
}
