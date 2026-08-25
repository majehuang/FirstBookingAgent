package io.agentharness.protocol;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * 消息的归属方。
 *
 * <p>协议 v1.1 新增。此前消息表只收助手侧输出，用户自己的话靠客户端本地回显 ——
 * 单客户端看不出问题，但多端登录时另一台设备看不到你在这台发的话，
 * 而且本地回显与流重放是<b>两套顺序来源</b>，只有一套能被 msgSeq 约束。
 *
 * <p>与 {@link MessageType} 是正交的两个维度：role 回答"谁说的"，type 回答"是什么内容"。
 * 不把 USER 塞进 MessageType 是因为将来用户也可能发图片、发语音，
 * 那时需要的是 {@code (USER, IMAGE)} 而不是一个新的 {@code USER_IMAGE}。
 */
public enum MessageRole {

    USER,
    ASSISTANT,
    /** 系统提示：会话建立、被接管、被停止、出错。 */
    SYSTEM,

    /**
     * 本客户端不认识的归属方。与 {@link MessageType#UNKNOWN} 同理 ——
     * 宁可按系统消息渲染，也不要因为一个枚举值让整条流断掉。
     */
    @JsonEnumDefaultValue
    UNKNOWN
}
