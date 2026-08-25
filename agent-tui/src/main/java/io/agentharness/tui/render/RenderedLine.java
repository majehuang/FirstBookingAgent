package io.agentharness.tui.render;

import java.util.Objects;

/** 渲染结果的最小单位：一行文本 + 它的语义类别。 */
public record RenderedLine(LineKind kind, String text) {

    public RenderedLine {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }

    public static RenderedLine of(LineKind kind, String text) {
        return new RenderedLine(kind, text);
    }

    public static RenderedLine assistant(String text) {
        return new RenderedLine(LineKind.ASSISTANT, text);
    }

    public static RenderedLine hint(String text) {
        return new RenderedLine(LineKind.HINT, text);
    }
}
