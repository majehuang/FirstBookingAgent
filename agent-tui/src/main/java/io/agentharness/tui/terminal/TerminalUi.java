package io.agentharness.tui.terminal;

import io.agentharness.tui.render.RenderedLine;

import java.util.List;

/**
 * 终端适配层 —— **整个 TUI 模块里唯一有副作用的接口**。
 *
 * <p>两个实现：交互式的 {@code JLineTerminalUi}，以及非 tty 时的 {@code PlainTerminalUi}。
 * 后者让 {@code echo "你好" | agent} 这种管道用法可以拿来写验收脚本，
 * 不需要伪终端。
 */
public interface TerminalUi extends AutoCloseable {

    /** 阻塞读取一行。实现必须保证读取期间 {@link #printLines} 仍可从其它线程安全调用。 */
    ReadResult readLine(String prompt);

    /** 落地若干完整行。已经打印的行不会被回收。 */
    void printLines(List<RenderedLine> lines);

    /** 刷新未完成的流式尾巴。空串表示清除。非交互实现可忽略。 */
    void setLiveTail(String tail);

    /** 刷新状态行。非交互实现可忽略。 */
    void setStatus(String status);

    /** 当前终端宽度，用于状态行排版。 */
    int width();

    /**
     * 是否为交互式终端。
     *
     * <p>非交互时主循环会在每轮回复结束后才读下一行 —— 管道里的输入不会像人一样等着看结果，
     * 不这样做的话 {@code printf '你好\n' | agent} 会在回复吐出来之前就退出。
     */
    boolean interactive();

    /** 清屏。非交互实现可忽略。 */
    void clearScreen();

    @Override
    void close();
}
