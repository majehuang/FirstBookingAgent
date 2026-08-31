package io.agentharness.task.lease;

import reactor.core.publisher.Mono;

/**
 * 写入许可 —— 落库/写流之前问一句"我现在还有资格写吗"。
 *
 * <p>做成窄接口而不是让写入侧直接依赖 {@link LeaseFence}：
 * 写入侧只需要"能不能写"这一个信息，不需要知道 lease、令牌、续租周期的存在。
 * 顺带的好处是单测里可以给一个恒开或恒闭的实现，不必构造一整套持牌上下文。
 */
@FunctionalInterface
public interface WriteGate {

    /**
     * 包住一次写入。闸门已关时返回的 Mono 直接以 {@link LeaseLostException} 结束，
     * {@code work} 不会被订阅 —— 副作用因此不会发生。
     */
    <T> Mono<T> check(Mono<T> work);

    /**
     * 恒开的闸门。
     *
     * <p>用于<b>本来就不持牌</b>的路径（单测、诊断工具）。生产路径上出现它就是 bug：
     * 那意味着有一条写入路径不受执行权约束，续租失败后它会继续写下去。
     */
    static WriteGate open() {
        return new WriteGate() {
            @Override
            public <T> Mono<T> check(Mono<T> work) {
                return work;
            }
        };
    }
}
