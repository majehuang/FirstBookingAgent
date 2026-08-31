package io.agentharness.task.health;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

/**
 * 健康检查的输出。
 *
 * <p><b>刻意不走 slf4j</b>，理由与 {@link io.agentharness.task.worker.TurnLog} 完全一样：
 * {@code agent-cli} 绑的是 {@code slf4j-nop}，所有 slf4j 输出都会被丢掉。
 * 一个"打了 WARN 就算有告警"的健康检查，在这个部署形态下什么都观测不到 ——
 * 而它恰恰是用来观测那些<b>本来就没有报错</b>的静默故障的。
 *
 * <h2>健康时不出声</h2>
 * 不打"一切正常"的心跳行：那种输出跑久了没人看，真出问题时反而淹在里面。
 * 代价是"检查还在跑吗"没法从输出确认，所以检查循环自己挂掉时必须单独喊一声
 * （见 {@code ReadyDispatcher} 的错误处理）。
 *
 * <h2>走 stderr</h2>
 * 与链路追踪同一个理由（开发规划 D 节）：worker 的 stdout 是<b>一轮一行</b>的常规输出，
 * 可以直接 diff 来做验收；告警掺进去就没法比对了。
 * 交互式终端上两者都可见，不影响观察。
 */
@FunctionalInterface
public interface HealthLog {

    /**
     * 报告一次检查结果。
     *
     * @param snapshot 快照
     * @param concerns 值得看一眼的事情；<b>空列表时不会被调用</b>
     */
    void concernsFound(QueueHealth snapshot, List<String> concerns);

    /** 什么都不打。内嵌在会话进程里的 worker 用它 —— 告警混进滚动区会盖住回复本身。 */
    static HealthLog disabled() {
        return (snapshot, concerns) -> {
        };
    }

    static HealthLog toStderr() {
        return toStream(System.err);
    }

    static HealthLog toStream(PrintStream out) {
        return (snapshot, concerns) -> {
            out.println("⚠ 队列健康检查发现 " + concerns.size() + " 项异常");
            out.println("  " + snapshot.summary());
            concerns.forEach(concern -> out.println("  · " + concern));
        };
    }

    /**
     * 采样后判定并输出。把"要不要打"的判断收在一处，
     * 免得每个调用点各写一遍 {@code if (concerns.isEmpty())}。
     *
     * @return 本次发现的异常条数，便于调用方计数或测试断言
     */
    default int report(QueueHealth snapshot, Duration maxLeaseHold) {
        List<String> concerns = snapshot.concerns(maxLeaseHold);
        if (!concerns.isEmpty()) {
            concernsFound(snapshot, concerns);
        }
        return concerns.size();
    }
}
