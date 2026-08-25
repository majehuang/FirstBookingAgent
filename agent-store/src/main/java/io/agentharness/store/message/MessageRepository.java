package io.agentharness.store.message;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;

import java.util.List;
import java.util.Optional;

/**
 * 消息表 —— **真相源**。
 *
 * <p>写入顺序在调用方那边固定为「消息表 → outbox → （异步）事件日志」。
 * 反序会导致用户"看到了、刷新后消失"，持久化必须先于可见（INV-5）。
 */
public interface MessageRepository {

    /**
     * 分配一段连续序号。
     *
     * <p><b>生产路径不要直接用它。</b>单独调用意味着"已分配但还没写入"，
     * 一旦后续写入失败，这些序号就永久没有对应的消息 —— 那是消息序列里的空洞（INV-10）。
     * 生产路径一律走 {@link #append}，它把分配与写入放在同一个事务里。
     *
     * <p>保留为公开方法只为诊断用途（{@code agent doctor} 验证分配的原子性）。
     *
     * @param count 需要的个数
     * @return 这一段的<b>第一个</b>序号
     */
    long allocate(SessionRef session, int count);

    /**
     * 分配序号并落库，<b>单事务</b>。
     *
     * <p>要么整批可见、要么序号水位一步都不推进 —— 中间态不存在。
     * 写入失败时事务回滚会把分配器一起回退，所以不会烧掉序号。
     *
     * @return 已经带上序号的消息，顺序与入参一致
     */
    List<ClientMessage> append(SessionRef session, List<PendingMessage> pending);

    /**
     * 落用户自己发的那条消息，带 {@code instructionId} 幂等。
     *
     * <p><b>先查后分配</b>，顺序不能反：先分配序号再发现是重试，那个序号就烧掉了，
     * 消息序列里留下一个洞 —— 而客户端的空窗判定要求序号无洞（INV-10）。
     *
     * <p>「查」与「插」之间的竞态在真实架构里不存在：lease 保证同一 session 同时只有
     * 一个 worker 在跑（INV-3），落用户消息是它的工作。
     *
     * @param instructionId 客户端生成的幂等键，重试时保持不变
     * @return 已存在的那条（重试）或新写入的那条
     */
    UserMessageOutcome appendUserMessage(SessionRef session, String replyId, String blockId,
                                         String text, String instructionId);

    /**
     * @param message  最终落库的那条消息
     * @param inserted true 表示本次新写入，false 表示命中幂等、复用了已有记录
     */
    record UserMessageOutcome(ClientMessage message, boolean inserted) {
    }

    /**
     * 历史拉取。空窗恢复时客户端带本地最大 seq 过来，从它之后开始取。
     * 被重跑作废（superseded）的 replyId 会被过滤掉。
     */
    List<ClientMessage> since(SessionRef session, long sinceSeq, int limit);

    /** 当前水位。0 表示这个 session 还没有任何消息。 */
    long lastSeq(SessionRef session);

    /** 按幂等键找回用户消息。Worker 用它拿到投递侧已经分配好的 replyId 与序号。 */
    Optional<ClientMessage> findByInstruction(SessionRef session, String instructionId);

    /**
     * 原子认领一条指令的 turn。
     *
     * <p>客户端重试会在 inbox 里留下同一个 {@code instructionId} 的第二条指令
     * （第一次 inbox 写成功、ready 写失败，见 INV-1）。Worker 必须只跑一轮，
     * 否则同一句话会被回答两次。
     *
     * <p>用条件更新实现，不是"先查再写"：查与写之间有窗口，而条件更新的受影响行数
     * 就是认领结果，由数据库保证互斥 —— 这也让它在 P3 的多 pod 下继续成立。
     *
     * @return true 表示本次认领成功，应当执行 turn；false 表示已被认领过，跳过
     */
    boolean claimTurn(SessionRef session, String instructionId);

    /**
     * turn 重跑时把旧 replyId 的助手侧输出标记作废。
     *
     * <p><b>不碰用户自己的消息。</b>重跑作废的是助手生成的内容，
     * 用户说过的话不会因为服务端重跑而变成没说过 —— 一起标掉的话，
     * 历史拉取会把用户的提问也过滤掉，重开会话只剩答案没有问题。
     *
     * @return 受影响行数
     */
    int markSuperseded(SessionRef session, String replyId);
}
