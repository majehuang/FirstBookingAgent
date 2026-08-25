package io.agentharness.engine;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * 按配置构造 {@link Model}。
 *
 * <p>{@link EngineConfig.Provider#AUTO} 走 {@link ModelRegistry} 的 SPI 解析 ——
 * classpath 上有哪个提供者就用哪个，模型名决定匹配结果。
 * 显式指定提供者则直接构造，好处是出错时报的是"缺 API Key"而不是
 * "没有提供者能解析这个模型名"这种绕圈子的信息。
 */
public final class ModelFactory {

    private ModelFactory() {
    }

    public static Model create(EngineConfig config) {
        EngineConfig resolved = config.resolveCredentials();

        return switch (resolved.provider()) {
            case DASHSCOPE -> dashScope(resolved);
            case OPENAI -> openAi(resolved);
            case AUTO -> auto(resolved);
        };
    }

    private static Model dashScope(EngineConfig config) {
        requireApiKey(config, "DASHSCOPE_API_KEY");
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .stream(true);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    private static Model openAi(EngineConfig config) {
        requireApiKey(config, "OPENAI_API_KEY");
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .stream(true);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    private static Model auto(EngineConfig config) {
        if (!ModelRegistry.canResolve(config.modelName())) {
            throw new IllegalStateException(
                    "没有提供者能解析模型 " + config.modelName()
                            + "。用 --provider openai|dashscope 显式指定，或检查 classpath 上的模型扩展");
        }
        return ModelRegistry.resolve(config.modelName());
    }

    private static void requireApiKey(EngineConfig config, String envName) {
        if (!config.hasApiKey()) {
            throw new IllegalStateException(
                    "缺少 API Key。设置环境变量 AGENT_API_KEY 或 " + envName);
        }
    }
}
