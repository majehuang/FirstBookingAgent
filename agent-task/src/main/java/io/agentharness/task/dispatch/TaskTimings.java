package io.agentharness.task.dispatch;

import java.time.Duration;

/**
 * 任务层的<b>时间参数契约</b>。
 *
 * <p>集中在一个不可变记录里，是因为这些值之间存在硬约束 —— 分散配置时每一项单独看
 * 都合理，组合起来却会静默失效。构造器把这些约束变成启动期的失败（RCV-003）。
 *
 * <table border="1">
 *   <caption>生产值</caption>
 *   <tr><th>参数</th><th>值</th><th>作用</th></tr>
 *   <tr><td>{@code leaseTtl}</td><td>30s</td><td>执行权过期时间，硬杀后的裸奔上限</td></tr>
 *   <tr><td>{@code renewInterval}</td><td>10s</td><td>续租 + PEL 心跳，同频（HBT-001）</td></tr>
 *   <tr><td>{@code reclaimMinIdle}</td><td>60s</td><td>{@code XAUTOCLAIM MIN-IDLE-TIME}</td></tr>
 *   <tr><td>{@code reclaimInterval}</td><td>30s</td><td>回收周期</td></tr>
 *   <tr><td>{@code consumerIdleThreshold}</td><td>1h</td><td>死 consumer 清理门槛</td></tr>
 *   <tr><td>{@code pollInterval}</td><td>50ms</td><td>ready 非阻塞轮询</td></tr>
 *   <tr><td>{@code shutdownGrace}</td><td>20s</td><td>停机时等在飞 turn 自然跑完的上限</td></tr>
 *   <tr><td>{@code maxLeaseHold}</td><td>15min</td><td>单个 turn 最长持牌时间，超过即判定卡死</td></tr>
 * </table>
 *
 * <h2>硬约束一：{@code reclaimMinIdle} 必须大于 {@code renewInterval}</h2>
 * 回收看的是 idle，而 idle 由心跳按 {@code renewInterval} 重置。两者相等或反过来时，
 * 一个<b>完全健康</b>的 pod 会在两次心跳之间被判定为死亡、令牌被抢走 ——
 * 于是同一个 session 上出现两个 worker，全靠 lease 兜底，而 lease 只是最后一道。
 * 生产值 60s : 10s 留了六倍余量，也就是连丢五次心跳才判死。
 *
 * <h2>硬约束二：{@code reclaimMinIdle} 不能为 0</h2>
 * 填 0 等于"回收所有 pending"，包括此刻正在被处理的那些。这是本文件里唯一一个
 * 写错了之后<b>压力越大越糟</b>的参数：空闲时没有 pending 所以看不出问题，
 * 一忙起来就变成所有在飞任务互相抢令牌。
 *
 * <h2>硬约束三：{@code maxLeaseHold} 必须远大于 {@code leaseTtl}</h2>
 * 这两个参数长得像，管的却是完全不同的事：
 *
 * <ul>
 *   <li>{@code leaseTtl} 管的是<b>持有者死了</b> —— 停止续租，牌子自然过期</li>
 *   <li>{@code maxLeaseHold} 管的是<b>持有者活着但卡死</b> —— 续租还在一拍一拍地续，
 *       牌子因此永不过期</li>
 * </ul>
 *
 * <p>第二种是整个系统里<b>唯一会让 session 永久失聪</b>的状态：牌子一直被占着，
 * 于是用户之后发的每一条消息都会被某个 pod 读到、发现"有人在处理"、然后交差走人。
 * 用户重发多少次都没用 —— 而 TTL 救不了它，因为续租一直在成功。
 *
 * <p>所以持牌上限的取值口径是<b>"没有任何正常 turn 会跑这么久"</b>，
 * 而不是"turn 一般跑多久"。15 分钟对一个多轮工具调用的长 turn 也留足了余量；
 * 真触达它，那就是卡死，不是慢。（开发计划 §6 把 lease 持有时长 P99 &gt; 5 分钟
 * 列为告警线，两者相差三倍，正常波动不会误伤。）

 * <h2>恢复时延的实际上界 —— 90 秒，不是 30 秒</h2>
 * 硬杀之后，接管要等两件事：令牌 idle 达到 {@code reclaimMinIdle}，以及下一次回收周期
 * 到来（最坏等满 {@code reclaimInterval}）。所以上界是
 * <b>{@code reclaimMinIdle + reclaimInterval}</b>，生产值下为 <b>90 秒</b>。
 *
 * <p>lease TTL 从来不是约束项：到第 50 秒时牌子早就过期了，卡住接管的是
 * <b>"没有令牌，就没有人会去抢牌"</b>。规划原文写的"恢复时延 ≈ lease TTL"由此作废 ——
 * <b>2026-08-26 决议采用方案 A</b>：SLA 放宽到 90 秒，参数保持不动。
 *
 * <p>之所以敢放宽，是因为同时补了<b>优雅停机</b>（{@code TurnHandoff}）：
 * 发布与滚动更新会主动交接在飞 turn，接管时延 ≈ 0。90 秒只落在真正的非预期死亡上，
 * 而那类事件的频率远低于发布。反过来说，<b>如果哪天去掉优雅停机，这个 SLA 就必须重议</b> ——
 * 那会让每次发布 × 每个在飞 session 都吃满 90 秒。
 *
 * <p>另一条路（把参数整体调紧到 20s 级）被否决的理由是它同时把
 * lease TTL 压到 10 秒，对 GC 停顿的容忍度下降三倍 —— 恢复时延与停顿容忍度是同一个旋钮。
 */
public record TaskTimings(
        Duration leaseTtl,
        Duration renewInterval,
        Duration reclaimMinIdle,
        Duration reclaimInterval,
        Duration consumerIdleThreshold,
        Duration pollInterval,
        Duration shutdownGrace,
        Duration maxLeaseHold) {

    /** 心跳丢失多少次才判死。改小它之前请先读类注释里的"硬约束一"。 */
    private static final int MIN_MISSED_HEARTBEATS = 2;

    public TaskTimings {
        requirePositive(leaseTtl, "leaseTtl");
        requirePositive(renewInterval, "renewInterval");
        requirePositive(reclaimMinIdle, "reclaimMinIdle");
        requirePositive(reclaimInterval, "reclaimInterval");
        requirePositive(consumerIdleThreshold, "consumerIdleThreshold");
        requirePositive(pollInterval, "pollInterval");
        requirePositive(shutdownGrace, "shutdownGrace");
        requirePositive(maxLeaseHold, "maxLeaseHold");

        if (renewInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException(
                    "续租周期必须短于 lease TTL，否则牌子会在续租之前就过期："
                            + "renewInterval=" + renewInterval + " leaseTtl=" + leaseTtl);
        }

        if (maxLeaseHold.compareTo(leaseTtl) <= 0) {
            throw new IllegalArgumentException(
                    "持牌上限必须远大于 lease TTL —— 它是「卡死」的判据，不是「过期」的判据："
                            + "maxLeaseHold=" + maxLeaseHold + " leaseTtl=" + leaseTtl);
        }

        Duration floor = renewInterval.multipliedBy(MIN_MISSED_HEARTBEATS);
        if (reclaimMinIdle.compareTo(floor) < 0) {
            throw new IllegalArgumentException(
                    "MIN-IDLE-TIME 太小，健康 pod 会在两次心跳之间被误判为死亡："
                            + "reclaimMinIdle=" + reclaimMinIdle
                            + " 至少需要 " + floor + "（心跳周期 " + renewInterval + " 的 "
                            + MIN_MISSED_HEARTBEATS + " 倍）");
        }
    }

    /** 规划 E 节 P3 冻结的生产值。 */
    public static TaskTimings production() {
        return new TaskTimings(
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                Duration.ofHours(1),
                Duration.ofMillis(50),
                Duration.ofSeconds(20),
                Duration.ofMinutes(15));
    }

    /**
     * 等比例缩小的测试值。
     *
     * <p>测试不能等生产级的 60 秒与 1 小时，但也<b>不能各自随手填一组数</b> ——
     * 随手填出来的组合往往正好绕开了生产值里的比例关系，于是测试绿着、生产坏着。
     * 这里按同一个因子缩放，比例关系原样保留，构造器的校验照样跑。
     *
     * @param factor 缩小倍数，例如 100 表示 60s → 600ms
     */
    public static TaskTimings scaledForTests(int factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("缩放因子必须为正：" + factor);
        }
        TaskTimings base = production();
        return new TaskTimings(
                base.leaseTtl.dividedBy(factor),
                base.renewInterval.dividedBy(factor),
                base.reclaimMinIdle.dividedBy(factor),
                base.reclaimInterval.dividedBy(factor),
                base.consumerIdleThreshold.dividedBy(factor),
                base.pollInterval,
                base.shutdownGrace.dividedBy(factor),
                base.maxLeaseHold.dividedBy(factor));
    }

    /**
     * <b>硬杀</b>之后接管的时延上界 —— 对外承诺的 SLA（90 秒）。
     *
     * <p>见类注释最后一段：它<b>不是</b> lease TTL。牌子过期只让新持有者*能*抢，
     * 但没有令牌就没有人会去抢，所以真正的门槛是
     * {@code reclaimMinIdle + reclaimInterval}。
     *
     * <p><b>这条只覆盖非预期的死亡</b>（SIGKILL / OOM / 节点丢失 / 网络分区 /
     * 超过 TTL 的长 GC）。正常停机走 {@code TurnHandoff} 主动交接，接管时延 ≈ 0，
     * 不吃这个上界。
     */
    public Duration worstCaseTakeoverDelay() {
        return reclaimMinIdle.plus(reclaimInterval);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正数，实际是 " + value);
        }
    }
}
