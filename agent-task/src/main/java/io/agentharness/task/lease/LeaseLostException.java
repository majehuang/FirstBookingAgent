package io.agentharness.task.lease;

/**
 * 执行权在 turn 进行中丢失。
 *
 * <p><b>这不是"出错了"，是"我们已经不是这个 session 的合法写入者了"。</b>
 * 两者的处置完全不同：普通错误要落一条 ERROR 消息告诉用户这轮失败了，
 * 而 lease 丢失时<b>什么都不该写</b> —— 很可能已经有另一个 pod 接管并正在回复，
 * 我们再写任何东西都是在污染它的输出（LSE-008）。
 */
public final class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
