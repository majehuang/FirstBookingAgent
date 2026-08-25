package io.agentharness.task.outbox;

import io.agentharness.protocol.MessageType;
import io.agentharness.store.message.PendingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 合并<b>相邻</b>的同块文本增量。纯函数。
 *
 * <p>收益不是省 Redis QPS，而是<b>摊薄信封开销并降低客户端渲染压力</b>：
 * 模型逐 token 吐字，一个 80ms 窗口里可能有几十个两三字的片段，
 * 每个都单独落库、单独进流、单独触发一次客户端渲染，是纯粹的浪费。
 *
 * <p>"相邻"是硬条件。中间隔了一条卡片或工具消息的两段文本不能合并 ——
 * 合了就等于把卡片挪到了那段文本后面，而卡片与文本的交错顺序错乱是最难查的一类 bug。
 */
public final class DeltaMerger {

    private DeltaMerger() {
    }

    public static List<PendingMessage> merge(List<PendingMessage> input) {
        if (input == null || input.size() < 2) {
            return input == null ? List.of() : List.copyOf(input);
        }

        List<PendingMessage> merged = new ArrayList<>(input.size());
        for (PendingMessage current : input) {
            int lastIndex = merged.size() - 1;
            PendingMessage previous = merged.isEmpty() ? null : merged.get(lastIndex);
            if (mergeable(previous, current)) {
                merged.set(lastIndex, join(previous, current));
            } else {
                merged.add(current);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 四个维度全都相同才允许合并。
     *
     * <p>少判一个维度的后果各不相同：漏判 {@code replyId} 会把两轮回复粘在一起；
     * 漏判 {@code role} 会把用户的话并进助手的段落；漏判 {@code blockId} 会把两段
     * 本该分开的文字连成一句；漏判 {@code type} 会把卡片的兜底文本并进正文。
     */
    static boolean mergeable(PendingMessage previous, PendingMessage current) {
        return previous != null
                && previous.type() == MessageType.TEXT_DELTA
                && current.type() == MessageType.TEXT_DELTA
                && previous.replyId().equals(current.replyId())
                && previous.role() == current.role()
                && previous.blockId().equals(current.blockId());
    }

    /** 时间取后者：合并后的这一条代表的是"到此刻为止"的内容。 */
    private static PendingMessage join(PendingMessage previous, PendingMessage current) {
        return new PendingMessage(previous.replyId(), previous.blockId(), previous.role(),
                MessageType.TEXT_DELTA, previous.fallbackText() + current.fallbackText(),
                Map.of(), current.createdAt());
    }
}
