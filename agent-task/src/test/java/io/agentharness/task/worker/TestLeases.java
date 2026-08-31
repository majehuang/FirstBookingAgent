package io.agentharness.task.worker;

import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.ScriptRegistry;
import io.agentharness.task.dispatch.TaskTimings;
import io.agentharness.task.lease.LeaseControl;

/**
 * 测试里构造持牌上下文的唯一入口。
 *
 * <p>集中一处是为了不让"忘记 {@code loadScripts()}"这种事在每个测试类里各犯一遍 ——
 * 忘了之后的症状是收尾时报"脚本未加载"，而那和被测行为毫无关系。
 */
final class TestLeases {

    private TestLeases() {
    }

    /**
     * 不带 PEL 心跳的持牌控制：这些测试直接调 Worker，不经由消费组，因此没有令牌可刷。
     *
     * <p>时间参数按 100 倍缩小 —— 比例关系原样保留，见 {@link TaskTimings#scaledForTests(int)}。
     */
    static LeaseControl control(RedisRuntime runtime) {
        ScriptRegistry scripts = new ScriptRegistry(runtime);
        LeaseGuard leases = new LeaseGuard(runtime, scripts);
        leases.loadScripts().block();
        return LeaseControl.withoutHeartbeat(leases, TaskTimings.production());
    }
}
