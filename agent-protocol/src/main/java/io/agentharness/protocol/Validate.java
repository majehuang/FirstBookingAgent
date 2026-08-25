package io.agentharness.protocol;

import java.util.Objects;

/**
 * 协议对象的入参校验。所有 record 的紧凑构造器统一走这里，
 * 保证"非法对象根本构造不出来"，而不是构造出来之后再判断。
 */
public final class Validate {

    private Validate() {
    }

    public static String notBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(field + " 不能为空");
        }
        return value;
    }

    public static String notNull(String value, String field) {
        return Objects.requireNonNull(value, () -> field + " 不能为 null");
    }

    public static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, () -> field + " 不能为 null");
    }

    public static long positive(long value, String field) {
        if (value <= 0) {
            throw new ProtocolException(field + " 必须为正数，实际为 " + value);
        }
        return value;
    }

    public static long notNegative(long value, String field) {
        if (value < 0) {
            throw new ProtocolException(field + " 不能为负数，实际为 " + value);
        }
        return value;
    }

    public static String maxLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new ProtocolException(field + " 超长：上限 " + max + "，实际 " + value.length());
        }
        return value;
    }
}
