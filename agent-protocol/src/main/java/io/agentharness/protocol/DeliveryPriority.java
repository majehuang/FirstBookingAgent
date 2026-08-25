package io.agentharness.protocol;

/**
 * 指令的消费时机。
 *
 * <p>{@link #IMMEDIATE} 由持牌 pod 的 200ms 轮询器扫出来，绕开正常游标推进 ——
 * 打断必须在下一个推理迭代边界生效，不能排在队尾。
 */
public enum DeliveryPriority {
    QUEUED,
    IMMEDIATE
}
