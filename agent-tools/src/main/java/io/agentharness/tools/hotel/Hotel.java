package io.agentharness.tools.hotel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一家酒店的业务数据。 */
public record Hotel(String id, String name, String price, String rating, String note) {

    public Hotel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    /**
     * 转成卡片条目。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：卡片条目的字段顺序会影响
     * 序列化后的 JSON，而卡片内容要<b>逐字节</b>可比对（重开会话所见即当时所见）。
     * {@code Map.of} 的迭代顺序不保证稳定。
     */
    public Map<String, Object> toCardItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        putIfPresent(item, "price", price);
        putIfPresent(item, "rating", rating);
        putIfPresent(item, "note", note);
        return item;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
