package io.agentharness.tui.loopback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptedReplyTest {

    @Test
    @DisplayName("顺序是 推荐理由 → 卡片 → 追问，不是卡片在前")
    void 酒店剧本的步骤顺序() {
        List<ReplyStep> steps = ScriptedReply.forPrompt("帮我订一间明天的酒店");

        assertThat(steps).extracting(step -> step.getClass().getSimpleName())
                .containsExactly("Text", "ToolCall", "ToolResult", "Text", "Card", "Text");

        // 卡片之前那段是推荐理由，之后那段是追问
        assertThat(((ReplyStep.Text) steps.get(3)).content()).contains("最合适");
        assertThat(((ReplyStep.Text) steps.get(5)).content()).contains("？");
    }

    @Test
    void 卡片带数据时间_且条目内容在这一刻冻结() {
        ReplyStep.Card card = (ReplyStep.Card) ScriptedReply.forPrompt("酒店").get(4);

        assertThat(card.dataAsOf()).isNotBlank();
        assertThat(card.items()).hasSize(3);
        assertThat(card.items().get(0)).containsEntry("name", "北京国贸大酒店");
    }

    @Test
    void 报错剧本走失败分支() {
        List<ReplyStep> steps = ScriptedReply.forPrompt("故意报错试试");

        assertThat(steps).last().isInstanceOf(ReplyStep.Failure.class);
    }

    @Test
    void 其余输入走多行文本_用来验证行缓冲() {
        List<ReplyStep> steps = ScriptedReply.forPrompt("你好");

        assertThat(steps).hasSize(1);
        assertThat(((ReplyStep.Text) steps.get(0)).content().lines().count()).isEqualTo(3);
    }

    @Test
    void 同一输入永远产出同一串步骤() {
        assertThat(ScriptedReply.forPrompt("酒店")).isEqualTo(ScriptedReply.forPrompt("酒店"));
    }

    @Test
    void 空输入与null不抛异常() {
        assertThat(ScriptedReply.forPrompt(null)).isNotEmpty();
        assertThat(ScriptedReply.forPrompt("")).isNotEmpty();
    }
}
