package io.agentharness.tui.render;

/**
 * 一行输出的语义类别。渲染层只产出「类别 + 文本」，
 * 具体颜色由终端适配层的 Theme 决定 —— 这样纯渲染逻辑不依赖 JLine，可以直接断言。
 */
public enum LineKind {

    USER,
    ASSISTANT,
    TOOL,
    CARD,
    SYSTEM,
    ERROR,
    HINT
}
