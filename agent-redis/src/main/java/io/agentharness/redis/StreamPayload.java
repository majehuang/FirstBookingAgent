package io.agentharness.redis;

import io.agentharness.protocol.Json;

import java.util.Map;

/**
 * 流条目的载荷编码：<b>整个对象序列化成一个 JSON 字段</b>。
 *
 * <p>不把每个属性拆成独立的 Stream 字段，是为了让协议演进不牵动 Redis 层：
 * 新增一个字段只改 JSON 结构，流的字段名永远是 {@code d}。
 * 拆字段的方案看起来更"Redis 原生"，代价是每加一个协议字段就要同步改编解码两处，
 * 而且老条目缺字段时的兼容处理要自己写 —— Jackson 已经免费提供了。
 */
public final class StreamPayload {

    /** 唯一的字段名。Lua 脚本写 ctrl-stream 时也用它。 */
    public static final String FIELD = "d";

    private StreamPayload() {
    }

    public static Map<String, String> of(Object value) {
        return Map.of(FIELD, Json.write(value));
    }

    public static <T> T read(Map<String, String> body, Class<T> type) {
        String json = body == null ? null : body.get(FIELD);
        if (json == null || json.isBlank()) {
            throw new RedisException("流条目缺少 " + FIELD + " 字段，无法解析为 " + type.getSimpleName());
        }
        return Json.read(json, type);
    }
}
