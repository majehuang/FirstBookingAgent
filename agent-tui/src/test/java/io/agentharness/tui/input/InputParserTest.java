package io.agentharness.tui.input;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputParserTest {

    @Test
    void 普通文本走消息投递_首尾空白被裁掉() {
        assertThat(InputParser.parse("  订一间酒店  "))
                .isEqualTo(new InputAction.SendMessage("订一间酒店"));
    }

    @Test
    void 空行与null都不产生动作() {
        assertThat(InputParser.parse("")).isInstanceOf(InputAction.Nothing.class);
        assertThat(InputParser.parse("   ")).isInstanceOf(InputAction.Nothing.class);
        assertThat(InputParser.parse(null)).isInstanceOf(InputAction.Nothing.class);
    }

    @Test
    void 斜杠命令带参数() {
        assertThat(InputParser.parse("/session s-42"))
                .isEqualTo(new InputAction.RunCommand(SlashCommand.SESSION, "s-42"));
    }

    @Test
    void 斜杠命令不带参数时参数为空串() {
        assertThat(InputParser.parse("/help"))
                .isEqualTo(new InputAction.RunCommand(SlashCommand.HELP, ""));
    }

    @Test
    void 别名与大小写都能识别() {
        assertThat(InputParser.parse("/q")).isEqualTo(new InputAction.RunCommand(SlashCommand.QUIT, ""));
        assertThat(InputParser.parse("/EXIT")).isEqualTo(new InputAction.RunCommand(SlashCommand.QUIT, ""));
        assertThat(InputParser.parse("/?")).isEqualTo(new InputAction.RunCommand(SlashCommand.HELP, ""));
    }

    @Test
    void 打错的命令提示而不是当成正文发出去() {
        // 把 /statsu 当成问题发给模型是最糟的结果
        assertThat(InputParser.parse("/statsu"))
                .isEqualTo(new InputAction.UnknownCommand("/statsu"));
        assertThat(InputParser.parse("/")).isInstanceOf(InputAction.UnknownCommand.class);
    }

    @Test
    void 正文中间的斜杠不受影响() {
        assertThat(InputParser.parse("北京/上海 哪个便宜"))
                .isEqualTo(new InputAction.SendMessage("北京/上海 哪个便宜"));
    }
}
