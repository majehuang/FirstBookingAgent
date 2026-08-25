package io.agentharness.tui.input;

/** 一行输入被解析成的动作。密封类型，主循环的 switch 必须穷尽。 */
public sealed interface InputAction {

    /** 普通文本，投递为一条 MESSAGE 指令。 */
    record SendMessage(String text) implements InputAction {
    }

    /** 已识别的斜杠命令。{@code argument} 可能为空串。 */
    record RunCommand(SlashCommand command, String argument) implements InputAction {
    }

    /** 以斜杠开头但不认识的命令。提示用户而不是当成正文发出去。 */
    record UnknownCommand(String raw) implements InputAction {
    }

    /** 空行，忽略。 */
    record Nothing() implements InputAction {
    }
}
