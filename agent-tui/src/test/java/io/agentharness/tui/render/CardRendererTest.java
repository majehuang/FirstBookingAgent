package io.agentharness.tui.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CardRendererTest {

    @Test
    void 完整卡片渲染出标题_条目与数据时间() {
        List<RenderedLine> lines = CardRenderer.render("3 家酒店", Map.of(
                "title", "为你找到 3 家酒店",
                "items", List.of(
                        Map.of("name", "国贸大酒店", "price", "¥1,280", "rating", "4.8★", "note", "含双早"),
                        Map.of("name", "王府井希尔顿", "price", "¥1,050")),
                "dataAsOf", "2026-08-23 10:00"));

        assertThat(lines).extracting(RenderedLine::text).containsExactly(
                "┌ 为你找到 3 家酒店",
                "│ 1. 国贸大酒店  ¥1,280  4.8★  含双早",
                "│ 2. 王府井希尔顿  ¥1,050",
                "└ 数据截至 2026-08-23 10:00");
    }

    @Test
    void 卡片内容已冻结_所以数据时间必须展示出来() {
        // 缺 dataAsOf 时用占位符而不是静默隐藏：宁可显示"—"也不能让用户以为是实时价
        List<RenderedLine> lines = CardRenderer.render("标题", Map.of("title", "标题"));

        assertThat(lines).extracting(RenderedLine::text).last().isEqualTo("└ —");
    }

    @Test
    void payload结构不认识时降级为fallbackText而不是抛异常() {
        List<RenderedLine> lines = CardRenderer.render("一条无法解析的卡片", Map.of());

        assertThat(lines).extracting(RenderedLine::text).containsExactly("┌ 一条无法解析的卡片");
    }

    @Test
    void payload为null时同样降级() {
        assertThat(CardRenderer.render("兜底文本", null))
                .extracting(RenderedLine::text).containsExactly("┌ 兜底文本");
    }
}
