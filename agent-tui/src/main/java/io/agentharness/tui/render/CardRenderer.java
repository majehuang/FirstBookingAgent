package io.agentharness.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 富消息卡片的终端渲染。
 *
 * <p>卡片内容在生成时就已冻结落库（见 开发规划.md D3），所以这里渲染的永远是当时的数据，
 * 不是实时数据。{@code dataAsOf} 因此必须显式展示 —— 否则用户会把三天前的房价当成今天的。
 *
 * <p>payload 结构不认识时降级为 fallbackText，绝不抛异常：
 * 一条渲染不了的卡片不应该让整个会话中断。
 */
public final class CardRenderer {

    private static final String TOP = "┌ ";
    private static final String MID = "│ ";
    private static final String BOTTOM = "└ ";

    private CardRenderer() {
    }

    public static List<RenderedLine> render(String fallbackText, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of(RenderedLine.of(LineKind.CARD, TOP + fallbackText));
        }

        List<RenderedLine> lines = new ArrayList<>();
        String title = asText(payload.get("title"), fallbackText);
        lines.add(RenderedLine.of(LineKind.CARD, TOP + title));

        Object items = payload.get("items");
        if (items instanceof List<?> list) {
            int index = 1;
            for (Object item : list) {
                lines.add(RenderedLine.of(LineKind.CARD, MID + index + ". " + describeItem(item)));
                index++;
            }
        }

        String dataAsOf = asText(payload.get("dataAsOf"), "");
        lines.add(RenderedLine.of(LineKind.CARD,
                dataAsOf.isEmpty() ? BOTTOM + "—" : BOTTOM + "数据截至 " + dataAsOf));
        return List.copyOf(lines);
    }

    private static String describeItem(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return String.valueOf(item);
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, map.get("name"), "");
        appendIfPresent(sb, map.get("price"), "  ");
        appendIfPresent(sb, map.get("rating"), "  ");
        appendIfPresent(sb, map.get("note"), "  ");
        return sb.isEmpty() ? map.toString() : sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, Object value, String separator) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(separator);
        }
        sb.append(text);
    }

    private static String asText(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
