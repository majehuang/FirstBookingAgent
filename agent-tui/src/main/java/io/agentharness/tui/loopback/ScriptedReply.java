package io.agentharness.tui.loopback;

import java.util.List;
import java.util.Map;

/**
 * 本地假引擎的回复脚本。纯函数：给定输入永远产出同一串步骤。
 *
 * <p>它存在的目的不是"像个模型"，而是**把 TUI 需要正确处理的形态一次凑齐**：
 * 多行文本、工具调用、卡片与文本的交错、错误分支。
 * 等 AgentScope Java v2 的 HarnessAgent 接进来，这个类整体删除，
 * {@link LoopbackBackend} 换成真实事件流的适配器。
 */
public final class ScriptedReply {

    private static final String DATA_AS_OF = "2026-08-23 10:00";

    private ScriptedReply() {
    }

    public static List<ReplyStep> forPrompt(String prompt) {
        String text = prompt == null ? "" : prompt.strip();

        if (text.contains("报错") || text.toLowerCase().contains("error")) {
            return List.of(
                    new ReplyStep.Text("我试着查了一下，不过后端返回了异常。\n"),
                    new ReplyStep.Failure("下游服务 hotel-search 超时（模拟）"));
        }

        if (text.contains("酒店") || text.toLowerCase().contains("hotel")) {
            return hotelScript();
        }

        return List.of(new ReplyStep.Text(
                "收到。当前连的是本地假引擎，它只认两种剧本：\n"
                        + "带「酒店」的问题会走一遍工具调用加卡片，带「报错」的会走失败分支。\n"
                        + "其余输入都会得到这段话，用来验证流式文本与换行处理。\n"));
    }

    private static List<ReplyStep> hotelScript() {
        return List.of(
                new ReplyStep.Text("好的，我先查一下明天北京可订的房源。\n"),
                new ReplyStep.ToolCall("search_hotels", Map.of("city", "北京", "date", "2026-08-24")),
                new ReplyStep.ToolResult("找到 3 家符合条件的酒店"),
                new ReplyStep.Card("为你找到 3 家酒店", List.of(
                        Map.of("name", "北京国贸大酒店", "price", "¥1,280", "rating", "4.8★", "note", "含双早"),
                        Map.of("name", "王府井希尔顿", "price", "¥1,050", "rating", "4.7★", "note", "步行 12 分钟"),
                        Map.of("name", "东直门亚朵", "price", "¥680", "rating", "4.6★", "note", "性价比高")),
                        DATA_AS_OF),
                new ReplyStep.Text("这三家里我更推荐国贸大酒店：离你下午的会场步行 8 分钟，\n"
                        + "而且是唯一一家含双早的。要我直接锁一间大床房吗？\n"));
    }
}
