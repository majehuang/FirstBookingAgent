package io.agentharness.engine;

import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;

import java.util.List;
import java.util.Objects;

/**
 * 一组工具的完整装配：工具本体 + middleware + 渲染器。
 *
 * <p>三者绑在一起交付，是因为<b>一种富消息 = 一个工具 + 一个 middleware + 一个渲染器</b>，
 * 少任何一个都不成立：
 * <ul>
 *   <li>只有工具没有 middleware —— 卡片里的数据是模型编的</li>
 *   <li>只有工具没有渲染器 —— 用户看到的是一行「⚒ 调用了发卡片的函数」</li>
 *   <li>只有渲染器没有工具 —— 模型没有办法表达这种消息</li>
 * </ul>
 * 用一个 record 装起来，接入新富消息时就不会漏掉其中一环。
 */
public record ToolBundle(Toolkit toolkit, List<MiddlewareBase> middlewares,
                         RichMessageRegistry renderers) {

    public ToolBundle {
        Objects.requireNonNull(renderers, "renderers");
        middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
    }

    public static ToolBundle empty() {
        return new ToolBundle(null, List.of(), RichMessageRegistry.empty());
    }

    public boolean hasToolkit() {
        return toolkit != null;
    }
}
