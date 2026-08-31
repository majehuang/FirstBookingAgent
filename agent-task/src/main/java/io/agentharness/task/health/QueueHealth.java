package io.agentharness.task.health;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 唤醒队列的一次健康快照。
 *
 * <h2>为什么需要它</h2>
 * P3 建起来的几道机制有一个共同的坏毛病：<b>失效的时候是静默的。</b>
 *
 * <ul>
 *   <li>{@code XAUTOCLAIM} 停跑 —— Redis 视角一切正常，没有任何错误日志。
 *       令牌就那么一直躺在 PEL 里（官方原话：<i>leave the messages pending forever</i>）</li>
 *   <li>某一轮卡死 —— 续租一拍一拍地续着，牌子永不过期，
 *       此后该会话发什么都没人理，而 lease 在续、令牌在流转、监控全绿</li>
 * </ul>
 *
 * <p>这两种都没法靠"等报错"发现，只能靠<b>主动去看几个数</b>。这个记录就是那几个数。
 *
 * <h2>全局与本机是两组数，不能混着看</h2>
 * 前四项来自 Redis，是<b>整个消费组</b>的状况；后三项是<b>本进程</b>的。
 * 混在一起会得出错误结论 —— 比如"在飞 0 但 PEL 深度 50"，
 * 单看本机像是闲着，实际是别的 pod 忙着（正常），或者是一堆令牌卡在死 pod 名下（不正常）。
 * 分清楚才能判断该查哪边。
 *
 * @param readyLag          全局：已投递但还没被任何消费者读走的令牌数
 * @param pelDepth          全局：所有消费者名下 pending 的总数（在飞 + 滞留）
 * @param oldestPendingIdle 全局：PEL 里最老那条的 idle。<b>回收停跑的唯一信号</b>
 * @param consumerCount     全局：消费组里的消费者数（含已死但还没清理的）
 * @param inFlight          本机：正在跑的 turn 数
 * @param capacity          本机：在飞上限
 * @param longestHeld       本机：持牌最久的那一轮已经持了多久；没有在飞时为 {@code null}
 */
public record QueueHealth(
        long readyLag,
        long pelDepth,
        Duration oldestPendingIdle,
        int consumerCount,
        int inFlight,
        int capacity,
        Duration longestHeld) {

    /**
     * PEL 最老条目 idle 超过它就该查了。
     *
     * <p>取值依据：正常在飞的令牌每 {@code renewInterval}（10s）被心跳刷一次 idle，
     * 死掉的令牌最多 {@code reclaimMinIdle + reclaimInterval}（90s）就该被回收走。
     * 所以稳态下<b>没有任何令牌的 idle 会长期停在几分钟</b>。
     * 5 分钟给足了余量，一旦触达，含义只有一个：回收没在跑，或者跑不动。
     */
    public static final Duration PEL_IDLE_ALARM = Duration.ofMinutes(5);

    /**
     * 判定"这一轮快卡死了"的比例。
     *
     * <p>取持牌上限的八成而不是等它真的触达：触达意味着这一轮已经被放弃、
     * 用户已经白等了一次。八成时报出来，还有机会在现场把它抓下来看是卡在哪儿。
     */
    private static final double HELD_ALARM_RATIO = 0.8;

    /**
     * 值得人看一眼的事情。空列表表示一切正常。
     *
     * <p>刻意<b>不</b>返回一个 boolean：每条 concern 要查的地方完全不同，
     * 压成"健康/不健康"之后，运维拿到的信息还不如没有。
     *
     * @param maxLeaseHold 持牌上限，用来判断有没有 turn 接近卡死
     */
    public List<String> concerns(Duration maxLeaseHold) {
        List<String> concerns = new ArrayList<>();

        if (oldestPendingIdle != null && oldestPendingIdle.compareTo(PEL_IDLE_ALARM) > 0) {
            concerns.add(String.format(
                    "PEL 最老条目已 idle %s（告警线 %s）—— 回收很可能没在跑。"
                            + "Redis 不会自动把 pending 放回队列，这是唯一能看见它的信号",
                    format(oldestPendingIdle), format(PEL_IDLE_ALARM)));
        }

        if (longestHeld != null && maxLeaseHold != null) {
            Duration alarm = scale(maxLeaseHold, HELD_ALARM_RATIO);
            if (longestHeld.compareTo(alarm) > 0) {
                concerns.add(String.format(
                        "有 turn 已持牌 %s，接近上限 %s —— 再不结束就会被判定卡死并放弃执行权。"
                                + "趁现在看它卡在哪儿",
                        format(longestHeld), format(maxLeaseHold)));
            }
        }

        if (inFlight >= capacity) {
            concerns.add(String.format(
                    "本机槽位已满（%d/%d）—— 此时不再认领新令牌，积压会留在 ready 里",
                    inFlight, capacity));
        }

        return concerns;
    }

    /** 一行摘要，给周期日志用。 */
    public String summary() {
        return String.format(
                "队列[待投递=%d PEL=%d 最老idle=%s consumer=%d] 本机[在飞=%d/%d 最长持牌=%s]",
                readyLag, pelDepth, format(oldestPendingIdle), consumerCount,
                inFlight, capacity, format(longestHeld));
    }

    private static Duration scale(Duration value, double ratio) {
        return Duration.ofMillis((long) (value.toMillis() * ratio));
    }

    static String format(Duration value) {
        if (value == null) {
            return "-";
        }
        long seconds = value.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m" + (seconds % 60 == 0 ? "" : (seconds % 60) + "s");
        }
        return seconds / 3600 + "h" + (seconds % 3600) / 60 + "m";
    }
}
