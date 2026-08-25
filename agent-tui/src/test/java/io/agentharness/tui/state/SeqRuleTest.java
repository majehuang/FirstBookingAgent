package io.agentharness.tui.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("客户端三规则（开发规划 B 节）")
class SeqRuleTest {

    @ParameterizedTest(name = "本地最大 {0}，收到 {1} → {2}")
    @CsvSource({
            "10, 5,  DISCARD",
            "10, 10, DISCARD",
            "10, 11, APPEND",
            "10, 12, GAP",
            "10, 99, GAP",
            "0,  1,  APPEND"
    })
    void 三条规则各自的边界(long localMax, long incoming, SeqVerdict expected) {
        assertThat(SeqRule.judge(localMax, incoming)).isEqualTo(expected);
    }

    @Test
    void 首次加载与空窗恢复走同一条路径() {
        // 本地最大 = 0，首帧 seq 必然远大于 1（服务端从 outbox 窗口起点全量重放）
        assertThat(SeqRule.judge(0, 4321)).isEqualTo(SeqVerdict.GAP);
        // 空窗恢复也是 GAP —— 客户端不需要区分这两种情况
        assertThat(SeqRule.judge(4321, 4400)).isEqualTo(SeqVerdict.GAP);
    }

    @Test
    void 空窗后从本地最大处拉取历史_而不是从0拉() {
        assertThat(SeqRule.historyCursorAfterGap(4321)).isEqualTo(4321);
    }

    @Test
    void 重放窗口内的重复消息全部被丢弃_不会重复渲染() {
        long localMax = 100;
        for (long replayed = 1; replayed <= localMax; replayed++) {
            assertThat(SeqRule.judge(localMax, replayed)).isEqualTo(SeqVerdict.DISCARD);
        }
    }
}
