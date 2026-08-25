package io.agentharness.engine.rich;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表达型工具的注册表。
 *
 * <p>{@code EventMapper} 靠它区分两类工具调用：
 * <ul>
 *   <li><b>业务查询工具</b>（{@code search_hotels}）—— 渲染成 {@code ⚒ 正在查询…} 的状态行</li>
 *   <li><b>表达型工具</b>（{@code send_hotel_cards}）—— 工具的<b>入参就是消息内容</b>，
 *       渲染成一条富消息；它的调用状态行反而是噪音，要抑制掉</li>
 * </ul>
 *
 * <p>富消息来源选择工具入参而不是解析模型自由文本，是因为函数调用是模型受训行为、
 * schema 即契约；让模型直接输出消息 JSON 会丧失流式体验、拉低文本质量，还要处理转义地狱。
 */
public final class RichMessageRegistry {

    private static final RichMessageRegistry EMPTY = new RichMessageRegistry(Map.of());

    private final Map<String, RichMessageRenderer> byToolName;

    private RichMessageRegistry(Map<String, RichMessageRenderer> byToolName) {
        this.byToolName = Map.copyOf(byToolName);
    }

    public static RichMessageRegistry empty() {
        return EMPTY;
    }

    public static RichMessageRegistry of(RichMessageRenderer... renderers) {
        return of(List.of(renderers));
    }

    public static RichMessageRegistry of(List<RichMessageRenderer> renderers) {
        Map<String, RichMessageRenderer> index = new LinkedHashMap<>();
        for (RichMessageRenderer renderer : renderers) {
            RichMessageRenderer previous = index.put(renderer.toolName(), renderer);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "工具 " + renderer.toolName() + " 注册了两个渲染器：一种富消息只能有一个");
            }
        }
        return index.isEmpty() ? EMPTY : new RichMessageRegistry(index);
    }

    public boolean isExpressive(String toolName) {
        return toolName != null && byToolName.containsKey(toolName);
    }

    public Optional<RichMessageRenderer> forTool(String toolName) {
        return Optional.ofNullable(toolName == null ? null : byToolName.get(toolName));
    }

    public List<String> toolNames() {
        return List.copyOf(byToolName.keySet());
    }
}
