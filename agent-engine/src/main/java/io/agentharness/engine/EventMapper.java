package io.agentharness.engine;

import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentharness.engine.rich.RichMessageRenderer;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.Json;
import io.agentharness.protocol.MessageType;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 的 {@link Event} → 客户端消息草稿。**纯函数，无 IO。**
 *
 * <p>整个适配层里最容易出错的就是这一段，所以它被刻意做成没有任何依赖的静态方法：
 * 构造几个 Event 就能把全部分支断言一遍，不需要模型、不需要数据库。
 *
 * <p>两个映射上的取舍：
 * <ul>
 *   <li><b>{@code AGENT_RESULT} 只当作结束标记，不再输出正文。</b>
 *       流式开启时正文已经通过 REASONING 的增量块吐过一遍了，
 *       再把最终整条消息发一次，用户会看到内容重复一遍。</li>
 *   <li><b>ThinkingBlock 不下发。</b>思维链属于推理事件，按 D2 只进冷存储日志，
 *       不进用户可见的消息表。要展示的话应当新增一个消息类型，而不是混进正文。</li>
 * </ul>
 */
public final class EventMapper {

    private static final int DIGEST_HEX_LENGTH = 16;

    /** 工具结果在界面上的最大长度。超了截断 —— 几百字符的 JSON 会把对话内容挤没。 */
    private static final int MAX_RESULT_LENGTH = 80;

    /**
     * 上游用这个占位名表示"工具调用的入参还在流式拼装中"。
     *
     * <p>装配处已经关掉了入参分片流，这里再挡一道：分片块的入参是不完整的 JSON，
     * 一旦漏进消息表，用户会看到同一个工具被调了十几次。
     */
    private static final String FRAGMENT_TOOL_NAME = "__fragment__";

    private EventMapper() {
    }

    /** 不带表达型工具注册表的映射。测试与不需要富消息的场景用。 */
    public static List<MessageDraft> map(Event event) {
        return map(event, RichMessageRegistry.empty());
    }

    public static List<MessageDraft> map(Event event, RichMessageRegistry registry) {
        if (event == null) {
            return List.of();
        }
        EventType type = event.getType();
        Msg message = event.getMessage();

        if (type == EventType.AGENT_RESULT) {
            // 只收尾，不重复输出正文
            return List.of(MessageDraft.endOfBlock(blockKeyOf(event)));
        }
        if (message == null) {
            return List.of();
        }

        return switch (type) {
            case REASONING -> fromReasoning(event, message, registry);
            case TOOL_RESULT -> fromToolResult(message, registry);
            case HINT -> fromHint(message);
            case SUMMARY -> fromSummary(event, message);
            default -> List.of();
        };
    }

    private static List<MessageDraft> fromReasoning(Event event, Msg message,
                                                    RichMessageRegistry registry) {
        List<MessageDraft> drafts = new ArrayList<>();
        String blockKey = blockKeyOf(event);

        for (ContentBlock block : safeContent(message)) {
            if (block instanceof TextBlock text) {
                appendText(drafts, blockKey, text.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                if (isFragment(toolUse)) {
                    continue;
                }
                if (registry.isExpressive(toolUse.getName())) {
                    // 表达型工具的入参就是消息内容，富消息会在 TOOL_RESULT 时渲染出来。
                    // 这里再打一行"⚒ send_hotel_cards(...)"纯属噪音 ——
                    // 用户看到的应该是卡片，不是"正在调用发卡片的函数"
                    continue;
                }
                drafts.add(new MessageDraft(bounded("tool:" + toolUse.getId()),
                        MessageType.TOOL_CALL, toolUse.getName(),
                        Map.of("tool", toolUse.getName(),
                                "args", toolUse.getInput() == null ? Map.of() : toolUse.getInput())));
            } else if (block instanceof ThinkingBlock) {
                // 思维链只进冷存储，不进消息表
                continue;
            }
        }
        return List.copyOf(drafts);
    }

    private static List<MessageDraft> fromToolResult(Msg message, RichMessageRegistry registry) {
        List<MessageDraft> drafts = new ArrayList<>();
        for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
            String blockKey = bounded("tool-result:" + result.getId());
            MessageDraft rich = renderRich(registry, result, blockKey);
            drafts.add(rich != null ? rich : new MessageDraft(blockKey,
                    MessageType.TOOL_RESULT, summarize(result),
                    Map.of("tool", nullToEmpty(result.getName()))));
        }
        return List.copyOf(drafts);
    }

    /**
     * 表达型工具的返回值渲染成富消息。
     *
     * <p>任何一步失败都退回普通的工具结果展示，<b>绝不抛异常</b>：
     * 一条渲染不了的卡片不应该让整轮对话中断，用户至少还能看到工具执行完了。
     */
    @SuppressWarnings("unchecked")
    private static MessageDraft renderRich(RichMessageRegistry registry, ToolResultBlock result,
                                           String blockKey) {
        RichMessageRenderer renderer = registry.forTool(result.getName()).orElse(null);
        if (renderer == null) {
            return null;
        }
        try {
            String json = rawOutput(result);
            if (json.isBlank()) {
                return null;
            }
            return renderer.render(blockKey, Json.read(json, Map.class));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<MessageDraft> fromHint(Msg message) {
        List<MessageDraft> drafts = new ArrayList<>();
        for (HintBlock hint : message.getContentBlocks(HintBlock.class)) {
            drafts.add(new MessageDraft(bounded("hint:" + hint.getId()),
                    MessageType.SYSTEM, hint.getHint(), Map.of()));
        }
        return List.copyOf(drafts);
    }

    private static List<MessageDraft> fromSummary(Event event, Msg message) {
        String text = message.getTextContent();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(new MessageDraft(bounded("summary:" + blockKeyOf(event)),
                MessageType.SYSTEM, text, Map.of()));
    }

    /** 工具结果的原始文本。渲染富消息时要解析它，所以不能在这一步压缩。 */
    static String rawOutput(ToolResultBlock result) {
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 给用户看的一句话。
     *
     * <p>工具返回值经常是一大段 JSON —— 原样打在屏幕上是几百字符的噪音，
     * 会把真正的对话内容挤没。所以这里压成一行：
     * 认得出 {@code shown} / {@code count} 就用它们，否则截断。
     *
     * <p>压缩只影响<b>展示</b>。模型拿到的仍是完整返回值，
     * 富消息渲染读的也是 {@link #rawOutput}。
     */
    static String summarize(ToolResultBlock result) {
        String toolName = nullToEmpty(result.getName());
        String raw = rawOutput(result).strip();
        if (raw.isEmpty()) {
            return toolName + " 完成";
        }
        return compact(raw, toolName);
    }

    @SuppressWarnings("unchecked")
    static String compact(String raw, String toolName) {
        if (raw.startsWith("{")) {
            try {
                Map<String, Object> parsed = Json.read(raw, Map.class);
                Object shown = parsed.get("shown");
                if (shown != null) {
                    return String.valueOf(shown);
                }
                Object count = parsed.get("count");
                if (count != null) {
                    return toolName + " 返回 " + count + " 条";
                }
            } catch (RuntimeException ignored) {
                // 解不开就按纯文本截断，不要因为一个展示细节让映射失败
            }
        }
        String singleLine = raw.replaceAll("\\s+", " ").strip();
        return singleLine.length() <= MAX_RESULT_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_RESULT_LENGTH) + "…";
    }

    private static void appendText(List<MessageDraft> drafts, String blockKey, String text) {
        if (text != null && !text.isEmpty()) {
            drafts.add(MessageDraft.text(blockKey, text));
        }
    }

    private static List<ContentBlock> safeContent(Msg message) {
        List<ContentBlock> content = message.getContent();
        return content == null ? List.of() : content;
    }

    /**
     * 同一条模型消息的所有增量共享一个 block。
     *
     * <p>用 messageId 而不是自增计数：并发的子 agent 事件可能交错到达，
     * 计数器会把两条消息的增量并成一块。
     */
    static String blockKeyOf(Event event) {
        String messageId = event.getMessageId();
        return bounded(messageId == null || messageId.isBlank() ? "blk" : messageId);
    }

    /** 未拼装完成的工具调用不下发 —— 名字是占位符，入参是半截 JSON。 */
    static boolean isFragment(ToolUseBlock toolUse) {
        String name = toolUse.getName();
        return name == null || name.isBlank() || FRAGMENT_TOOL_NAME.equals(name);
    }

    /**
     * 把 block key 压到列宽以内。
     *
     * <p>上游的消息 id 与工具调用 id 长度不受我们控制，直接落库会撞上
     * {@code block_id} 的列宽 —— 而这个错误只在模型真的调工具时才出现，
     * 单测和不带工具的对话都盖不到。
     *
     * <p>超长时保留前缀再接一段 SHA-256 摘要：既能在日志里看出原始 id 的头部，
     * 又不会因为截断而让两个不同的 id 撞成同一个 block（那会让两段无关的文本被合并）。
     */
    static String bounded(String raw) {
        if (raw.length() <= ClientMessage.MAX_BLOCK_ID_LENGTH) {
            return raw;
        }
        int prefixLength = ClientMessage.MAX_BLOCK_ID_LENGTH - DIGEST_HEX_LENGTH - 1;
        return raw.substring(0, prefixLength) + "~" + digest(raw);
    }

    private static String digest(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行时缺少 SHA-256", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
