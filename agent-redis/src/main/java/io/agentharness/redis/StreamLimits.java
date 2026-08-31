package io.agentharness.redis;

import io.lettuce.core.XAddArgs;

/**
 * Stream 的裁剪上限 —— <b>集中一处，不许散落成字面量</b>。
 *
 * <p>裁剪阈值散在各个写入点的后果不是风格问题：ready 的上限只要有两个版本，
 * 小的那个会在积压时先动手，而它裁掉的条目<b>可能正在别人的 PEL 里</b>。
 *
 * <h2>为什么 ready 的阈值必须给足（INV-2b）</h2>
 * <b>{@code XTRIM} 不看 PEL。</b>被裁掉的条目如果还挂在某个 consumer 的 pending 列表里，
 * 那条 pending 记录会继续存在、但它指向的实体已经没了 —— {@code XAUTOCLAIM} 只会把它
 * 作为 deleted ID 清理掉，那份工作<b>无声蒸发</b>，Redis 视角一切正常。
 *
 * <p>所以 10 万这个数字不是"够用就行"，它是<b>安全边界</b>：积压深度触达它之前
 * 必须先告警（见 开发计划.md §6 的 PEL 深度 / 最老条目 idle 两条指标）。
 *
 * <p>用 {@code MAXLEN ~}（近似裁剪）而不是精确裁剪：精确裁剪要求 Redis 找到确切的
 * 分界点，代价随 Stream 长度增长；近似裁剪按整个 radix 节点丢弃，多留一些条目，
 * 但耗时恒定。多留的那些对我们只有好处 —— 它们正是 PEL 可能还指着的部分。
 */
public final class StreamLimits {

    /**
     * 全局唤醒队列的条数上限。
     *
     * <p>见类注释：这不是容量估算，是 PEL 的安全边界。调小之前先看监控。
     */
    public static final long READY_MAX_LEN = 100_000L;

    /** 单 session 的指令队列。按条数裁剪，精确的按时间裁剪留给 P4。 */
    public static final long INBOX_MAX_LEN = 10_000L;

    /**
     * 单 session 的客户端消息批。
     *
     * <p>保留窗口 5–10 分钟由 P4 的 {@code turnStartId} 保护接管，这里只是条数兜底。
     */
    public static final long OUTBOX_MAX_LEN = 10_000L;

    private StreamLimits() {
    }

    /** ready 的写入参数。所有写 ready 的地方都必须走这里。 */
    public static XAddArgs ready() {
        return approximate(READY_MAX_LEN);
    }

    /** inbox 的写入参数。 */
    public static XAddArgs inbox() {
        return approximate(INBOX_MAX_LEN);
    }

    private static XAddArgs approximate(long maxLen) {
        return XAddArgs.Builder.maxlen(maxLen).approximateTrimming();
    }
}
