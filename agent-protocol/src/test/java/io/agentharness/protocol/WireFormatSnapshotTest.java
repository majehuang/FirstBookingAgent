package io.agentharness.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线格式快照。
 *
 * <p>这些字面量锁住的是<b>已经发给客户端的 JSON</b>。改字段名、改枚举拼写、
 * 改时间格式都会让这里红 —— 红了不要改测试，要意识到你正在做协议破坏性变更，
 * 该走 开发计划.md §9 的变更流程。
 */
@DisplayName("线格式快照")
class WireFormatSnapshotTest {

    private static final Instant AT = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    void 用户消息() {
        String json = Json.write(ClientMessage.userText(1, "r-1", "u-i1", "帮我订酒店", AT));

        assertThat(json).isEqualTo("{\"msgSeq\":1,\"replyId\":\"r-1\",\"blockId\":\"u-i1\","
                + "\"role\":\"USER\",\"type\":\"TEXT\",\"fallbackText\":\"帮我订酒店\","
                + "\"payload\":{},\"createdAt\":\"2026-08-24T10:00:00Z\"}");
    }

    @Test
    void 文本增量() {
        String json = Json.write(ClientMessage.textDelta(2, "r-1", "b-1", "好的", AT));

        assertThat(json).isEqualTo("{\"msgSeq\":2,\"replyId\":\"r-1\",\"blockId\":\"b-1\","
                + "\"role\":\"ASSISTANT\",\"type\":\"TEXT_DELTA\",\"fallbackText\":\"好的\","
                + "\"payload\":{},\"createdAt\":\"2026-08-24T10:00:00Z\"}");
    }

    @Test
    void 卡片带冻结的载荷与数据时间() {
        ClientMessage card = new ClientMessage(3, "r-1", "b-2", MessageRole.ASSISTANT,
                MessageType.CARD, "为你找到 1 家酒店",
                Map.of("title", "1 家酒店", "dataAsOf", "2026-08-24 10:00"), AT);

        String json = Json.write(card);

        assertThat(json).contains("\"type\":\"CARD\"")
                .contains("\"dataAsOf\":\"2026-08-24 10:00\"")
                .contains("\"fallbackText\":\"为你找到 1 家酒店\"");
        assertThat(Json.read(json, ClientMessage.class)).isEqualTo(card);
    }

    @Test
    void 投递回执() {
        String json = Json.write(new Ack("i-1", "r-1", 42));

        assertThat(json).isEqualTo(
                "{\"instructionId\":\"i-1\",\"replyId\":\"r-1\",\"msgSeq\":42}");
    }

    @Test
    void 用户指令() {
        String json = Json.write(UserInstruction.message("i-1", "帮我订酒店", AT));

        assertThat(json).isEqualTo("{\"instructionId\":\"i-1\",\"kind\":\"MESSAGE\","
                + "\"priority\":\"QUEUED\",\"text\":\"帮我订酒店\","
                + "\"createdAt\":\"2026-08-24T10:00:00Z\"}");
    }

    @Test
    void 停止指令() {
        String json = Json.write(UserInstruction.cancel("i-2", "r-1", AT));

        assertThat(json).isEqualTo("{\"instructionId\":\"i-2\",\"kind\":\"CONTROL\","
                + "\"priority\":\"IMMEDIATE\",\"targetReplyId\":\"r-1\","
                + "\"createdAt\":\"2026-08-24T10:00:00Z\"}");
    }

    @Test
    void 控制状态帧() {
        ControlFrame frame = ControlFrame.idle().withTurnStarted("r-1").withCtrlId("1724-3");

        String json = Json.write(frame);

        assertThat(json).isEqualTo("{\"turnActive\":true,\"inputAllowed\":false,"
                + "\"activeReplyId\":\"r-1\",\"phase\":\"QUEUED\",\"stopping\":false,"
                + "\"ctrlId\":\"1724-3\"}");
        assertThat(Json.read(json, ControlFrame.class)).isEqualTo(frame);
    }

    @Test
    @DisplayName("null 字段不出现在线上 —— 省带宽，也让快照更稳定")
    void 空字段被省略() {
        String json = Json.write(ControlFrame.idle());

        assertThat(json).doesNotContain("activeReplyId").doesNotContain("supersededReplyId");
    }

    @Test
    void 时间戳统一为ISO_8601_UTC() {
        // 用时间戳数字的话，客户端跨时区解析会各显神通
        assertThat(Json.write(ClientMessage.textEnd(1, "r-1", "b-1", AT)))
                .contains("\"createdAt\":\"2026-08-24T10:00:00Z\"");
    }

    @Test
    void 全部消息类型的枚举拼写被锁定() {
        // 枚举名就是线格式的一部分，改名等于破坏协议
        assertThat(List.of(MessageType.values()).stream().map(Enum::name).toList())
                .containsExactly("TEXT", "TEXT_DELTA", "TEXT_END", "TOOL_CALL", "TOOL_RESULT",
                        "CARD", "IMAGE", "AUDIO", "ERROR", "SYSTEM", "UNKNOWN");
        assertThat(List.of(MessageRole.values()).stream().map(Enum::name).toList())
                .containsExactly("USER", "ASSISTANT", "SYSTEM", "UNKNOWN");
    }
}
