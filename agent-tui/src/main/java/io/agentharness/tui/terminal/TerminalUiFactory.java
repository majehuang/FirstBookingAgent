package io.agentharness.tui.terminal;

import java.nio.file.Path;

/**
 * 按运行环境挑选终端实现。
 *
 * <p>自动降级是有意为之：同一条命令在人手里是交互式会话，在脚本里就是可 diff 的纯文本，
 * 不需要两套入口。
 */
public final class TerminalUiFactory {

    private TerminalUiFactory() {
    }

    public static TerminalUi open(Path historyFile, boolean forcePlain) {
        if (forcePlain || !JLineTerminalUi.isInteractive()) {
            return PlainTerminalUi.standard();
        }
        return JLineTerminalUi.open(historyFile);
    }
}
