package io.agentharness.protocol;

import java.time.Instant;

/**
 * 投递到 inbox 的一条指令。
 *
 * <p>{@code instructionId} 是幂等键：投递是两条命令（先 inbox 后 ready），
 * 崩在中间时客户端会带同一个 id 重试，重复由消费侧的幂等检查吃掉（INV-1）。
 * 所以这个 id 必须由**客户端**生成并在重试时保持不变，服务端生成没有意义。
 */
public record UserInstruction(
        String instructionId,
        InstructionKind kind,
        DeliveryPriority priority,
        String text,
        String targetReplyId,
        Instant createdAt) {

    private static final int MAX_TEXT_LENGTH = 32768;

    public UserInstruction {
        Validate.notBlank(instructionId, "instructionId");
        Validate.required(kind, "kind");
        Validate.required(priority, "priority");
        Validate.required(createdAt, "createdAt");
        Validate.maxLength(text, MAX_TEXT_LENGTH, "text");

        if (kind == InstructionKind.MESSAGE) {
            Validate.notBlank(text, "text（MESSAGE 指令）");
        }
        if (kind == InstructionKind.CONTROL) {
            Validate.notBlank(targetReplyId, "targetReplyId（CONTROL 指令）");
        }
    }

    public static UserInstruction message(String instructionId, String text, Instant at) {
        return new UserInstruction(instructionId, InstructionKind.MESSAGE, DeliveryPriority.QUEUED, text, null, at);
    }

    /** 停止指令。走 IMMEDIATE，由持牌 pod 轮询扫出后调 interrupt。 */
    public static UserInstruction cancel(String instructionId, String targetReplyId, Instant at) {
        return new UserInstruction(instructionId, InstructionKind.CONTROL, DeliveryPriority.IMMEDIATE,
                null, targetReplyId, at);
    }
}
