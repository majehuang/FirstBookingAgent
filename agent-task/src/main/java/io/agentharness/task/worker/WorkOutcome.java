package io.agentharness.task.worker;

/**
 * 一个 ready 令牌被处理之后的结局 —— <b>它唯一的用途是决定要不要 {@code XACK}</b>（INV-4）。
 *
 * <p>把这个决定做成枚举而不是 {@code boolean}，是因为"不能交差"有好几种原因，
 * 而它们在排查时的含义完全不同：{@link #LEASE_LOST} 说明这个 pod 掉队了，
 * {@link #WORK_PENDING} 说明消息来得比处理得快，{@link #NOT_HOLDER} 说明牌子被抢了。
 * 压成一个 false 之后，监控上只会看到"有令牌没被 ACK"，而这三种要查的地方各不相同。
 *
 * <h2>交差为什么必须排在摘牌之后</h2>
 * {@code XACK} 一旦执行，令牌就从 PEL 里消失了 —— 它不再是任何回收机制的目标。
 * 所以先交差再摘牌的话，崩在两者之间就<b>什么线索都不剩</b>：
 * ready 里没有它，PEL 里没有它，只有一个还挂着的 lease 和一个再也不会被唤醒的 session。
 * 反过来（先摘牌后交差）崩在中间，最坏是令牌被重新投递一次 ——
 * 而重复投递有幂等兜底，重复本来就是设计允许的。
 */
public enum WorkOutcome {

    /** 抽干并成功摘牌。<b>唯一可以交差的结局。</b> */
    COMPLETED(true),

    /**
     * 牌子在别人手上，本次没做事。
     *
     * <p><b>不交差。</b>
     *
     * <h3>曾经交差过，而那是错的</h3>
     * 原先的理由是：持牌方在摘牌前必定确认 inbox 已抽干（INV-2），
     * 所以这条令牌对应的消息一定会被它处理掉，留着令牌只会让 PEL 堆积重复条目。
     *
     * <p><b>这个推理默认了持牌方会跑完。</b>持牌方中途丢牌、报错或崩溃时它并不会抽干 ——
     * 而那一刻我们已经把唤醒扔掉了。于是：inbox 里有活儿、游标没推进、
     * 牌子过期后没人持有、<b>PEL 里一个令牌都不剩</b>。
     * 没有任何机制会再来碰这个 session，它就此永久失聪。
     *
     * <p>这不是推演出来的：多 worker 压力测试稳定复现，现场就是
     * {@code inbox=[e0,e1,e2] cursor=null lease=null PEL=[]}。
     *
     * <h3>不交差的代价，以及为什么可以接受</h3>
     * 令牌留在 PEL 里，长 turn 期间会被回收反复重投，制造一些无效唤醒 ——
     * 每次重投的结果都是"又发现牌子在别人手上"。这有成本但<b>有界且无害</b>：
     * 重复唤醒本来就是设计允许的，抢不到牌就走人是最廉价的一条路径。
     *
     * <p>持牌方正常跑完时，它自己那条令牌会带着抽干的结论交差；
     * 我们这条多余的令牌随后被重投一次，发现 inbox 已空、摘牌成功、正常交差。收敛。
     *
     * <p><b>用一点 PEL 噪音换掉一整类永久卡死，是划算的。</b>
     */
    HELD_BY_OTHER(false),

    /**
     * 反复抽干后 inbox 仍然非空，主动让出。
     *
     * <p>不交差：这个 session 还有活儿，令牌留在 PEL 里等回收重新投递，
     * 让别的 pod（或下一轮的自己）接着干。
     */
    WORK_PENDING(false),

    /**
     * turn 进行中续租失败，执行权已经不属于我们。
     *
     * <p>不交差（LSE-009）：未完成的工作必须还能被接管，而接管的入口就是这条 PEL 记录。
     */
    LEASE_LOST(false),

    /**
     * 摘牌时发现牌子已经不是自己的了 —— 执行期间过期并被别人抢走。
     *
     * <p>不交差，理由同 {@link #LEASE_LOST}。
     */
    NOT_HOLDER(false);

    private final boolean mayAck;

    WorkOutcome(boolean mayAck) {
        this.mayAck = mayAck;
    }

    public boolean mayAck() {
        return mayAck;
    }
}
