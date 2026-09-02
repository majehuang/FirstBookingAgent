package io.agentharness.task.outbox;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * outbox 保留窗口的裁剪下限（P4-2 / P4-4，INV-6）。
 *
 * <p>outbox 只是<b>重连缓冲</b>，不是真相源（真相源在消息表）。窗口按时间算：
 * 裁剪下限 = {@code now - window}，Stream ID 的毫秒前缀直接可比，不需要另存时间戳。
 *
 * <h2>turnStartId 保护 —— turn 进行中不允许裁剪（INV-6）</h2>
 * 长 turn 的生成时间可以超过保留窗口。没有保护的话，turn 还在进行、
 * 它的前半段就被裁掉了 —— 此时客户端断线重连，重放窗口里只有后半段，
 * 序号出现空洞，只能走"清空 → 拉历史 → 重建"的大路径；turn 越长越容易撞上。
 * 所以裁剪下限要再压一道：<b>不越过当前 turn 的第一条消息</b>。
 *
 * <h2>为什么登记在进程内存里就够了</h2>
 * 同一个 session 的 outbox 只有一个写入者 —— lease 的持有者（INV-3），
 * 而裁剪只发生在写入时。写入者自己登记、自己读取，不存在第二个进程需要看到这个值。
 * 持有者崩溃时登记随进程消失，但那一刻写入也停了，没有登记的裁剪不会发生；
 * 接管方开新 turn 时会登记自己的下限。<b>不需要新的 Redis key。</b>
 *
 * <h2>为什么用精确 MINID 而不是近似（{@code ~}）</h2>
 * 近似裁剪按整个 radix 节点（约百条）丢弃，条目少时<b>根本不触发</b> ——
 * 单 session 的 outbox 恰恰是小流，配近似裁剪等于窗口不生效，
 * 而且不生效是静默的。精确 MINID 的代价只与被裁掉的条数成正比，小流上可以忽略。
 */
public final class OutboxRetention {

    /**
     * 默认保留窗口。
     *
     * <p>计划 P4-4 给的区间是 5–10 分钟：短于 5 分钟对移动端网络切换太苛刻，
     * 长于 10 分钟就开始承担"第二个真相源"的角色 —— 超窗的部分本来就该由消息表兜底。
     */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(10);

    private final Duration window;
    private final Map<String, String> turnFloors = new ConcurrentHashMap<>();

    public OutboxRetention(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("保留窗口必须为正：" + window);
        }
        this.window = window;
    }

    /** 登记当前 turn 的第一条 outbox 条目，此后裁剪不越过它。 */
    public void beginTurn(String sessionId, String entryId) {
        turnFloors.put(sessionId, entryId);
    }

    /** turn 收尾（含失败路径）：撤掉保护，窗口重新只按时间算。 */
    public void endTurn(String sessionId) {
        turnFloors.remove(sessionId);
    }

    /**
     * 本次写入应当使用的 {@code MINID}：窗口起点与 turn 下限中<b>更早</b>的那个。
     *
     * <p>取更早的一侧永远是安全方向 —— 多留只是多占一点内存，少留就是 INV-6 破坏。
     */
    public String minId(String sessionId, long nowMs) {
        long windowStart = Math.max(0, nowMs - window.toMillis());
        String turnFloor = turnFloors.get(sessionId);
        if (turnFloor != null && msPart(turnFloor) < windowStart) {
            return turnFloor;
        }
        return windowStart + "-0";
    }

    /**
     * Stream ID 的毫秒前缀。
     *
     * <p>ID 形如 {@code 1725100000000-5}。只比较毫秒段：同毫秒内的序列号差异
     * 对"要不要保护"没有影响 —— 相等时走窗口起点（{@code <ms>-0}），
     * 它不高于任何同毫秒的条目，仍然是安全方向。
     */
    static long msPart(String entryId) {
        int dash = entryId.indexOf('-');
        return Long.parseLong(dash < 0 ? entryId : entryId.substring(0, dash));
    }
}
