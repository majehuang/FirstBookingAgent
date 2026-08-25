package io.agentharness.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 P2-9 的链路追踪。
 *
 * <p>格式在这里被逐字节钉住，因为它的用途是<b>两个终端并排看</b> ——
 * 列宽一变，客户端与 worker 的输出就对不齐，人得逐行找。
 */
class TraceFormatTest {

    /** 用固定时刻，否则断言里没法写死时间列。 */
    private static final Instant AT = Instant.parse("2026-08-25T02:32:14.802Z")
            .atZone(ZoneId.systemDefault()).toInstant();

    private static String at(String component, TraceStage stage, String session, String detail) {
        return TraceFormat.line(AT, component, stage, session, detail);
    }

    @Test
    @DisplayName("两个进程的输出列对齐 —— 并排看时靠这个扫链路")
    void 不同进程与环节的前缀等宽() {
        // 哨兵得挑标签里不会出现的字符 —— "inbox"/"outbox" 里都有 x
        String tui = at("tui", TraceStage.INBOX_IN, "s-local", "Ω");
        String worker = at("worker", TraceStage.MESSAGE_OUT, "s-local", "Ω");

        assertThat(tui.indexOf('Ω')).isEqualTo(worker.indexOf('Ω'));
    }

    @Test
    void 时间列精确到毫秒() {
        // 毫秒是必须的：同一个 turn 内多个 step 常落在同一秒里，
        // 只到秒的话顺序就看不出来了
        assertThat(at("tui", TraceStage.TURN_START, "s-local", "d"))
                .matches("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}] .*");
    }

    @Test
    void 会话为空时占位而不是留白() {
        assertThat(at("worker", TraceStage.STEP_EVENT, null, "d")).contains("-");
    }

    @Test
    @DisplayName("超长载荷截断并标注截掉多少 —— 静默截断在这里比不打还糟")
    void 超长载荷标注截断量() {
        String payload = "x".repeat(TraceFormat.MAX_DETAIL + 37);

        String clamped = TraceFormat.clamp(payload);

        assertThat(clamped).hasSize(TraceFormat.MAX_DETAIL + "…（还有 37 字符）".length());
        assertThat(clamped).endsWith("…（还有 37 字符）");
    }

    @Test
    void 未超限的载荷原样保留() {
        String payload = "{\"msgSeq\":42}";

        assertThat(TraceFormat.clamp(payload)).isEqualTo(payload);
    }

    @Test
    @DisplayName("换行压平 —— 一条事件必须占且只占一行，否则并排看时错位")
    void 换行被压平() {
        assertThat(TraceFormat.clamp("第一行\n第二行\r\n第三行"))
                .isEqualTo("第一行 第二行 第三行")
                .doesNotContain("\n");
    }

    @Test
    void 空载荷不抛() {
        assertThat(TraceFormat.clamp(null)).isEmpty();
    }

    // ---------- sink ----------

    @Test
    void 每条追踪整行一次写出() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TraceSink sink = new StderrTraceSink("worker",
                new PrintStream(buffer, true, StandardCharsets.UTF_8));

        sink.emit(TraceStage.CTRL_OUT, "s-local", "{\"turnActive\":false}");
        sink.emit(TraceStage.MESSAGE_OUT, "s-local", "{\"msgSeq\":1}");

        assertThat(buffer.toString(StandardCharsets.UTF_8).lines())
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("worker").contains("s-local"));
    }

    @Test
    @DisplayName("关闭时惰性载荷根本不求值 —— 那一步要序列化整条消息")
    void 关闭时不构造载荷() {
        AtomicInteger built = new AtomicInteger();
        TraceSink disabled = TraceSink.disabled();

        disabled.emit(TraceStage.MESSAGE_OUT, "s-local", () -> {
            built.incrementAndGet();
            return "贵得离谱的序列化";
        });

        assertThat(disabled.enabled()).isFalse();
        assertThat(built).hasValue(0);
    }

    @Test
    void 开启时惰性载荷会求值一次() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TraceSink sink = new StderrTraceSink("tui",
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        AtomicInteger built = new AtomicInteger();

        sink.emit(TraceStage.INBOX_IN, "s-local", () -> {
            built.incrementAndGet();
            return "载荷";
        });

        assertThat(built).hasValue(1);
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("载荷");
    }

    @Test
    void 六个环节都有标签且互不相同() {
        assertThat(TraceStage.values()).hasSize(6);
        assertThat(java.util.Arrays.stream(TraceStage.values()).map(TraceStage::label).distinct())
                .hasSize(6);
    }

    @Test
    void 关闭的sink直接调用也不抛() {
        TraceSink.disabled().emit(TraceStage.TURN_START, "s-local", "d");
    }

    @Test
    void toStderr给出可用的sink() {
        assertThat(TraceSink.toStderr("tui").enabled()).isTrue();
    }
}
