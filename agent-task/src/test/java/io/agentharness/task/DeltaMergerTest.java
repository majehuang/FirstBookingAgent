package io.agentharness.task;

import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.store.message.PendingMessage;
import io.agentharness.task.outbox.DeltaMerger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P1 的 OUT-003 ～ OUT-006。
 *
 * <p>合并规则错一个维度的后果都不一样，所以每个维度单独立一条用例 ——
 * 只测"能合并"和"不能合并"两种情况，改错任意一个判据都不会红。
 */
class DeltaMergerTest {

    private static final Instant T1 = Instant.parse("2026-08-24T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-24T10:00:01Z");
    private static final Instant T3 = Instant.parse("2026-08-24T10:00:02Z");

    private static PendingMessage delta(String replyId, String blockId, String text, Instant at) {
        return new PendingMessage(replyId, blockId, MessageRole.ASSISTANT,
                MessageType.TEXT_DELTA, text, Map.of(), at);
    }

    @Test
    @DisplayName("OUT-003 相邻同块 delta 合并为一条，时间取最后一段")
    void 相邻同块合并() {
        List<PendingMessage> merged = DeltaMerger.merge(List.of(
                delta("r-1", "b-1", "A", T1),
                delta("r-1", "b-1", "B", T2),
                delta("r-1", "b-1", "C", T3)));

        assertThat(merged).singleElement()
                .satisfies(m -> assertThat(m.fallbackText()).isEqualTo("ABC"))
                // 合并后的这一条代表"到此刻为止"的内容，时间必须是最后一段的
                .satisfies(m -> assertThat(m.createdAt()).isEqualTo(T3))
                .satisfies(m -> assertThat(m.blockId()).isEqualTo("b-1"));
    }

    @Test
    void OUT_003_输入对象不被修改() {
        PendingMessage first = delta("r-1", "b-1", "A", T1);
        PendingMessage second = delta("r-1", "b-1", "B", T2);

        DeltaMerger.merge(List.of(first, second));

        assertThat(first.fallbackText()).isEqualTo("A");
        assertThat(second.fallbackText()).isEqualTo("B");
    }

    @Test
    @DisplayName("OUT-004 不同 blockId 不合并，顺序保持")
    void 不同块不合并() {
        List<PendingMessage> merged = DeltaMerger.merge(List.of(
                delta("r-1", "b-1", "A", T1),
                delta("r-1", "b-2", "B", T2)));

        assertThat(merged).extracting(PendingMessage::fallbackText).containsExactly("A", "B");
    }

    static Stream<Arguments> 不可合并的维度() {
        return Stream.of(
                Arguments.of("replyId 不同 —— 合了就是把两轮回复粘在一起",
                        delta("r-2", "b-1", "B", T2)),
                Arguments.of("role 不同 —— 合了就是把用户的话并进助手的段落",
                        new PendingMessage("r-1", "b-1", MessageRole.USER,
                                MessageType.TEXT_DELTA, "B", Map.of(), T2)),
                Arguments.of("type 不同 —— 合了就是把卡片的兜底文本并进正文",
                        new PendingMessage("r-1", "b-1", MessageRole.ASSISTANT,
                                MessageType.CARD, "B", Map.of("title", "x"), T2)));
    }

    @ParameterizedTest(name = "OUT-005 {0}")
    @MethodSource("不可合并的维度")
    void 同块但其它维度不同时不合并(String reason, PendingMessage second) {
        List<PendingMessage> merged = DeltaMerger.merge(List.of(
                delta("r-1", "b-1", "A", T1), second));

        assertThat(merged).as(reason).hasSize(2);
        assertThat(merged.get(0).fallbackText()).isEqualTo("A");
    }

    @Test
    @DisplayName("OUT-006 隔了一条工具消息的两段 delta 不能跨过去合并")
    void 非相邻不合并() {
        PendingMessage toolCall = new PendingMessage("r-1", "b-tool", MessageRole.ASSISTANT,
                MessageType.TOOL_CALL, "search", Map.of(), T2);

        List<PendingMessage> merged = DeltaMerger.merge(List.of(
                delta("r-1", "b-1", "A", T1), toolCall, delta("r-1", "b-1", "C", T3)));

        // 合了就等于把工具调用挪到了那段文本后面 —— 交错顺序错乱是最难查的一类 bug
        assertThat(merged).extracting(PendingMessage::fallbackText)
                .containsExactly("A", "search", "C");
    }

    @Test
    void 多段交替时只合并各自相邻的部分() {
        List<PendingMessage> merged = DeltaMerger.merge(List.of(
                delta("r-1", "b-1", "A", T1),
                delta("r-1", "b-1", "B", T1),
                delta("r-1", "b-2", "C", T2),
                delta("r-1", "b-2", "D", T2),
                delta("r-1", "b-1", "E", T3)));

        assertThat(merged).extracting(PendingMessage::fallbackText)
                .containsExactly("AB", "CD", "E");
    }

    @Test
    void 空输入与单条输入原样返回() {
        assertThat(DeltaMerger.merge(List.of())).isEmpty();
        assertThat(DeltaMerger.merge(null)).isEmpty();

        PendingMessage only = delta("r-1", "b-1", "A", T1);
        assertThat(DeltaMerger.merge(List.of(only))).containsExactly(only);
    }

    @Test
    void 合并结果丢弃payload_因为文本增量本来就没有载荷() {
        PendingMessage withPayload = new PendingMessage("r-1", "b-1", MessageRole.ASSISTANT,
                MessageType.TEXT_DELTA, "A", Map.of("stray", "value"), T1);

        List<PendingMessage> merged = DeltaMerger.merge(List.of(
                withPayload, delta("r-1", "b-1", "B", T2)));

        assertThat(merged).singleElement()
                .satisfies(m -> assertThat(m.payload()).isEmpty());
    }
}
