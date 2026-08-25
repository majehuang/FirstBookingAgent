package io.agentharness.tui.port;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;

import java.util.List;

/**
 * 历史拉取 —— 对应 {@code GET /sessions/{sid}/messages?since=&limit=}，直读消息表。
 *
 * <p>空窗恢复的完整动作是「清空缓冲 → <b>拉取历史</b> → 重建 → 再接后续帧」。
 * 中间这一步缺了，用户看到的就是一片空白。
 *
 * <p>被重跑作废（superseded）的 replyId 由实现方过滤，调用方不需要知道这回事。
 */
public interface HistorySource {

    /** @param sinceSeq 客户端本地最大序号，返回的是<b>严格大于</b>它的消息 */
    List<ClientMessage> since(SessionRef session, long sinceSeq, int limit);
}
