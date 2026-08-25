package io.agentharness.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 协议对象的 JSON 编解码。
 *
 * <p>序列化侧 {@code ORDER_MAP_ENTRIES_BY_KEYS} 是"重开会话逐字节一致"的前提：
 * 载荷经 PostgreSQL 的 jsonb 往返之后键序会变，只有两边都按键排序才可能对上。
 *
 * <p>两个反序列化开关是协议向前兼容的<b>全部</b>依据：
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} —— 新增字段被忽略而不是崩溃</li>
 *   <li>{@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE = true} —— 新增枚举值落到
 *       {@code UNKNOWN} 而不是崩溃</li>
 * </ul>
 * 关掉任何一个，服务端往前走一步就会把老客户端全部打死。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Map 的键按字典序输出。没有它，同一份载荷序列化两次可能得到不同键序，
            // 而 PostgreSQL 的 jsonb 还会再重排一次 —— 逐字节一致从根上不成立
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 未知枚举值落到 @JsonEnumDefaultValue 标注的常量，而不是抛异常。
            // 少了这一行，服务端新增一种消息类型就会让所有老客户端的流当场断掉
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new ProtocolException("序列化失败：" + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        Validate.notBlank(json, "json");
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new ProtocolException("反序列化失败，目标类型 " + type.getSimpleName(), e);
        }
    }
}
