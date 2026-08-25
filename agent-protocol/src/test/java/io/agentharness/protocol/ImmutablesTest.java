package io.agentharness.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 Test/P2 的 REN-002、REN-003、REN-007。
 *
 * <p>这三条都指向同一个根因：{@code Map.copyOf} 只冻顶层、且不保证迭代顺序。
 * 卡片内容要冻结落库并在重开会话时逐字节比对，两点都不能将就。
 */
class ImmutablesTest {

    private static final Instant AT = Instant.parse("2026-08-25T10:00:00Z");

    /** 一份带嵌套的卡片载荷，故意用可变集合构造。 */
    private static Map<String, Object> mutableCard() {
        Map<String, Object> item = new HashMap<>();
        item.put("name", "北京国贸大酒店");
        item.put("price", "¥1,280");

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item);

        Map<String, Object> card = new HashMap<>();
        card.put("title", "为你找到 1 家酒店");
        card.put("items", items);
        card.put("dataAsOf", "2026-08-25 10:00");
        return card;
    }

    @Test
    @DisplayName("REN-007 嵌套 List 与 item Map 都冻住 —— 浅冻结挡不住这条")
    void 深冻结覆盖嵌套结构() {
        Map<String, Object> frozen = Immutables.freeze(mutableCard());

        assertThatThrownBy(() -> frozen.put("title", "改标题"))
                .isInstanceOf(UnsupportedOperationException.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) frozen.get("items");
        assertThatThrownBy(() -> items.add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> items.get(0).put("price", "¥1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("REN-003 冻结之后改原始集合，已冻结的内容不受影响")
    void 外部引用改不动已冻结的内容() {
        Map<String, Object> source = mutableCard();
        Map<String, Object> frozen = Immutables.freeze(source);

        // 拿着原始引用一通乱改
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceItems = (List<Map<String, Object>>) source.get("items");
        sourceItems.get(0).put("price", "¥9,999");
        sourceItems.add(new HashMap<>(Map.of("name", "凭空多出来的酒店")));
        source.put("title", "被改过的标题");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frozenItems = (List<Map<String, Object>>) frozen.get("items");
        assertThat(frozen).containsEntry("title", "为你找到 1 家酒店");
        assertThat(frozenItems).hasSize(1);
        assertThat(frozenItems.get(0)).containsEntry("price", "¥1,280");
    }

    @Test
    @DisplayName("REN-002 键序确定 —— 换一种 Map 实现、换一次插入顺序，序列化结果都一样")
    void 序列化结果与Map实现和插入顺序无关() {
        Map<String, Object> insertionOrder = new LinkedHashMap<>();
        insertionOrder.put("title", "标题");
        insertionOrder.put("dataAsOf", "2026-08-25");
        insertionOrder.put("items", List.of(Map.of("name", "甲", "price", "¥1")));

        Map<String, Object> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("items", List.of(Map.of("price", "¥1", "name", "甲")));
        reverseOrder.put("dataAsOf", "2026-08-25");
        reverseOrder.put("title", "标题");

        String first = Json.write(Immutables.freeze(insertionOrder));
        String second = Json.write(Immutables.freeze(reverseOrder));

        assertThat(first).isEqualTo(second);
        // 键按字典序：dataAsOf < items < title
        assertThat(first).startsWith("{\"dataAsOf\"").contains("\"items\"").endsWith("\"标题\"}");
    }

    @Test
    void REN_002_同一输入序列化100次逐字节一致() {
        ClientMessage card = new ClientMessage(1, "r-1", "b-1", MessageRole.ASSISTANT,
                MessageType.CARD, "1 家酒店", mutableCard(), AT);

        String baseline = Json.write(card);
        for (int i = 0; i < 100; i++) {
            assertThat(Json.write(card)).isEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("往返之后仍然逐字节一致 —— 这是重开会话不变形的前提")
    void 序列化往返稳定() {
        ClientMessage original = new ClientMessage(1, "r-1", "b-1", MessageRole.ASSISTANT,
                MessageType.CARD, "1 家酒店", mutableCard(), AT);

        String once = Json.write(original);
        String twice = Json.write(Json.read(once, ClientMessage.class));

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void 空载荷与null都退化为空Map() {
        assertThat(Immutables.freeze(null)).isEmpty();
        assertThat(Immutables.freeze(Map.of())).isEmpty();
        assertThat(Immutables.freezeList(null)).isEmpty();
        assertThat(Immutables.freezeList(List.of())).isEmpty();
    }

    @Test
    void 标量原样保留_不做无谓包装() {
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("count", 3);
        mixed.put("enabled", true);
        mixed.put("name", "甲");
        mixed.put("missing", null);

        Map<String, Object> frozen = Immutables.freeze(mixed);

        assertThat(frozen).containsEntry("count", 3)
                .containsEntry("enabled", true)
                .containsEntry("name", "甲")
                .containsEntry("missing", null);
    }

    @Test
    @DisplayName("null 值不能让冻结崩掉 —— Map.copyOf 在这里会直接抛 NPE")
    void 含null值的载荷可以冻结() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("dataAsOf", null);

        assertThat(Immutables.freeze(withNull)).containsEntry("dataAsOf", null);
    }

    @Test
    void 多层嵌套逐层冻结() {
        Map<String, Object> deep = new HashMap<>(Map.of(
                "a", new HashMap<>(Map.of(
                        "b", new ArrayList<>(List.of(
                                new HashMap<>(Map.of("c", "最里面"))))))));

        Map<String, Object> frozen = Immutables.freeze(deep);

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) frozen.get("a");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> b = (List<Map<String, Object>>) a.get("b");

        assertThatThrownBy(() -> b.get(0).put("c", "改了"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
