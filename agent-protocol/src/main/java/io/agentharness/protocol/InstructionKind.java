package io.agentharness.protocol;

/**
 * 指令类别。消息与控制统一进 inbox（见 开发规划.md G 节），
 * 靠这个字段加两个游标区分消费时机。
 */
public enum InstructionKind {
    /** 用户消息，由 msg 游标消费。 */
    MESSAGE,
    /** 控制指令（停止、HITL 恢复），由 ctrl 游标消费。 */
    CONTROL
}
