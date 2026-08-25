package io.agentharness.engine.rich;

import io.agentharness.engine.MessageDraft;

import java.util.Map;

/**
 * 表达型工具的渲染器：把工具返回值变成一条富消息。
 *
 * <p><b>必须是纯函数。</b>渲染在生成时只跑一次，结果冻结落库，重开会话直接读库 ——
 * 所以这里不能查库、不能调外部接口，否则"重开会话所见即当时所见"就不成立了
 * （补全属于 middleware 的职责，见 {@code onActing}）。
 *
 * <p>一种富消息 = 一个工具 + 一个 middleware + 一个渲染器。三者同名同进退。
 */
public interface RichMessageRenderer {

    /** 对应的工具名，与 {@code @Tool(name=...)} 一致。 */
    String toolName();

    /**
     * @param blockKey    这条消息的块标识
     * @param toolResult  工具返回值反序列化后的 Map
     * @return 一条富消息草稿；无法渲染时返回 null，调用方会退回普通工具结果展示
     */
    MessageDraft render(String blockKey, Map<String, Object> toolResult);
}
