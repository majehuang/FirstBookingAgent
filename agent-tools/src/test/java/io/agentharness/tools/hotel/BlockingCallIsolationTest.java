package io.agentharness.tools.hotel;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INV-7 的守卫：<b>middleware 里的阻塞调用必须 offload。</b>
 *
 * <p>违反了平时看不出来 —— 只有补全真的变慢时才暴露，那时症状是
 * "全 pod 所有 session 的吞吐一起掉到个位数"，而现场根本不指向 middleware。
 * 所以必须在测试里当场炸掉。
 *
 * <p>BlockHound 会在 Reactor 的非阻塞线程（parallel 调度器）上检测到
 * {@code Thread.sleep} 这类阻塞调用并抛 {@link BlockingOperationError}。
 * 本类同时给出<b>反证</b>：去掉 offload 之后同一断言确实会红 ——
 * 否则这条测试可能只是永远绿着而已。
 */
class BlockingCallIsolationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 带 30ms 延迟的数据源。没有延迟的话检测不到任何东西。 */
    private static final HotelSource SLOW = new InMemoryHotelSource(
            List.of(new Hotel("h-1", "国贸大酒店", "¥1,280", "4.8★", "含双早")),
            Duration.ofMillis(30), "2026-08-24 10:00");

    private final HotelEnrichmentMiddleware middleware = new HotelEnrichmentMiddleware(SLOW);

    @BeforeAll
    static void installBlockHound() {
        BlockHound.install();
    }

    private static ActingInput cardCall() {
        return new ActingInput(List.of(new ToolUseBlock("call-1",
                HotelEnrichmentMiddleware.TOOL_NAME,
                Map.of("title", "为你找到 1 家酒店", "hotelIds", List.of("h-1")))));
    }

    @Test
    @DisplayName("反证：BlockHound 确实装上了 —— 否则下面那条永远是绿的")
    void 不做offload时阻塞调用会被抓到() {
        assertThatThrownBy(() -> Mono.fromCallable(() -> middleware.enrich(cardCall()))
                .subscribeOn(Schedulers.parallel())
                .block(TIMEOUT))
                .rootCause()
                .isInstanceOf(BlockingOperationError.class);
    }

    @Test
    @DisplayName("补全在事件循环线程上被调用时也不会阻塞它")
    void middleware把阻塞补全挪出了事件循环() {
        AtomicReference<ActingInput> captured = new AtomicReference<>();

        middleware.onActing(null, null, cardCall(), input -> {
                    captured.set(input);
                    return Flux.empty();
                })
                // 模拟事件循环线程：整条链路从 parallel 上订阅
                .subscribeOn(Schedulers.parallel())
                .blockLast(TIMEOUT);

        // 走到这里没抛 BlockingOperationError，就说明 subscribeOn(boundedElastic) 生效了
        Map<String, Object> arguments = captured.get().toolCalls().get(0).getInput();
        assertThat(arguments).containsKey(HotelEnrichmentMiddleware.RESOLVED);
        assertThat(arguments).containsEntry(HotelEnrichmentMiddleware.DATA_AS_OF, "2026-08-24 10:00");
    }

    @Test
    @DisplayName("不相关的工具调用原样放行，不引入额外调度")
    void 非表达型工具不触发补全() {
        AtomicReference<ActingInput> captured = new AtomicReference<>();
        ActingInput other = new ActingInput(List.of(
                new ToolUseBlock("call-2", "search_hotels", Map.of("city", "北京"))));

        middleware.onActing(null, null, other, input -> {
            captured.set(input);
            return Flux.empty();
        }).blockLast(TIMEOUT);

        assertThat(captured.get()).isSameAs(other);
    }
}
