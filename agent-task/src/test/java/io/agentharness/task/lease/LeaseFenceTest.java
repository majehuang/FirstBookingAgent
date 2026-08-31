package io.agentharness.task.lease;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P3 的 LSE-007、LSE-008：**续租失败后立即中止，且不再写任何东西。**
 *
 * <p>这里测的是"闸门"这个机制本身。它与真 Redis 无关 —— 落闸的触发条件（续租返回 false）
 * 在 {@code SessionWorker} 里，而落闸之后会发生什么全部由这个类决定。
 */
class LeaseFenceTest {

    @Test
    @DisplayName("LSE-007 落闸后上游被取消，不等它自然结束")
    void 落闸立刻取消上游() {
        LeaseFence fence = new LeaseFence("s-1");
        AtomicBoolean cancelled = new AtomicBoolean();

        Flux<Integer> forever = Flux.range(1, Integer.MAX_VALUE)
                .delayElements(Duration.ofMillis(10))
                .doOnCancel(() -> cancelled.set(true));

        StepVerifier.create(fence.fence(forever))
                .expectNextCount(2)
                .then(() -> fence.trip("续租被拒"))
                // 取消而不是报错：报错会走进 turn 的失败分支，
                // 而失败分支要写一条 ERROR 消息，那正是落闸要禁止的事
                .thenConsumeWhile(ignored -> true)
                .verifyComplete();

        assertThat(cancelled).isTrue();
        assertThat(fence.isLost()).isTrue();
    }

    @Test
    @DisplayName("LSE-008 落闸后的写入被拦下，且副作用根本没发生")
    void 落闸后拒绝写入() {
        LeaseFence fence = new LeaseFence("s-2");
        AtomicInteger writes = new AtomicInteger();
        Mono<String> write = Mono.fromCallable(() -> {
            writes.incrementAndGet();
            return "written";
        });

        // 落闸前照常写
        assertThat(fence.check(write).block()).isEqualTo("written");
        assertThat(writes.get()).isEqualTo(1);

        fence.trip("牌子已被他人抢占");

        StepVerifier.create(fence.check(write))
                .verifyError(LeaseLostException.class);

        // 关键断言：不是"写了之后回滚"，是压根没订阅到那个 Callable
        assertThat(writes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("LSE-008 合批窗口里已在途的那一批也必须被拦住")
    void 在途批次不能漏网() {
        LeaseFence fence = new LeaseFence("s-3");
        AtomicInteger persisted = new AtomicInteger();

        // 模拟 OutboxWriter：先攒批，再逐批经闸门落库
        Flux<List<Integer>> batches = Flux.just(1, 2, 3, 4)
                .bufferTimeout(2, Duration.ofMillis(20));

        StepVerifier.create(batches
                        .concatMap(batch -> fence.check(
                                Mono.fromCallable(() -> persisted.addAndGet(batch.size()))))
                        // concatMap 是串行的：这个回调跑完，第二批才会被订阅。
                        // 也就是"第一批已落库、第二批还在管道里飞着"的那一刻牌子丢了
                        .doOnNext(total -> fence.trip("第一批之后续租失败")))
                .expectNext(2)
                .verifyError(LeaseLostException.class);

        // 只有闸门落下前的那一批进了库
        assertThat(persisted.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("落闸幂等 —— 续租任务与业务链路可能同时发现")
    void 重复落闸只记第一次原因() {
        LeaseFence fence = new LeaseFence("s-4");

        fence.trip("第一个原因");
        fence.trip("第二个原因");

        assertThat(fence.reason()).isEqualTo("第一个原因");
    }

    @Test
    @DisplayName("没落闸时闸门完全透明 —— 正常路径不该为这个机制付代价")
    void 未落闸时不改变行为() {
        LeaseFence fence = new LeaseFence("s-5");

        StepVerifier.create(fence.fence(Flux.just("a", "b", "c")))
                .expectNext("a", "b", "c")
                .verifyComplete();
        assertThat(fence.check(Mono.just(42)).block()).isEqualTo(42);
        assertThat(fence.isLost()).isFalse();
    }

    @Test
    @DisplayName("恒开闸门用于不持牌路径 —— 生产路径上出现它就是 bug")
    void 恒开闸门放行一切() {
        WriteGate open = WriteGate.open();

        assertThat(open.check(Mono.just("ok")).block()).isEqualTo("ok");
    }
}
