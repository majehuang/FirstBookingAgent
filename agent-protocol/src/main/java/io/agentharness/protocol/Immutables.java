package io.agentharness.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 载荷的<b>递归深冻结</b>，同时把键排成确定顺序。
 *
 * <p>{@code Map.copyOf} 做不到这件事，两个原因都会咬人：
 * <ul>
 *   <li><b>只冻顶层。</b>嵌套的 {@code items} List 与每个 item Map 仍然是外部传进来的那个引用 ——
 *       调用方之后改它，已经"冻结"的卡片内容会跟着变。这类改动没有任何地方会报错，
 *       而消息表里那一行早就写完了，于是内存里的和库里的悄悄对不上。</li>
 *   <li><b>不保证迭代顺序。</b>同一份逻辑内容序列化两次可能得到不同的键序，
 *       "重开会话逐字节一致"从根上不成立。</li>
 * </ul>
 *
 * <p>排序而不是保留插入序，是因为<b>插入序活不过 PostgreSQL 的 jsonb</b> ——
 * jsonb 按自己的规则重排对象键，实测 {@code title → items → dataAsOf} 读回变成
 * {@code items → title → dataAsOf}。只有"按键排序"这一个顺序是两边都能重现的。
 */
public final class Immutables {

    private Immutables() {
    }

    /** 深冻结一个载荷。null 视作空载荷。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> freeze(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        // TreeMap 定序 → LinkedHashMap 固化 → unmodifiable 封口。
        // 不用 Map.copyOf 收尾：它会重新打散顺序，而且不接受 null 值
        Map<String, Object> sorted = new TreeMap<>((Map<String, Object>) source);
        Map<String, Object> frozen = new LinkedHashMap<>(sorted.size());
        sorted.forEach((key, value) -> frozen.put(key, freezeValue(value)));
        return Collections.unmodifiableMap(frozen);
    }

    public static List<Object> freezeList(Collection<?> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Object> frozen = new ArrayList<>(source.size());
        source.forEach(element -> frozen.add(freezeValue(element)));
        return Collections.unmodifiableList(frozen);
    }

    /**
     * 递归冻结一个值。
     *
     * <p>标量（String / Number / Boolean / null）本身不可变，原样返回。
     * 其余类型一律按 Map 或 Collection 处理 —— 载荷来自 JSON，不会有别的形态；
     * 真出现了也只是原样透传，不会静默丢数据。
     */
    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return freeze(typed);
        }
        if (value instanceof Collection<?> collection) {
            return freezeList(collection);
        }
        return value;
    }
}
