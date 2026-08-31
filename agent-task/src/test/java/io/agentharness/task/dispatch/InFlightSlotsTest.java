package io.agentharness.task.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖 Test/P3 的 CAP-001～CAP-005 中不需要真 Redis 的那部分。 */
class InFlightSlotsTest {

    @Test
    @DisplayName("CAP-003 空闲槽位数就是下一次允许认领的条数")
    void 空闲数随占用变化() {
        InFlightSlots slots = new InFlightSlots(4);

        assertThat(slots.free()).isEqualTo(4);
        assertThat(slots.tryAcquire()).isTrue();
        assertThat(slots.free()).isEqualTo(3);
        assertThat(slots.inFlight()).isEqualTo(1);

        slots.release();
        assertThat(slots.free()).isEqualTo(4);
    }

    @Test
    @DisplayName("CAP-002 满载时认领预算为 0 —— 不是少领，是一条都不领")
    void 满载时停止认领() {
        InFlightSlots slots = new InFlightSlots(2);

        assertThat(slots.tryAcquire()).isTrue();
        assertThat(slots.tryAcquire()).isTrue();

        assertThat(slots.tryAcquire()).isFalse();
        assertThat(slots.free()).isZero();
    }

    @Test
    @DisplayName("CAP-004 归还之后立刻可以再占，不等下一轮轮询")
    void 完成即释放() {
        InFlightSlots slots = new InFlightSlots(1);
        assertThat(slots.tryAcquire()).isTrue();
        assertThat(slots.tryAcquire()).isFalse();

        slots.release();

        assertThat(slots.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("CAP-005 归还多于占用时立刻炸 —— 计数穿底会让上限静默失效")
    void 重复归还不被容忍() {
        InFlightSlots slots = new InFlightSlots(2);
        slots.tryAcquire();
        slots.release();

        assertThatThrownBy(slots::release)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复调用");
    }

    @Test
    @DisplayName("CAP-001 并发抢占下在飞数从不越过上限")
    void 并发下不超上限() throws Exception {
        int capacity = 8;
        int threads = 64;
        InFlightSlots slots = new InFlightSlots(capacity);
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (slots.tryAcquire()) {
                            acquired.incrementAndGet();
                            peak.accumulateAndGet(slots.inFlight(), Math::max);
                            slots.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // 先 get 再 increment 的写法会在这里冒出 capacity+1
        assertThat(peak.get()).isLessThanOrEqualTo(capacity);
        assertThat(acquired.get()).isPositive();
        assertThat(slots.inFlight()).isZero();
    }

    @Test
    @DisplayName("上限必须为正 —— 0 意味着这个 pod 永远不干活，那是配置错误不是关闭开关")
    void 上限必须为正() {
        assertThatThrownBy(() -> new InFlightSlots(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InFlightSlots(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
