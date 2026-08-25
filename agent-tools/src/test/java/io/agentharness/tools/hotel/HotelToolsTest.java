package io.agentharness.tools.hotel;

import io.agentharness.engine.ToolBundle;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HotelToolsTest {

    private final InMemoryHotelSource source = InMemoryHotelSource.demo();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result.get("card")).get("items");
    }

    // ---------- 表达型工具 ----------

    @Test
    void 卡片工具返回shown摘要供模型续接() {
        Map<String, Object> result = new HotelCardTool(source).sendHotelCards(
                "为你找到 2 家酒店", List.of("h-guomao", "h-atour"), null, null);

        assertThat(result.get("shown")).asString()
                .contains("2 张").contains("北京国贸大酒店").contains("东直门亚朵");
    }

    @Test
    @DisplayName("middleware 已补全时工具不再查库 —— 补全只做一次")
    void 已补全时不重复查询() {
        List<Map<String, Object>> resolved = List.of(
                new LinkedHashMap<>(Map.of("name", "已补全的酒店", "price", "¥999")));
        long before = source.lookupCount();

        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", List.of("h-guomao"), resolved, "2026-01-01 00:00");

        assertThat(source.lookupCount()).isEqualTo(before);
        assertThat(itemsOf(result)).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("name", "已补全的酒店"));
        assertThat(((Map<String, Object>) result.get("card")).get("dataAsOf"))
                .isEqualTo("2026-01-01 00:00");
    }

    @Test
    @DisplayName("middleware 没装上时工具自己兜底查一次")
    void 未补全时自行查询() {
        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", List.of("h-guomao"), null, null);

        assertThat(itemsOf(result)).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("name", "北京国贸大酒店"));
    }

    @Test
    void 卡片数量被截断_十几张卡片刷屏没有价值() {
        List<Map<String, Object>> tooMany = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> (Map<String, Object>) new LinkedHashMap<String, Object>(
                        Map.of("name", "酒店" + i)))
                .toList();

        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", List.of(), tooMany, "2026-01-01");

        assertThat(itemsOf(result)).hasSize(HotelCardTool.MAX_CARDS);
    }

    @Test
    void 没有可展示的酒店时提示模型先查询() {
        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", List.of("不存在的id"), null, null);

        assertThat(result.get("shown")).asString().contains("search_hotels");
    }

    // ---------- 业务查询工具 ----------

    @Test
    void 查询工具按价格上限过滤() {
        Map<String, Object> result = new HotelSearchTool(source,
                List.of("h-guomao", "h-atour", "h-jinjiang")).searchHotels("北京", 700);

        assertThat(result).containsEntry("count", 2);
        assertThat(result).containsEntry("dataAsOf", source.dataAsOf());
    }

    @Test
    void 价格解析不了时不过滤_不静默丢数据() {
        assertThat(HotelSearchTool.parsePrice("¥1,280")).isEqualTo(1280);
        assertThat(HotelSearchTool.parsePrice("面议")).isNull();
        assertThat(HotelSearchTool.parsePrice(null)).isNull();
        assertThat(HotelSearchTool.withinBudget(
                new Hotel("h", "名", "面议", null, null), 100)).isTrue();
    }

    // ---------- 补全 middleware 的纯逻辑 ----------

    @Test
    @DisplayName("模型填的展示数据一律被覆盖 —— 卡片里的价格只能来自业务系统")
    void 补全覆盖模型提供的内容() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("hotelIds", List.of("h-guomao"));
        arguments.put("resolved", List.of(Map.of("name", "模型编的酒店", "price", "¥1")));

        ActingInput enriched = new HotelEnrichmentMiddleware(source).enrich(
                new ActingInput(List.of(new ToolUseBlock("c-1", "send_hotel_cards", arguments))));

        List<Map<String, Object>> resolved = (List<Map<String, Object>>)
                enriched.toolCalls().get(0).getInput().get("resolved");
        assertThat(resolved).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("name", "北京国贸大酒店"));
    }

    @Test
    void 酒店id两种写法都认() {
        assertThat(HotelEnrichmentMiddleware.hotelIds(Map.of("hotelIds", List.of("a", "b"))))
                .containsExactly("a", "b");
        assertThat(HotelEnrichmentMiddleware.hotelIds(Map.of("hotelIds", "a, b")))
                .containsExactly("a", "b");
        assertThat(HotelEnrichmentMiddleware.hotelIds(Map.of())).isEmpty();
    }

    @Test
    void 只补全表达型工具_同批的其它调用原样保留() {
        ActingInput mixed = new ActingInput(List.of(
                new ToolUseBlock("c-1", "search_hotels", Map.of("city", "北京")),
                new ToolUseBlock("c-2", "send_hotel_cards",
                        Map.of("hotelIds", List.of("h-guomao")))));

        ActingInput enriched = new HotelEnrichmentMiddleware(source).enrich(mixed);

        assertThat(enriched.toolCalls().get(0).getInput()).doesNotContainKey("resolved");
        assertThat(enriched.toolCalls().get(1).getInput()).containsKey("resolved");
    }

    // ---------- 装配 ----------

    @Test
    @DisplayName("一种富消息 = 一个工具 + 一个 middleware + 一个渲染器，三者绑定交付")
    void 装配包三件齐全() {
        ToolBundle bundle = HotelTools.demo();

        assertThat(bundle.hasToolkit()).isTrue();
        assertThat(bundle.toolkit().getToolNames())
                .contains("search_hotels", "send_hotel_cards");
        assertThat(bundle.middlewares()).hasSize(1);
        assertThat(bundle.renderers().toolNames()).containsExactly("send_hotel_cards");
    }

    @Test
    void 数据源延迟可配_用于让INV_7的违反能被测出来() {
        InMemoryHotelSource slow = new InMemoryHotelSource(
                List.of(new Hotel("h", "名", null, null, null)),
                Duration.ofMillis(5), "t");

        long start = System.nanoTime();
        slow.lookup(List.of("h"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(3));
    }
}
