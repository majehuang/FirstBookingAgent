package io.agentharness.comm.egress;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import reactor.core.publisher.Flux;

/**
 * 发消息模块的对外能力。HTTP 门面接上来之后，两条流分别是
 * {@code GET /sessions/{sid}/events} 与控制 SSE。
 */
public interface MessageSubscriber {

    /**
     * 消息流。
     *
     * <p><b>建连即从 outbox 窗口起点全量重放</b>，不接受也不解析任何位置参数
     * （{@code Last-Event-ID}、{@code since}、{@code cursor} 一律无效）。
     * 服务端不保存客户端位置 —— 去重与空窗判定由客户端按三规则完成。
     */
    Flux<ClientMessage> messages(SessionRef session);

    /** 控制流：先下发含 {@code ctrlId} 水位的快照，再从水位之后重放。 */
    Flux<ControlFrame> control(SessionRef session);
}
