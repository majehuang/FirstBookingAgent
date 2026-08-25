package io.agentharness.tools.hotel;

import io.agentharness.protocol.Json;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 卡片补全：模型只给酒店 id，服务端把真实数据填进去。
 *
 * <p>两个收益：
 * <ul>
 *   <li><b>卡片里的价格永远来自业务系统。</b>模型即使编了价格也会被覆盖 ——
 *       让模型直接产出展示数据，等于把一个会一本正经胡说的组件放在了用户眼前</li>
 *   <li><b>补全只做一次。</b>结果随卡片冻结落库，重开会话直接读库不重查（D3）</li>
 * </ul>
 *
 * <p><b>⚠ INV-7 就在这个类里。</b>{@link HotelSource} 是阻塞接口，而 middleware 跑在
 * 事件循环线程上。少了 {@code subscribeOn(boundedElastic)} 这一行，
 * 一次查询就会占住事件循环，<b>全 pod 所有 session 一起卡</b> ——
 * 症状是吞吐莫名掉到个位数，而且只在补全真的变慢时才出现。
 *
 * <p>注意工具内部<b>不</b>需要这样做：{@code ToolExecutor} 已经默认
 * {@code subscribeOn(boundedElastic)}。INV-7 只针对 middleware 与 adapter。
 */
public final class HotelEnrichmentMiddleware implements MiddlewareBase {

    /** 服务端填充的两个入参名，与 {@link HotelCardTool} 的参数一一对应。 */
    static final String RESOLVED = "resolved";
    static final String DATA_AS_OF = "dataAsOf";
    static final String HOTEL_IDS = "hotelIds";
    static final String TOOL_NAME = "send_hotel_cards";

    private final HotelSource source;

    public HotelEnrichmentMiddleware(HotelSource source) {
        this.source = source;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!needsEnrichment(input)) {
            // 与本 middleware 无关的工具调用原样放行，不产生任何额外调度开销
            return next.apply(input);
        }
        return Mono.fromCallable(() -> enrich(input))
                // ★ 这一行不能删：HotelSource 是阻塞的，middleware 跑在事件循环线程上
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(next);
    }

    private static boolean needsEnrichment(ActingInput input) {
        return input.toolCalls() != null && input.toolCalls().stream()
                .anyMatch(call -> TOOL_NAME.equals(call.getName()));
    }

    ActingInput enrich(ActingInput input) {
        List<ToolUseBlock> enriched = new ArrayList<>(input.toolCalls().size());
        for (ToolUseBlock call : input.toolCalls()) {
            enriched.add(TOOL_NAME.equals(call.getName()) ? enrichCall(call) : call);
        }
        return new ActingInput(List.copyOf(enriched));
    }

    private ToolUseBlock enrichCall(ToolUseBlock call) {
        Map<String, Object> arguments = new LinkedHashMap<>(
                call.getInput() == null ? Map.of() : call.getInput());

        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Hotel hotel : source.lookup(hotelIds(arguments))) {
            resolved.add(hotel.toCardItem());
        }

        // 覆盖而不是合并：模型填了什么都不算数，展示数据只能来自业务系统
        arguments.put(RESOLVED, resolved);
        arguments.put(DATA_AS_OF, source.dataAsOf());

        // ⚠ 必须同时重建 content。
        //
        // ToolUseBlock 有两份入参：结构化的 input，和模型原始的 JSON 串 content。
        // 参数校验与实际绑定走的是 content —— 只改 input 的话补全根本送不到工具，
        // 而三参构造器会把 content 置空，校验器直接报 argument "content" is null。
        // 这一点在签名上完全看不出来，是实跑真实模型才暴露的。
        return new ToolUseBlock(call.getId(), call.getName(), arguments,
                Json.write(arguments), call.getMetadata());
    }

    /** 入参可能是 List，也可能被模型写成逗号分隔的字符串。两种都认，认不出就返回空。 */
    @SuppressWarnings("unchecked")
    static List<String> hotelIds(Map<String, Object> arguments) {
        Object raw = arguments.get(HOTEL_IDS);
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return List.of(text.split("\\s*,\\s*"));
        }
        return List.of();
    }

    @Override
    public int order() {
        // 补全要在别的 acting middleware 之前跑完，后面的环节才能看到完整入参
        return -100;
    }
}
