package io.agentharness.tui.input;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 斜杠命令表。新增命令只需要在这里加一项，/help 与补全会自动带上。 */
public enum SlashCommand {

    HELP("help", List.of("h", "?"), "", "显示命令列表"),
    STATUS("status", List.of("st"), "", "打印当前会话与连接状态"),
    STOP("stop", List.of(), "", "停止正在进行的回复（等同 Ctrl+C）"),
    NEW("new", List.of(), "[sessionId]", "开一个新会话，不带参数则随机生成 id"),
    SESSION("session", List.of("sess"), "<sessionId>", "切换到指定会话"),
    CLEAR("clear", List.of("cls"), "", "清屏，不影响服务端历史"),
    QUIT("quit", List.of("exit", "q"), "", "退出");

    private final String name;
    private final List<String> aliases;
    private final String usage;
    private final String description;

    SlashCommand(String name, List<String> aliases, String usage, String description) {
        this.name = name;
        this.aliases = List.copyOf(aliases);
        this.usage = usage;
        this.description = description;
    }

    public String commandName() {
        return name;
    }

    public List<String> aliases() {
        return aliases;
    }

    public String usage() {
        return usage;
    }

    public String description() {
        return description;
    }

    public boolean matches(String token) {
        return name.equalsIgnoreCase(token) || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(token));
    }

    public static Optional<SlashCommand> find(String token) {
        return Arrays.stream(values()).filter(c -> c.matches(token)).findFirst();
    }
}
