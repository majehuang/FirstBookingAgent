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
    @DisplayName("TOOL-004 空列表被拒绝 —— 不生成空卡片")
    void 空列表返回结构化错误() {
        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", List.of(), null, null);

        assertThat(result).containsKey("error").doesNotContainKey("card");
        assertThat(result.get("error")).asString().contains("search_hotels");
    }

    @Test
    @DisplayName("TOOL-004 超上限被拒绝 —— 不静默截断")
    void 超过上限返回结构化错误() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> "h-" + i).toList();

        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", tooMany, null, null);

        // 静默截断会让 shown 摘要与用户实际看到的对不上，模型会引用没显示出来的酒店
        assertThat(result).containsKey("error").doesNotContainKey("card");
        assertThat(result.get("error")).asString().contains("最多展示 5 家");
    }

    @Test
    @DisplayName("TOOL-005 重复 id 去重保留首次，结果确定")
    void 重复id被去重() {
        Map<String, Object> result = new HotelCardTool(source).sendHotelCards(
                "标题", List.of("h-guomao", "h-atour", "h-guomao"), null, null);

        assertThat(itemsOf(result)).hasSize(2);
        assertThat(itemsOf(result)).extracting(item -> item.get("name"))
                .containsExactly("北京国贸大酒店", "东直门亚朵");
        // shown 摘要必须与最终卡片一致
        assertThat(result.get("shown")).asString().contains("2 张");
    }

    @Test
    void 去重后正好卡在上限上是合法的() {
        List<String> withDuplicates = List.of(
                "h-guomao", "h-guomao", "h-hilton", "h-atour", "h-jinjiang");

        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", withDuplicates, null, null);

        assertThat(result).doesNotContainKey("error");
        assertThat(itemsOf(result)).hasSize(4);
    }

    @Test
    void id全部查不到时也返回错误而不是空卡片() {
        Map<String, Object> result = new HotelCardTool(source)
                .sendHotelCards("标题", List.of("不存在-1", "不存在-2"), null, null);

        assertThat(result).containsKey("error").doesNotContainKey("card");
    }

    @Test
    @DisplayName("入参不合法时 middleware 不进补全服务 —— 白查一次没有意义")
    void 非法入参不触发查库() {
        long before = source.lookupCount();

        new HotelEnrichmentMiddleware(source).enrich(new ActingInput(List.of(
                new ToolUseBlock("c-1", "send_hotel_cards", Map.of("hotelIds", List.of())))));

        assertThat(source.lookupCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("MID-004 重建时保留原有 metadata，且不改动原对象")
    void 补全保留原metadata且不修改入参() {
        Map<String, Object> metadata = Map.of("traceId", "t-1");
        ToolUseBlock original = new ToolUseBlock("c-1", "send_hotel_cards",
                Map.of("hotelIds", List.of("h-guomao")), null, metadata);
        ActingInput input = new ActingInput(List.of(original));

        ActingInput enriched = new HotelEnrichmentMiddleware(source).enrich(input);

        ToolUseBlock rebuilt = enriched.toolCalls().get(0);
        assertThat(rebuilt).isNotSameAs(original);
        assertThat(rebuilt.getMetadata()).containsEntry("traceId", "t-1");
        assertThat(rebuilt.getInput()).containsKey("resolved");
        // 原对象不变
        assertThat(original.getInput()).doesNotContainKey("resolved");
        assertThat(input.toolCalls().get(0)).isSameAs(original);
    }

    @Test
    @DisplayName("重建后 content 与 input 一致 —— 校验和绑定走的是 content")
    void 重建同时更新content() {
        ActingInput enriched = new HotelEnrichmentMiddleware(source).enrich(new ActingInput(List.of(
                new ToolUseBlock("c-1", "send_hotel_cards",
                        Map.of("hotelIds", List.of("h-guomao"))))));

        String content = enriched.toolCalls().get(0).getContent();

        assertThat(content).isNotNull().contains("resolved").contains("dataAsOf");
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
