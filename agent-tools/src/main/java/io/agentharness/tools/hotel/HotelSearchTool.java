package io.agentharness.tools.hotel;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务查询工具。
 *
 * <p>与表达型工具的分工：查询工具只把数据交给<b>模型</b>，由模型决定展示什么；
 * 表达型工具才把内容交给<b>用户</b>。两者在界面上的呈现也不同 ——
 * 查询是一行「⚒ 正在查询…」的状态，表达是一条卡片消息。
 */
public final class HotelSearchTool {

    private final HotelSource source;
    private final List<String> allIds;

    public HotelSearchTool(HotelSource source, List<String> allIds) {
        this.source = source;
        this.allIds = List.copyOf(allIds);
    }

    @Tool(name = "search_hotels",
            description = "查询可订酒店。返回结果里的 id 可以交给 send_hotel_cards 展示给用户。")
    public Map<String, Object> searchHotels(
            @ToolParam(name = "city", description = "城市，例如「北京」")
            String city,
            @ToolParam(name = "maxPrice", required = false, description = "价格上限，单位元")
            Integer maxPrice) {

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Hotel hotel : source.lookup(allIds)) {
            if (withinBudget(hotel, maxPrice)) {
                hits.add(hotel.toCardItem());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("count", hits.size());
        result.put("hotels", hits);
        result.put("dataAsOf", source.dataAsOf());
        return result;
    }

    static boolean withinBudget(Hotel hotel, Integer maxPrice) {
        if (maxPrice == null) {
            return true;
        }
        Integer price = parsePrice(hotel.price());
        return price == null || price <= maxPrice;
    }

    /** 价格是「¥1,280」这种展示格式，比价前要还原成数字。解析不了就不过滤，不要静默丢数据。 */
    static Integer parsePrice(String display) {
        if (display == null) {
            return null;
        }
        String digits = display.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
