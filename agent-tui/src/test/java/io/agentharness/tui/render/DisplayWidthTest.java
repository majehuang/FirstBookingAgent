package io.agentharness.tui.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayWidthTest {

    @Test
    @DisplayName("中文一个字两列 —— 这就是不能用 String.length() 的原因")
    void 中文按两列计() {
        assertThat("生成中".length()).isEqualTo(3);
        assertThat(DisplayWidth.of("生成中")).isEqualTo(6);
    }

    @Test
    void ascii一个字符一列() {
        assertThat(DisplayWidth.of("seq 42")).isEqualTo(6);
    }

    @Test
    void 中英混排逐段相加() {
        assertThat(DisplayWidth.of("✎ 生成中 1.4s")).isEqualTo(DisplayWidth.of("✎")
                + DisplayWidth.of(" ") + DisplayWidth.of("生成中") + DisplayWidth.of(" 1.4s"));
    }

    @Test
    void 空串与null都是零() {
        assertThat(DisplayWidth.of("")).isZero();
        assertThat(DisplayWidth.of(null)).isZero();
    }

    @Test
    void 截断后不超过给定列数() {
        String truncated = DisplayWidth.truncate("为你找到 3 家酒店", 8);

        assertThat(DisplayWidth.of(truncated)).isLessThanOrEqualTo(8);
        assertThat(truncated).endsWith("…");
    }

    @Test
    @DisplayName("宽字符不会被切成半个 —— 否则终端上是个乱码方块")
    void 不切开宽字符() {
        // 预算 5 列，"为你" 占 4 列，第三个字放不下，只能停在这里
        assertThat(DisplayWidth.truncate("为你找到", 5)).isEqualTo("为你…");
    }

    @Test
    void 未超限原样返回() {
        assertThat(DisplayWidth.truncate("seq 42", 20)).isEqualTo("seq 42");
    }

    @Test
    void 零列或负数返回空串() {
        assertThat(DisplayWidth.truncate("生成中", 0)).isEmpty();
        assertThat(DisplayWidth.truncate("生成中", -3)).isEmpty();
    }

    @Test
    @DisplayName("补充平面字符按码点推进，不会切出半个代理对")
    void 代理对不被切开() {
        String emoji = "🏨🏨🏨";

        String truncated = DisplayWidth.truncate(emoji, 5);

        assertThat(DisplayWidth.of(truncated)).isLessThanOrEqualTo(5);
        assertThat(truncated.codePoints()).allMatch(Character::isDefined);
    }
}
