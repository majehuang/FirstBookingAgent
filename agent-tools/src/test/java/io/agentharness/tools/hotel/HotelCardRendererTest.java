package io.agentharness.tools.hotel;

import io.agentharness.engine.MessageDraft;
import io.agentharness.protocol.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P2-1：渲染是纯函数，表驱动可测。 */
class HotelCardRendererTest {

    private final HotelCardRenderer renderer = new HotelCardRenderer();

    private static Map<String, Object> item(String name, String price, String rating) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("price", price);
        item.put("rating", rating);
        return item;
    }

    private static Map<String, Object> toolResult(List<Map<String, Object>> items) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", "为你找到 " + items.size() + " 家酒店");
        card.put("items", items);
        card.put("dataAsOf", "2026-08-24 10:00");
        return Map.of("shown", "已展示", "card", card);
    }

    @Test
    void 渲染成卡片消息_载荷即冻结后的卡片内容() {
        MessageDraft draft = renderer.render("b-1", toolResult(List.of(
                item("北京国贸大酒店", "¥1,280", "4.8★"),
                item("王府井希尔顿", "¥1,050", "4.7★"))));

        assertThat(draft.type()).isEqualTo(MessageType.CARD);
        assertThat(draft.blockKey()).isEqualTo("b-1");
        assertThat(draft.payload()).containsEntry("dataAsOf", "2026-08-24 10:00");
        assertThat(draft.payload()).containsKey("items");
    }

    @Test
    @DisplayName("降级文本必须自己说得清楚 —— 不支持卡片的客户端只剩它")
    void 降级文本含名称价格与数据时间() {
        MessageDraft draft = renderer.render("b-1", toolResult(List.of(
                item("北京国贸大酒店", "¥1,280", "4.8★"))));

        assertThat(draft.text())
                .contains("北京国贸大酒店").contains("¥1,280").contains("4.8★")
                .contains("数据截至 2026-08-24 10:00");
    }

    @Test
    void 条目多时降级文本截断并给出总数() {
        List<Map<String, Object>> many = List.of(
                item("甲", "¥1", "5★"), item("乙", "¥2", "5★"),
                item("丙", "¥3", "5★"), item("丁", "¥4", "5★"));

        String text = renderer.render("b-1", toolResult(many)).text();

        assertThat(text).contains("甲").contains("乙").contains("丙")
                .doesNotContain("丁").contains("共 4 家");
    }

    @Test
    void 空条目时降级为标题() {
        assertThat(renderer.render("b-1", toolResult(List.of())).text())
                .isEqualTo("为你找到 0 家酒店");
    }

    @Test
    @DisplayName("结构不认识时返回 null，由调用方退回普通工具结果 —— 绝不抛异常")
    void 无法渲染时返回null() {
        assertThat(renderer.render("b-1", Map.of("shown", "只有摘要"))).isNull();
        assertThat(renderer.render("b-1", Map.of("card", "不是对象"))).isNull();
        assertThat(renderer.render("b-1", Map.of())).isNull();
        assertThat(renderer.render("b-1", null)).isNull();
    }

    @Test
    void 缺数据时间时不输出括号里的null() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", "酒店");
        card.put("items", List.of(item("甲", "¥1", "5★")));

        String text = renderer.render("b-1", Map.of("card", card)).text();

        assertThat(text).doesNotContain("null").doesNotContain("数据截至");
    }

    @Test
    void 工具名与middleware一致() {
        assertThat(renderer.toolName()).isEqualTo("send_hotel_cards");
    }
}
