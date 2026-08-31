package io.agentharness.task.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 周期任务的节拍源。
 *
 * <p>这个类是一次线上级 bug 的产物：三条周期循环（回收、健康检查、续租）
 * 原本都写成 {@code Flux.interval(...).concatMap(work)}。
 * 只要工作有一次跑得比周期长，{@code Flux.interval} 就会以
 * {@code OverflowException} <b>终止整条订阅</b> —— 不是跳过一拍，是再也没有下一拍。
 *
 * <p>它在多 worker 压力测试里稳定复现：回收循环死掉之后，
 * 12 个令牌卡在 PEL 里 idle 涨到 32 秒都没人回收（MIN-IDLE 只有 2 秒），
 * 对应的 session 就此不再被处理。<b>而且全程没有任何业务报错。</b>
 */
class PeriodicTest {

    @Test
    @DisplayName("反证：裸 Flux.interval 遇到慢工作会直接报错终止，而不是跳过")
    void 裸interval会被慢工作打死() throws Exception {
        AtomicReference<Throwable> death = new AtomicReference<>();
        CountDownLatch died = new CountDownLatch(1);

        Flux.interval(Duration.ofMillis(10), Duration.ofMillis(10))
                .concatMap(tick -> Mono.delay(Duration.ofMillis(200)).thenReturn(tick))
                .subscribe(tick -> {
                }, error -> {
                    death.set(error);
                    died.countDown();
                });

        assertThat(died.await(5, TimeUnit.SECONDS))
                .as("裸 interval 确实会死 —— 这条反证不成立的话，下面的修复就没有意义")
                .isTrue();
        assertThat(death.get()).hasMessageContaining("Could not emit tick");
    }

    @Test
    @DisplayName("转绿：Periodic 在同样的场景下丢拍继续跑，不终止")
    void 丢拍而不是终止() throws Exception {
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicReference<Throwable> death = new AtomicReference<>();
        CountDownLatch enough = new CountDownLatch(3);

        var subscription = Periodic.ticks(Duration.ofMillis(10), tick -> skipped.incrementAndGet())
                .concatMap(tick -> Mono.delay(Duration.ofMillis(100)).thenReturn(tick))
                .subscribe(tick -> {
                    done.incrementAndGet();
                    enough.countDown();
                }, death::set);

        try {
            assertThat(enough.await(5, TimeUnit.SECONDS))
                    .as("慢工作照样一轮一轮跑下去").isTrue();
            assertThat(death.get()).as("绝不能终止").isNull();
            assertThat(skipped.get()).as("忙不过来的那些拍被丢掉了").isPositive();
        } finally {
            subscription.dispose();
        }
    }

    @Test
    @DisplayName("工作跑得比周期快时一拍都不丢")
    void 跟得上时不丢拍() throws Exception {
        AtomicInteger skipped = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(5);

        var subscription = Periodic.ticks(Duration.ofMillis(50), tick -> skipped.incrementAndGet())
                .concatMap(tick -> Mono.just(tick))
                .subscribe(tick -> enough.countDown());

        try {
            assertThat(enough.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(skipped.get()).isZero();
        } finally {
            subscription.dispose();
        }
    }
}
