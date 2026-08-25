package io.agentharness.protocol;

/**
 * 投递回执。对应 HTTP 门面的 {@code 202 {replyId, msgSeq}}。
 *
 * <p>只有 inbox 与 ready 两条命令都成功才会返回它 —— 收到 Ack 即表示服务端已接管，
 * 未收到（超时或 5xx）时客户端必须用同一个 instructionId 重试（INV-1）。
 */
public record Ack(String instructionId, String replyId, long msgSeq) {

    public Ack {
        Validate.notBlank(instructionId, "instructionId");
        Validate.notBlank(replyId, "replyId");
        Validate.notNegative(msgSeq, "msgSeq");
    }
}
