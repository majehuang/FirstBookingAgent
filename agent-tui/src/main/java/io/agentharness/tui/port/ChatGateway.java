package io.agentharness.tui.port;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import reactor.core.publisher.Flux;

/**
 * 发消息模块的客户端视角（见 开发规划.md D 节）。
 *
 * <p>两条流对应最终形态的两条 SSE：
 * <ul>
 *   <li>{@link #messages} —— 建连后从 outbox 窗口起点<b>全量重放</b>，不接受任何位置参数。
 *       去重与空窗判定在客户端完成，实现见 {@code state/SeqRule}。</li>
 *   <li>{@link #control} —— 先下发含 ctrlId 水位的完整快照，再从水位之后重放 ctrl-stream。</li>
 * </ul>
 *
 * <p>本轮由进程内 loopback 实现，P1/P5 换成 Redis 实现，接口不变。
 */
public interface ChatGateway {

    Flux<ClientMessage> messages(SessionRef session);

    Flux<ControlFrame> control(SessionRef session);
}
