package io.agentharness.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话模式的参数面。
 *
 * <p>钉的是两件事：一级命令表就那四条，以及 {@code --sole} 的默认值是"不孤单"——
 * 也就是默认会拉起内嵌 worker。默认反了的话，现场是"消息发出去没有回复"，
 * 而 inbox 里躺着条目、两边日志都干干净净。
 */
class ChatOptionsTest {

    private static CommandLine cli() {
        return new CommandLine(new AgentCli()).setCaseInsensitiveEnumValuesAllowed(true);
    }

    private static boolean sole(String... args) throws Exception {
        ChatOptions options = new ChatOptions();
        new CommandLine(options).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(args);
        java.lang.reflect.Field field = ChatOptions.class.getDeclaredField("sole");
        field.setAccessible(true);
        return (boolean) field.get(options);
    }

    @Test
    @DisplayName("默认带内嵌 worker —— 一个进程就能跑通，不用先开第二个终端")
    void 默认拉起内嵌worker() throws Exception {
        assertThat(sole()).isFalse();
    }

    @Test
    void sole只起客户端() throws Exception {
        assertThat(sole("--sole")).isTrue();
    }

    @Test
    @DisplayName("一级命令只剩四条：chat、worker、migrate、doctor")
    void 一级命令表() {
        assertThat(cli().getSubcommands().keySet())
                .containsExactlyInAnyOrder("chat", "worker", "migrate", "doctor");
    }

    @Test
    @DisplayName("agent 与 agent chat 是同一件事，不是两条路")
    void chat既是子命令也是默认() {
        // getCommand() 是泛型方法，直接塞进 assertThat 会在 Predicate 的几个重载之间歧义
        Object chat = cli().getSubcommands().get("chat").getCommand();
        Object root = cli().getCommand();

        assertThat(chat).isInstanceOf(ChatCommand.class);
        assertThat(root).isInstanceOf(AgentCli.class);
    }

    @Test
    @DisplayName("--redis 已删除：连接串只从 AGENT_REDIS_URI 读")
    void 没有redis选项() {
        assertThat(cli().getCommandSpec().findOption("--redis")).isNull();
        assertThat(RedisEndpoint.resolve()).isNotBlank();
    }
}
