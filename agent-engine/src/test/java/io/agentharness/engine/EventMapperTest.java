package io.agentharness.engine;

import io.agentharness.protocol.MessageType;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventMapperTest {

    @Test
    void 文本块映射为增量_同一条消息共享一个block() {
        Event first = reasoning("m-1", TextBlock.builder().text("你好").build());
        Event second = reasoning("m-1", TextBlock.builder().text("，世界").build());

        List<MessageDraft> a = EventMapper.map(first);
        List<MessageDraft> b = EventMapper.map(second);

        assertThat(a).singleElement()
                .satisfies(d -> assertThat(d.type()).isEqualTo(MessageType.TEXT_DELTA))
                .satisfies(d -> assertThat(d.text()).isEqualTo("你好"));
        assertThat(b.get(0).blockKey()).isEqualTo(a.get(0).blockKey());
    }

    @Test
    void 空文本不产生消息_避免打出一串空白() {
        assertThat(EventMapper.map(reasoning("m-1", TextBlock.builder().text("").build()))).isEmpty();
    }

    @Test
    void 工具调用带上工具名与入参() {
        Event event = reasoning("m-2",
                new ToolUseBlock("call-1", "search_hotels", Map.of("city", "北京")));

        MessageDraft draft = EventMapper.map(event).get(0);

        assertThat(draft.type()).isEqualTo(MessageType.TOOL_CALL);
        assertThat(draft.text()).isEqualTo("search_hotels");
        assertThat(draft.payload()).containsEntry("tool", "search_hotels");
        assertThat(draft.payload().get("args")).isEqualTo(Map.of("city", "北京"));
    }

    @Test
    void 工具调用与文本在同一事件里时保持先后顺序() {
        Event event = reasoning("m-3",
                TextBlock.builder().text("我查一下").build(),
                new ToolUseBlock("call-2", "lookup", Map.of()));

        List<MessageDraft> drafts = EventMapper.map(event);

        assertThat(drafts).extracting(MessageDraft::type)
                .containsExactly(MessageType.TEXT_DELTA, MessageType.TOOL_CALL);
    }

    @Test
    @DisplayName("思维链不进消息表 —— 推理事件按 D2 只做日志")
    void 思考块被丢弃() {
        Event event = reasoning("m-4", ThinkingBlock.builder().thinking("我应该先查一下价格").build());

        assertThat(EventMapper.map(event)).isEmpty();
    }

    @Test
    void 工具结果取输出里的文本作为摘要() {
        ToolResultBlock result = new ToolResultBlock("call-1", "search_hotels",
                List.of(TextBlock.builder().text("找到 3 家").build()));
        Event event = new Event(EventType.TOOL_RESULT, msg(MsgRole.TOOL, result), false);

        MessageDraft draft = EventMapper.map(event).get(0);

        assertThat(draft.type()).isEqualTo(MessageType.TOOL_RESULT);
        assertThat(draft.text()).isEqualTo("找到 3 家");
    }

    @Test
    void 工具结果没有文本输出时退回工具名_不产生空消息() {
        ToolResultBlock result = new ToolResultBlock("call-1", "write_file", List.of());
        Event event = new Event(EventType.TOOL_RESULT, msg(MsgRole.TOOL, result), false);

        assertThat(EventMapper.map(event).get(0).text()).isEqualTo("write_file 完成");
    }

    @Test
    @DisplayName("AGENT_RESULT 只收尾，不重复输出正文")
    void 最终结果只产生结束标记() {
        Event event = new Event(EventType.AGENT_RESULT,
                msg(MsgRole.ASSISTANT, TextBlock.builder().text("完整的最终回答").build()), true);

        List<MessageDraft> drafts = EventMapper.map(event);

        assertThat(drafts).singleElement()
                .satisfies(d -> assertThat(d.type()).isEqualTo(MessageType.TEXT_END))
                .satisfies(d -> assertThat(d.text()).isEmpty());
    }

    @Test
    void 提示块映射为系统消息() {
        Event event = new Event(EventType.HINT,
                msg(MsgRole.ASSISTANT, new HintBlock("h-1", "上下文即将超窗")), false);

        MessageDraft draft = EventMapper.map(event).get(0);

        assertThat(draft.type()).isEqualTo(MessageType.SYSTEM);
        assertThat(draft.text()).isEqualTo("上下文即将超窗");
    }

    @Test
    void null事件与空消息不抛异常() {
        assertThat(EventMapper.map(null)).isEmpty();
        assertThat(EventMapper.map(new Event(EventType.REASONING, null, false))).isEmpty();
    }

    @Test
    void 草稿的payload不可变() {
        MessageDraft draft = MessageDraft.text("b1", "hi");
        assertThat(draft.payload()).isEmpty();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> draft.payload().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("未拼装完成的工具调用不下发 —— 否则同一个工具会被渲染十几次")
    void 分片工具调用被丢弃() {
        Event fragment = reasoning("m-5", new ToolUseBlock("call-9", "__fragment__", Map.of()));
        Event blankName = reasoning("m-5", new ToolUseBlock("call-9", "", Map.of()));

        assertThat(EventMapper.map(fragment)).isEmpty();
        assertThat(EventMapper.map(blankName)).isEmpty();
    }

    @Test
    @DisplayName("block key 必须有界 —— 上游 id 长度不由我们控制，超了会撞上列宽")
    void 超长blockKey被压到列宽以内() {
        String longId = "chatcmpl-" + "x".repeat(300);

        String bounded = EventMapper.bounded(longId);

        assertThat(bounded).hasSizeLessThanOrEqualTo(128);
        assertThat(bounded).startsWith("chatcmpl-xxx");
        assertThat(bounded).contains("~");
    }

    @Test
    void 不同的超长id不会压成同一个key_否则两段无关文本会被合并() {
        String a = EventMapper.bounded("prefix-" + "a".repeat(200) + "-one");
        String b = EventMapper.bounded("prefix-" + "a".repeat(200) + "-two");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 未超长的key原样保留() {
        assertThat(EventMapper.bounded("tool:call-1")).isEqualTo("tool:call-1");
    }

    @Test
    void 工具调用的blockKey在列宽以内() {
        Event event = reasoning("m-6",
                new ToolUseBlock("c".repeat(200), "search_hotels", Map.of()));

        assertThat(EventMapper.map(event)).singleElement()
                .satisfies(d -> assertThat(d.blockKey()).hasSizeLessThanOrEqualTo(128));
    }

    private static Event reasoning(String messageId, ContentBlock... blocks) {
        Msg message = Msg.builder()
                .id(messageId)
                .role(MsgRole.ASSISTANT)
                .content(List.of(blocks))
                .build();
        return new Event(EventType.REASONING, message, false);
    }

    private static Msg msg(MsgRole role, ContentBlock... blocks) {
        return Msg.builder().id("m-x").role(role).content(List.of(blocks)).build();
    }
}
