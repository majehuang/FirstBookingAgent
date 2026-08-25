package io.agentharness.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * 命令行入口。
 *
 * <p><b>不带子命令时直接进入会话模式</b>，会话内部的操作走斜杠命令
 * （{@code /new}、{@code /stop}、{@code /status}、{@code /session}、{@code /clear}、{@code /help}）。
 * 单独留一个 {@code chat} 子命令没有意义 —— 它是这个工具的默认用途，
 * 而不是与 worker、doctor 并列的一种模式。
 *
 * <p>子命令与 开发规划.md D 节的模块一一对应，可以各自拉起为独立进程 ——
 * 因为模块之间没有任何直接调用，只经由 Redis 交换数据。
 * 后续接 servlet 时，门面只是把会话模式换成两条 SSE，
 * 其余四个子命令原样变成后台服务的启动入口。
 */
@Command(
        name = "agent",
        mixinStandardHelpOptions = true,
        version = "agent 0.1.0-SNAPSHOT",
        description = "分布式 Agent 服务的命令行形态。不带子命令时进入会话模式",
        subcommands = {
                MigrateCommand.class,
                DoctorCommand.class,
                IngressCommand.class,
                EgressCommand.class,
                DispatcherCommand.class,
                WorkerCommand.class
        })
public final class AgentCli implements Callable<Integer> {

    @Mixin
    private ChatOptions chat = new ChatOptions();

    /**
     * Netty 在 macOS 上找不到原生 DNS resolver 时会打一条 JUL 警告。
     * 它对我们没有影响（连的是 IP 或 localhost），但会盖住自检结果，
     * 让人以为是自检本身出了问题。
     */
    private static void silenceNettyDnsWarning() {
        java.util.logging.Logger.getLogger("io.netty.resolver.dns.DnsServerAddressStreamProviders")
                .setLevel(java.util.logging.Level.SEVERE);
    }

    @Override
    public Integer call() {
        return chat.run();
    }

    public static void main(String[] args) {
        silenceNettyDnsWarning();
        int exitCode = new CommandLine(new AgentCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}
