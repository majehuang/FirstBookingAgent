package io.agentharness.redis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * {@code XINFO} 系列命令的返回值解析。
 *
 * <p>Lettuce 在这几个命令上不给结构化类型，只返回 {@code Flux<Object>}，
 * 而具体形态取决于协议版本：RESP2 下是扁平的
 * {@code [name, <n>, pending, <p>, ...]}，RESP3 下直接是 Map。
 * 两种都认，因为协议版本取决于连接配置，而调用方不该关心那个。
 */
public final class XInfo {

    private XInfo() {
    }

    /** 把一项转成字段表。认不出的形态返回空表，而不是抛异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toFields(Object entry) {
        if (entry instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (entry instanceof List<?> list) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (int i = 0; i + 1 < list.size(); i += 2) {
                fields.put(String.valueOf(list.get(i)), list.get(i + 1));
            }
            return fields;
        }
        return Map.of();
    }

    /**
     * 解析一个数值字段。<b>认不出来时返回空，而不是 0。</b>
     *
     * <p>这几个字段常常被用来<b>拦截</b>某个动作（例如 {@code pending > 0} 时禁止删除消费者）。
     * 对那类用途来说，"解析失败"的正确含义是"我不知道"，而不是任何一个具体数字 ——
     * 返回 0 会让拦截直接放行，把安全前提悄悄降级成乐观假设。
     */
    public static OptionalLong number(Object value) {
        if (value instanceof Number n) {
            return OptionalLong.of(n.longValue());
        }
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** 数值字段，认不出时用给定的默认值。只用于<b>展示</b>，不要用于安全判定。 */
    public static long numberOr(Object value, long fallback) {
        return number(value).orElse(fallback);
    }

    public static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
