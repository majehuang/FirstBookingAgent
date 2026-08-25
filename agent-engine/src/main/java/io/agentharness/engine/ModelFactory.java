package io.agentharness.engine;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
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
        ModelCreationContext context = creationContext(config);
        if (!ModelRegistry.canResolve(config.modelName(), context)) {
            throw new IllegalStateException(
                    "没有提供者能解析模型 " + config.modelName()
                            + "。用 --provider openai|dashscope 显式指定，或检查 classpath 上的模型扩展");
        }
        return ModelRegistry.resolve(config.modelName(), context);
    }

    /**
     * 把已解析的凭据与地址装进 context 交给 provider。
     *
     * <p><b>必须走带 context 的重载。</b>单参的 {@code resolve(modelName)} 内部用的是
     * {@code ModelCreationContext.empty()}，apiKey 与 baseUrl 一个都到不了 provider，
     * 上游只能退回读它自己认的环境变量（{@code DASHSCOPE_API_KEY} / {@code OPENAI_API_KEY} /
     * {@code MOONSHOT_API_KEY} …）—— 而 {@code EngineOptions} 那道守卫看的是
     * {@code AGENT_API_KEY}，于是守卫放行、上游再炸一次，报的还是上游的话。
     *
     * <p>缺 Key 时<b>不</b>塞空串：那会盖掉 provider 自己的环境变量兜底，
     * 把「你有 DASHSCOPE_API_KEY 就能跑」这条路也堵死。
     *
     * <p>{@code stream(true)} 与显式 provider 的两条分支保持一致 ——
     * 关掉的话 TUI 会一次性收到整段，流式渲染那套代码就白写了。
     */
    private static ModelCreationContext creationContext(EngineConfig config) {
        ModelCreationContext.Builder builder = ModelCreationContext.builder().stream(true);
        if (config.hasApiKey()) {
            builder.apiKey(config.apiKey());
        }
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    private static void requireApiKey(EngineConfig config, String envName) {
        if (!config.hasApiKey()) {
            throw new IllegalStateException(
                    "缺少 API Key。设置环境变量 AGENT_API_KEY 或 " + envName);
        }
    }
}
