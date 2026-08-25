package io.agentharness.tui.state;

/** 收到一条消息后对其序号的判定结果。三选一，没有第四种情况。 */
public enum SeqVerdict {

    /** seq ≤ 本地最大：重放重复，丢弃。 */
    DISCARD,
    /** seq == 本地最大 + 1：正常追加。 */
    APPEND,
    /** seq > 本地最大 + 1：出现空窗，必须清空缓冲 → 拉取历史 → 重建 → 再接后续帧。 */
    GAP
}
