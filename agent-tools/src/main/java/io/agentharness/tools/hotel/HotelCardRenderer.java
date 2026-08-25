package io.agentharness.tools.hotel;

import io.agentharness.engine.MessageDraft;
import io.agentharness.engine.rich.RichMessageRenderer;
import io.agentharness.protocol.MessageType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code send_hotel_cards} 的返回值渲染成一条卡片消息。**纯函数。**
 *
 * <p>渲染只在生成时跑一次，结果冻结落库 —— 所以这里绝不能查库或调接口，
 * 否则"重开会话所见即当时所见"就不成立了。
 */
public final class HotelCardRenderer implements RichMessageRenderer {

    private static final int FALLBACK_ITEM_LIMIT = 3;

    @Override
    public String toolName() {
        return HotelEnrichmentMiddleware.TOOL_NAME;
    }

    @Override
    @SuppressWarnings("unchecked")
    public MessageDraft render(String blockKey, Map<String, Object> toolResult) {
        Object rawCard = toolResult == null ? null : toolResult.get("card");
        if (!(rawCard instanceof Map<?, ?> card)) {
            return null;
        }

        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) card);
        List<Map<String, Object>> items = itemsOf(payload);
        String title = String.valueOf(payload.getOrDefault("title", "酒店推荐"));

        return new MessageDraft(blockKey, MessageType.CARD,
                fallbackText(title, items, String.valueOf(payload.get("dataAsOf"))), payload);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> card) {
        Object raw = card.get("items");
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * 降级文本。
     *
     * <p>客户端不支持卡片时，这段文字是它唯一还能渲染的东西 ——
     * 所以它必须自己就说得清楚，不能是「[卡片]」这种占位符。
     * 富消息的 fallbackText 为空会被协议层直接拒绝。
     */
    static String fallbackText(String title, List<Map<String, Object>> items, String dataAsOf) {
        if (items.isEmpty()) {
            return title;
        }
        StringBuilder text = new StringBuilder(title).append("：");
        int shown = Math.min(items.size(), FALLBACK_ITEM_LIMIT);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append("；");
            }
            Map<String, Object> item = items.get(i);
            text.append(item.get("name"));
            appendIfPresent(text, item.get("price"));
            appendIfPresent(text, item.get("rating"));
        }
        if (items.size() > shown) {
            text.append("；等共 ").append(items.size()).append(" 家");
        }
        if (dataAsOf != null && !dataAsOf.isBlank() && !"null".equals(dataAsOf)) {
            text.append("（数据截至 ").append(dataAsOf).append('）');
        }
        return text.toString();
    }

    private static void appendIfPresent(StringBuilder text, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            text.append(' ').append(value);
        }
    }
}
