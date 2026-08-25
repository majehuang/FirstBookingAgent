package io.agentharness.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * 会话模式的显式入口。
 *
 * <p>和"不带子命令"完全等价 —— {@code agent} 与 {@code agent chat} 是同一件事。
 * 留这个名字是为了让 {@code --help} 里的一级命令表是完整的：
 * 只写 worker / migrate / doctor 三个的话，主用途反而成了表上看不见的那个。
 */
@Command(name = "chat", mixinStandardHelpOptions = true,
        description = "会话模式（默认）：TUI + 内嵌 worker，同进程，经 Redis 通信")
public final class ChatCommand implements Callable<Integer> {

    @Mixin
    private ChatOptions chat = new ChatOptions();

    @Override
    public Integer call() {
        return chat.run();
    }
}
