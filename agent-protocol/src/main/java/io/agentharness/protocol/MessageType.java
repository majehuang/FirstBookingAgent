package io.agentharness.protocol;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * 客户端可见消息的类型。
 *
 * <p>协议 v1 的扩展约定：新增类型时客户端可能不认识，因此
 * {@link ClientMessage#fallbackText()} 必填 —— 不认识的类型一律按纯文本降级渲染，
 * 而不是丢弃或报错。
 */
public enum MessageType {

    /**
     * 一条完整文本，不需要拼装。
     *
     * <p>用户发的消息走这个类型 —— 用户不是流式说话的，
     * 硬塞成 TEXT_DELTA + TEXT_END 只是为了复用类型而制造两条记录。
     */
    TEXT,
    /** 文本增量。同一 blockId 的相邻 delta 可以安全合并。 */
    TEXT_DELTA,
    /** 一个文本块结束。 */
    TEXT_END,
    /** 工具开始执行，用于展示"正在查询…"。 */
    TOOL_CALL,
    /** 工具执行结束。 */
    TOOL_RESULT,
    /** 富消息卡片，内容已冻结（见 开发规划.md D3）。 */
    CARD,
    IMAGE,
    AUDIO,
    /** 本轮出错，附带用户可读原因。 */
    ERROR,
    /** 系统提示：会话建立、被接管、被停止等。 */
    SYSTEM,

    /**
     * 本客户端不认识的类型。
     *
     * <p><b>这个常量是向前兼容的落地点。</b>没有它的话，服务端新增一种消息类型，
     * 老客户端在反序列化那一刻就抛异常、整条流断掉 ——
     * {@code fallbackText} 的降级设计根本走不到。
     *
     * <p>{@code @JsonEnumDefaultValue} 让任何不认识的枚举值都落到这里，
     * 于是老客户端把它当文本渲染，会话继续。
     */
    @JsonEnumDefaultValue
    UNKNOWN;

    /** 未知类型的降级判定：客户端遇到不认识的枚举值时按文本处理。 */
    public boolean isTextLike() {
        return this == TEXT || this == TEXT_DELTA || this == TEXT_END
                || this == SYSTEM || this == ERROR || this == UNKNOWN;
    }

    /** 是否为富消息 —— 能力协商时需要客户端显式声明支持才会下发。 */
    public boolean isRich() {
        return this == CARD || this == IMAGE || this == AUDIO;
    }
}
