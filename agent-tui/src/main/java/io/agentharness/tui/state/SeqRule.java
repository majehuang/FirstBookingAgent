package io.agentharness.tui.state;

/**
 * 客户端去重与空窗判定 —— 见 开发规划.md B 节那张表。
 *
 * <p>这是**正确性的一部分，不是可选优化**。服务端建连时全量重放窗口内的消息，
 * 不保存也不解析客户端位置，因此重复必然发生，空窗也必然发生；
 * 这三条规则是唯一的收敛手段。
 *
 * <p>第 3 条同时覆盖首次加载：本地最大 = 0 时首帧 seq 必然远大于 1，判定为 GAP，
 * 于是**首次打开与空窗恢复走同一条路径**，调用方不需要区分。
 */
public final class SeqRule {

    private SeqRule() {
    }

    /**
     * @param localMax 本地已渲染的最大 msgSeq，尚未收到任何消息时为 0
     * @param incoming 刚收到的消息 msgSeq
     */
    public static SeqVerdict judge(long localMax, long incoming) {
        if (incoming <= localMax) {
            return SeqVerdict.DISCARD;
        }
        if (incoming == localMax + 1) {
            return SeqVerdict.APPEND;
        }
        return SeqVerdict.GAP;
    }

    /**
     * GAP 之后要向服务端拉取的起点。
     *
     * <p>注意"清空"后面必须跟着"拉取"：只清空会让用户看到空白页。
     */
    public static long historyCursorAfterGap(long localMax) {
        return localMax;
    }
}
