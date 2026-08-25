package io.agentharness.tui.loopback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptedReplyTest {

    @Test
    void 酒店剧本覆盖了文本_工具_卡片的交错() {
        List<ReplyStep> steps = ScriptedReply.forPrompt("帮我订一间明天的酒店");

        assertThat(steps).extracting(step -> step.getClass().getSimpleName())
                .containsExactly("Text", "ToolCall", "ToolResult", "Card", "Text");
    }

    @Test
    void 卡片带数据时间_且条目内容在这一刻冻结() {
        ReplyStep.Card card = (ReplyStep.Card) ScriptedReply.forPrompt("酒店").get(3);

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
