package io.agentharness.tui.input;

/**
 * 输入行解析。纯函数，不碰终端也不碰网络 —— 因此可以用表驱动的单测覆盖全部分支。
 *
 * <p>一个刻意的取舍：以 {@code /} 开头但不认识的输入返回 {@link InputAction.UnknownCommand}
 * 而不是当作正文发出去。用户打错命令时，把 {@code /statsu} 当成问题发给模型是最糟的结果。
 */
public final class InputParser {

    private static final char PREFIX = '/';

    private InputParser() {
    }

    public static InputAction parse(String raw) {
        if (raw == null) {
            return new InputAction.Nothing();
        }
        String line = raw.strip();
        if (line.isEmpty()) {
            return new InputAction.Nothing();
        }
        if (line.charAt(0) != PREFIX) {
            return new InputAction.SendMessage(line);
        }

        String body = line.substring(1).strip();
        if (body.isEmpty()) {
            return new InputAction.UnknownCommand(line);
        }

        int split = indexOfFirstSpace(body);
        String token = split < 0 ? body : body.substring(0, split);
        String argument = split < 0 ? "" : body.substring(split + 1).strip();

        return SlashCommand.find(token)
                .<InputAction>map(command -> new InputAction.RunCommand(command, argument))
                .orElseGet(() -> new InputAction.UnknownCommand(line));
    }

    private static int indexOfFirstSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
