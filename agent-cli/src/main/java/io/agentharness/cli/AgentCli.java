package io.agentharness.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * 命令行入口。
 *
 * <p><b>运行形态只有两种</b>：{@code chat}（默认，不带子命令即是）与 {@code worker}。
 * {@code migrate} 与 {@code doctor} 是跑完就退的维护工具，不是第三、第四种形态。
 *
 * <ul>
 *   <li>{@code chat} —— TUI 客户端 + 内嵌 worker，同一个进程，两半之间照样走 Redis。
 *       {@code --sole} 只起客户端那一半</li>
 *   <li>{@code worker} —— 只跑推理，不接受输入，每轮打一行日志</li>
 * </ul>
 *
 * <p>会话内部的操作走斜杠命令（{@code /new}、{@code /stop}、{@code /status}、
 * {@code /trace}、{@code /doctor}、{@code /keys} …）。
 *
 * <p>模块之间没有任何直接调用，只经由 Redis 交换数据 —— 后续接 servlet 时，
 * 门面只是把会话模式换成两条 SSE，worker 一行不动。
 */
@Command(
        name = "agent",
        mixinStandardHelpOptions = true,
        version = "agent 0.1.0-SNAPSHOT",
        description = "分布式 Agent 服务的命令行形态。不带子命令时进入会话模式",
        subcommands = {
                ChatCommand.class,
                WorkerCommand.class,
                MigrateCommand.class,
                DoctorCommand.class
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
