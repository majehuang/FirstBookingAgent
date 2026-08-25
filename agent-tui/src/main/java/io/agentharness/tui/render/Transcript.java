package io.agentharness.tui.render;

import io.agentharness.protocol.ClientMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话文字稿的归约器。对应最终形态里的 {@code MessageAdapter}：per-reply、只累积文本、
 * 块结束即销毁缓冲。
 *
 * <p>核心约束是**顺序**：任何非文本消息（卡片、工具、错误）落地之前，
 * 必须先把当前未完成的文本尾巴打印出去。否则模型先说"我推荐这几家"、
 * 再吐出卡片时，卡片会插到那句话中间 —— 那正是最难查的一类错乱。
 *
 * <p>不可变：每次 accept 返回新的 Transcript 与本次要打印的行。
 */
public record Transcript(String activeBlockId, StreamBuffer buffer) {

    public Transcript {
        Objects.requireNonNull(buffer, "buffer");
    }

    /** 一次归约的产出：新状态 + 要落地的完整行 + 实时刷新的未完成尾巴。 */
    public record Emission(Transcript transcript, List<RenderedLine> lines, String liveTail) {

        public Emission {
            Objects.requireNonNull(transcript, "transcript");
            Objects.requireNonNull(liveTail, "liveTail");
            lines = List.copyOf(lines);
        }
    }

    public static Transcript empty() {
        return new Transcript(null, StreamBuffer.empty());
    }

    public Emission accept(ClientMessage message) {
        Objects.requireNonNull(message, "message");

        return switch (message.type()) {
            case TEXT -> withPendingFlushed(List.of(completeText(message)));
            case TEXT_DELTA -> onDelta(message);
            case TEXT_END -> flush();
            case CARD -> withPendingFlushed(CardRenderer.render(message.fallbackText(), message.payload()));
            case TOOL_CALL -> withPendingFlushed(List.of(
                    RenderedLine.of(LineKind.TOOL, "⚒ " + toolLabel(message))));
            case TOOL_RESULT -> withPendingFlushed(List.of(
                    RenderedLine.of(LineKind.TOOL, "✓ " + message.fallbackText())));
            case IMAGE -> withPendingFlushed(List.of(
                    RenderedLine.of(LineKind.CARD, "🖼 " + message.fallbackText())));
            case AUDIO -> withPendingFlushed(List.of(
                    RenderedLine.of(LineKind.CARD, "🔊 " + message.fallbackText())));
            case SYSTEM -> withPendingFlushed(List.of(
                    RenderedLine.of(LineKind.SYSTEM, "· " + message.fallbackText())));
            case ERROR -> withPendingFlushed(List.of(
                    RenderedLine.of(LineKind.ERROR, "✗ " + message.fallbackText())));
            // 服务端新增的、本客户端还不认识的类型。
            // 按纯文本渲染 fallbackText —— 这就是 fallbackText 必填的兑现点
            case UNKNOWN -> withPendingFlushed(List.of(
                    RenderedLine.assistant(message.fallbackText())));
        };
    }

    /** 把未完成的尾巴作为最后一行落地。turn 结束、被打断、退出前都要调。 */
    public Emission flush() {
        StreamBuffer.Append flushed = buffer.flush();
        return new Emission(empty(), toAssistantLines(flushed.completedLines()), "");
    }

    /** 空窗恢复时清空渲染缓冲。注意调用方紧接着必须去拉历史，只清空会留下空白。 */
    public Emission clear() {
        return new Emission(empty(), List.of(), "");
    }

    private Emission onDelta(ClientMessage message) {
        List<RenderedLine> lines = new ArrayList<>();
        StreamBuffer working = buffer;

        boolean blockChanged = activeBlockId != null && !activeBlockId.equals(message.blockId());
        if (blockChanged) {
            StreamBuffer.Append flushed = working.flush();
            lines.addAll(toAssistantLines(flushed.completedLines()));
            working = flushed.buffer();
        }

        StreamBuffer.Append appended = working.append(message.fallbackText());
        lines.addAll(toAssistantLines(appended.completedLines()));

        return new Emission(new Transcript(message.blockId(), appended.buffer()),
                lines, appended.buffer().tail());
    }

    private Emission withPendingFlushed(List<RenderedLine> extra) {
        StreamBuffer.Append flushed = buffer.flush();
        List<RenderedLine> lines = new ArrayList<>(toAssistantLines(flushed.completedLines()));
        lines.addAll(extra);
        return new Emission(empty(), lines, "");
    }

    /**
     * 一条完整文本。用户自己的消息走这条路 ——
     * 它不是本地回显，是服务端落库后从流里推回来的，所以顺序与助手内容在同一个 seq 空间里。
     */
    private static RenderedLine completeText(ClientMessage message) {
        return message.fromUser()
                ? RenderedLine.of(LineKind.USER, "› " + message.fallbackText())
                : RenderedLine.assistant(message.fallbackText());
    }

    private static List<RenderedLine> toAssistantLines(List<String> texts) {
        return texts.stream().map(RenderedLine::assistant).toList();
    }

    private static String toolLabel(ClientMessage message) {
        Object tool = message.payloadValue("tool");
        if (tool == null) {
            return message.fallbackText();
        }
        Object args = message.payloadValue("args");
        if (args instanceof Map<?, ?> map && !map.isEmpty()) {
            String rendered = map.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return tool + "(" + rendered + ")";
        }
        return tool + "()";
    }
}
