package io.agentharness.task.dispatch;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在飞任务的槽位账本（P3-11 / CAP-001～CAP-007）。
 *
 * <p><b>认领数量 = 空闲槽位数。</b>这条约束不是性能调优，是公平性：
 * 令牌一旦被 {@code XREADGROUP} 取走就进了本 pod 的 PEL，<b>认领即持有</b>。
 * 多领的那些会在自己的队列里干等，而别的空闲 pod 看不到它们 —— ready 流里已经没有了，
 * 它们只能等这个 pod 慢慢消化，或者等 {@code MIN-IDLE} 到期被回收。
 * 也就是说预取制造的不是缓冲，是<b>饥饿</b>：一个 pod 攒着活，其他 pod 闲着。
 *
 * <p>所以空闲为 0 时必须<b>完全停止认领</b>，而不是"先领回来放进程内队列"（CAP-002）——
 * 进程内队列在崩溃时会连同里面的令牌一起消失，而那些令牌的 PEL 记录还在，
 * 要等回收才能救回来，等于把恢复时延加到了每一条排队消息上。
 */
public final class InFlightSlots {

    private final int capacity;
    private final AtomicInteger used = new AtomicInteger();

    public InFlightSlots(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("在飞上限必须为正数，实际是 " + capacity);
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    /** 当前空闲槽位数。这就是下一次认领允许请求的条数。 */
    public int free() {
        return Math.max(0, capacity - used.get());
    }

    public int inFlight() {
        return used.get();
    }

    /**
     * 占一个槽。
     *
     * <p>用 CAS 循环而不是先 {@code get} 再 {@code incrementAndGet}：后者在并发下
     * 会短暂超过上限，而"短暂超过"在这里意味着真的多起了一个 turn。
     *
     * @return 占到了返回 true；已满返回 false
     */
    public boolean tryAcquire() {
        while (true) {
            int current = used.get();
            if (current >= capacity) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 还一个槽。
     *
     * <p>所有终止路径（正常完成、异常、取消、lease lost）都必须<b>恰好</b>调一次（CAP-005）。
     * 少调一次就永久漏一个槽位，多调一次会让计数变负、上限失效 ——
     * 后者更隐蔽，因为它的表现是"并发莫名其妙变高了"。
     * 调用方用 {@code doFinally} 保证恰好一次；这里再钉一道下界防止计数穿底。
     */
    public void release() {
        while (true) {
            int current = used.get();
            if (current <= 0) {
                throw new IllegalStateException(
                        "槽位归还次数多于占用次数 —— 某条终止路径重复调用了 release()");
            }
            if (used.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }
}
