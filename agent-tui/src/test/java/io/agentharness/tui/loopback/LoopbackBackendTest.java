package io.agentharness.tui.loopback;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.protocol.UserInstruction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackBackendTest {

    private static final SessionRef SESSION = SessionRef.of("u1", "s1");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LoopbackBackend backend;

    @BeforeEach
    void setUp() {
        backend = new LoopbackBackend(Duration.ofMillis(1), Duration.ofMillis(5));
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void msgSeq单调无洞_客户端的空窗判定依赖这一点() {
        send("你好");
        List<ClientMessage> messages = collectMessages();

        assertThat(messages).isNotEmpty();
        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i).msgSeq()).isEqualTo(i + 1L);
        }
    }

    @Test
    void 用户消息排在助手输出之前_且序号为1() {
        send("你好");
        List<ClientMessage> messages = collectMessages();

        assertThat(messages.get(0).fromUser()).isTrue();
        assertThat(messages.get(0).msgSeq()).isEqualTo(1);
        assertThat(messages.get(0).fallbackText()).isEqualTo("你好");
        assertThat(messages.get(1).fromUser()).isFalse();
    }

    @Test
    void 文本delta按顺序到达_拼起来就是完整回复() {
        send("你好");
        List<ClientMessage> messages = collectMessages();

        String assembled = messages.stream()
                .filter(m -> m.type() == MessageType.TEXT_DELTA)
                .map(ClientMessage::fallbackText)
                .reduce("", String::concat);

        assertThat(assembled).contains("本地假引擎").contains("酒店").contains("报错");
    }

    @Test
    void 酒店剧本按文本_工具_卡片_文本的顺序到达() {
        send("订酒店");
        List<ClientMessage> messages = collectMessages();

        List<MessageType> milestones = messages.stream()
                .map(ClientMessage::type)
                .filter(type -> type != MessageType.TEXT_DELTA && type != MessageType.TEXT_END)
                .toList();

        // 第一条是用户自己的消息 —— 它也由后端推流，客户端收到后才回显
        assertThat(milestones).containsExactly(
                MessageType.TEXT, MessageType.TOOL_CALL, MessageType.TOOL_RESULT, MessageType.CARD);
    }

    @Test
    void 卡片payload在生成时冻结_含数据时间() {
        send("订酒店");
        ClientMessage card = collectMessages().stream()
                .filter(m -> m.type() == MessageType.CARD)
                .findFirst()
                .orElseThrow();

        assertThat(card.payloadValue("dataAsOf")).isEqualTo("2026-08-23 10:00");
        assertThat(card.payload()).containsKey("items");
    }

    @Test
    void 新订阅者拿到窗口内全部消息_对应建连全量重放() {
        send("你好");

        List<ClientMessage> replayed = backend.messages(SESSION)
                .take(Duration.ofMillis(200))
                .collectList()
                .block(TIMEOUT);

        assertThat(replayed).isNotEmpty();
        assertThat(replayed.get(0).msgSeq()).isEqualTo(1);
    }

    @Test
    void 控制流先给快照再给后续帧() {
        var frame = backend.control(SESSION).blockFirst(TIMEOUT);

        assertThat(frame).isNotNull();
        assertThat(frame.turnActive()).isFalse();
        assertThat(frame.phase()).isEqualTo(TurnPhase.IDLE);
    }

    @Test
    void 失败剧本以FAILED收尾且带用户可读原因() {
        send("报错");
        List<ClientMessage> messages = collectMessages();

        assertThat(messages).last()
                .satisfies(m -> assertThat(m.type()).isEqualTo(MessageType.ERROR))
                .satisfies(m -> assertThat(m.fallbackText()).contains("hotel-search"));
    }

    @Test
    void 目标turn不存在的停止指令被丢弃_不会误杀新turn() {
        // 空闲状态下发一条停止：必须什么都不发生
        backend.send(SESSION, UserInstruction.cancel("i-x", "r-999", Instant.now())).block(TIMEOUT);

        var frame = backend.control(SESSION).blockFirst(TIMEOUT);
        assertThat(frame).isNotNull();
        assertThat(frame.turnActive()).isFalse();
    }

    /**
     * 发一条消息并等这一轮跑完。
     *
     * <p>必须**先订阅控制流再投递**：控制流是 replay-latest 语义，
     * 投递之后再订阅可能只看到最终态，于是"等 turn 结束"这件事本身会漏掉起始帧。
     * 这正是真实客户端建连时要处理的同一个问题。
     */
    private void send(String text) {
        CompletableFuture<ControlFrame> turnEnded = backend.control(SESSION)
                .skipUntil(ControlFrame::turnActive)
                .filter(frame -> !frame.turnActive())
                .next()
                .toFuture();

        backend.send(SESSION, UserInstruction.message("i-" + text, text, Instant.now())).block(TIMEOUT);

        try {
            turnEnded.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 turn 结束被中断", e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("turn 未在超时内结束", e);
        }
    }

    private List<ClientMessage> collectMessages() {
        return backend.messages(SESSION).take(Duration.ofMillis(80)).collectList().block(TIMEOUT);
    }
}
