package io.agentharness.tools.hotel;

import io.agentharness.engine.ToolBundle;
import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentscope.core.tool.Toolkit;

import java.util.List;

/**
 * 酒店场景的完整装配。
 *
 * <p>接入一种新富消息时照着这个类抄：一个表达型工具、一个补全 middleware、一个渲染器，
 * 用 {@link ToolBundle} 装在一起交付 —— 三者绑定就不会出现"工具注册了、渲染器忘了"
 * 这种只在联调时才发现的漏装。
 */
public final class HotelTools {

    private static final List<String> DEMO_IDS =
            List.of("h-guomao", "h-hilton", "h-atour", "h-jinjiang");

    private HotelTools() {
    }

    /** 演示数据集的装配。 */
    public static ToolBundle demo() {
        return of(InMemoryHotelSource.demo(), DEMO_IDS);
    }

    public static ToolBundle of(HotelSource source, List<String> searchableIds) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new HotelSearchTool(source, searchableIds));
        toolkit.registerTool(new HotelCardTool(source));

        return new ToolBundle(
                toolkit,
                List.of(new HotelEnrichmentMiddleware(source)),
                RichMessageRegistry.of(new HotelCardRenderer()));
    }
}
