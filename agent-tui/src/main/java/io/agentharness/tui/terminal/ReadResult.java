package io.agentharness.tui.terminal;

/**
 * 一次读取的结果。
 *
 * <p>刻意不用异常表达 Ctrl+C / Ctrl+D：这两个是正常控制流而不是错误，
 * 用密封类型让主循环的 switch 必须把它们都处理掉。
 */
public sealed interface ReadResult {

    record Line(String text) implements ReadResult {
    }

    /** Ctrl+C。turn 进行中表示停止，空闲时表示清空当前输入。 */
    record Interrupted() implements ReadResult {
    }

    /** Ctrl+D 或输入流结束。 */
    record EndOfInput() implements ReadResult {
    }
}
