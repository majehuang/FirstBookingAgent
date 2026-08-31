package io.agentharness.redis;

/**
 * 摘牌结果。三种，<b>只有一种允许交差</b>。
 *
 * <p>这个枚举的存在本身就是 INV-4 的一半：如果摘牌返回的是 {@code boolean}，
 * "仍有工作"和"非持有者"就会被压成同一个 false，而这两者的正确处置完全相反 ——
 * 前者要继续干，后者要立刻停手。
 */
public enum UnleaseOutcome {

    /**
     * 已摘牌：确实是持有者，inbox 也确实抽干了。<b>这是唯一可以 {@code XACK} 的分支。</b>
     */
    UNLEASED(1),

    /**
     * 仍有工作：游标之后还有 inbox 条目。lease 保留在手上。
     *
     * <p>正常且预期的结果 —— 消息恰好在抽干与摘牌之间到达。
     * 处置是<b>回去继续 drain</b>，不是重试摘牌，更不是交差：
     * 交差了那条新消息就没有唤醒令牌了。
     */
    WORK_PENDING(0),

    /**
     * 非持有者：lease 不见了或者已经是别人的值。
     *
     * <p>意味着我们的牌子在执行期间过期了 —— 刚才那段可能一直在裸奔，
     * 而现在很可能已经有另一个 pod 在跑同一个 session。
     * 处置是<b>停手且不交差</b>：令牌留在 PEL 里，让回收机制决定它的去向（LSE-009）。
     */
    NOT_HOLDER(-1);

    private final int code;

    UnleaseOutcome(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 只有摘牌成功才允许 {@code XACK}（INV-4 / LSE-005）。 */
    public boolean mayAck() {
        return this == UNLEASED;
    }

    static UnleaseOutcome fromCode(long code) {
        for (UnleaseOutcome outcome : values()) {
            if (outcome.code == code) {
                return outcome;
            }
        }
        throw new RedisException("摘牌脚本返回了未知的返回码：" + code);
    }
}
