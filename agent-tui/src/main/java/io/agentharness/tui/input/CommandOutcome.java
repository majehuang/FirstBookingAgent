package io.agentharness.tui.input;

import io.agentharness.tui.render.RenderedLine;

import java.util.List;

/**
 * 一条斜杠命令的处理结果。
 *
 * <p>命令处理返回<b>要做什么</b>而不是直接去做，于是这段逻辑变成纯函数、可以直接断言。
 * 副作用（打印、切会话、清屏、退出）留给主循环执行 —— 那部分只有几行 switch。
 */
public sealed interface CommandOutcome {

    /** 打印若干行。 */
    record Print(List<RenderedLine> lines) implements CommandOutcome {

        public Print {
            lines = List.copyOf(lines);
        }

        public static Print of(RenderedLine... lines) {
            return new Print(List.of(lines));
        }
    }

    /** 切换到另一个会话。 */
    record SwitchSession(String sessionId) implements CommandOutcome {
    }

    /** 停止当前回复。 */
    record Interrupt() implements CommandOutcome {
    }

    record ClearScreen() implements CommandOutcome {
    }

    record Quit() implements CommandOutcome {
    }

    /** 什么都不做。 */
    record Nothing() implements CommandOutcome {
    }
}
