package io.agentharness.tools.hotel;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表达型工具：把一组酒店发成一条卡片消息。
 *
 * <p><b>富消息的来源是工具入参，不是模型自由文本。</b>
 * 函数调用是模型受训行为、schema 即契约；让模型直接吐消息 JSON 会丧失流式体验、
 * 拉低文本质量，还要处理转义地狱，改协议还得改 prompt。
 *
 * <p>返回值带 {@code shown} 摘要供模型续接 —— 模型需要知道"我刚给用户看了什么"
 * 才能接着说"这三家里我更推荐…"。{@code card} 则是冻结后落库的消息内容。
 *
 * <p>{@code resolved} 与 {@code dataAsOf} 由 {@link HotelEnrichmentMiddleware} 在
 * {@code onActing} 阶段填入。模型即使胡乱填了也会被覆盖 —— 这正是补全放在 middleware 的收益：
 * 卡片里的价格永远来自业务系统，不会是模型编的。
 */
public final class HotelCardTool {

    /** 一次最多展示几张卡片。超出的截断 —— 十几张卡片刷屏对用户没有价值。 */
    static final int MAX_CARDS = 5;

    private final HotelSource source;

    public HotelCardTool(HotelSource source) {
        this.source = source;
    }

    @Tool(name = "send_hotel_cards",
            description = "把一组酒店以卡片形式展示给用户。用它来「给用户看」酒店，"
                    + "不要在正文里罗列酒店明细。调用后继续说明推荐理由即可。")
    public Map<String, Object> sendHotelCards(
            @ToolParam(name = "title", description = "卡片组标题，例如「为你找到 3 家酒店」")
            String title,
            @ToolParam(name = "hotelIds", description = "要展示的酒店 id，来自 search_hotels 的结果")
            List<String> hotelIds,
            @ToolParam(name = "resolved", required = false,
                    description = "服务端填充，模型不要提供")
            List<Map<String, Object>> resolved,
            @ToolParam(name = "dataAsOf", required = false,
                    description = "服务端填充，模型不要提供")
            String dataAsOf) {

        // middleware 没装上时自己兜底查一次。工具内部的阻塞调用不需要手动 offload ——
        // ToolExecutor 已经默认跑在 boundedElastic 上（开发规划 F 节的注）
        List<Map<String, Object>> items = resolved != null && !resolved.isEmpty()
                ? capped(resolved)
                : capped(lookupItems(hotelIds));
        String asOf = dataAsOf != null && !dataAsOf.isBlank() ? dataAsOf : source.dataAsOf();

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title == null || title.isBlank() ? "为你找到 " + items.size() + " 家酒店" : title);
        card.put("items", items);
        card.put("dataAsOf", asOf);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shown", summarize(items));
        result.put("card", card);
        return result;
    }

    private List<Map<String, Object>> lookupItems(List<String> hotelIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Hotel hotel : source.lookup(hotelIds)) {
            items.add(hotel.toCardItem());
        }
        return items;
    }

    private static List<Map<String, Object>> capped(List<Map<String, Object>> items) {
        return items.size() <= MAX_CARDS ? List.copyOf(items) : List.copyOf(items.subList(0, MAX_CARDS));
    }

    /** 给模型看的一句话。带上名字，模型才能在后续正文里准确引用。 */
    static String summarize(List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            return "没有可展示的酒店，请先用 search_hotels 查询";
        }
        StringBuilder names = new StringBuilder();
        for (Map<String, Object> item : items) {
            if (!names.isEmpty()) {
                names.append("、");
            }
            names.append(item.get("name"));
        }
        return "已向用户展示 " + items.size() + " 张酒店卡片：" + names;
    }
}
