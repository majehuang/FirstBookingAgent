package io.agentharness.engine;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Agent 引擎配置。
 *
 * <p>凭据一律从环境变量读，不进配置文件、不进命令行参数 ——
 * 命令行参数会进 shell 历史和进程列表。
 */
public record EngineConfig(
        Provider provider,
        String modelName,
        String apiKey,
        String baseUrl,
        Path workspace,
        int maxIters,
        String systemPrompt,
        boolean disableShellTool,
        boolean disableFilesystemTools) {

    public enum Provider {
        /** 交给 ModelRegistry 按模型名匹配 SPI 提供者。 */
        AUTO,
        /** OpenAI 及其兼容端点（Kimi / MiniMax / GLM / DeepSeek 走同一个提供者）。 */
        OPENAI,
        DASHSCOPE
    }

    private static final int DEFAULT_MAX_ITERS = 20;

    public EngineConfig {
        Objects.requireNonNull(provider, "provider");
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 不能为空");
        }
        maxIters = maxIters > 0 ? maxIters : DEFAULT_MAX_ITERS;
    }

    /**
     * 按约定的环境变量补全凭据。
     *
     * <p>{@code AGENT_API_KEY} 优先；没有时按提供者退回到各自的惯用变量名，
     * 这样用惯了 {@code DASHSCOPE_API_KEY} 的人不用改环境。
     */
    public EngineConfig resolveCredentials() {
        if (apiKey != null && !apiKey.isBlank()) {
            return this;
        }
        String resolved = firstNonBlank(
                System.getenv("AGENT_API_KEY"),
                provider == Provider.DASHSCOPE ? System.getenv("DASHSCOPE_API_KEY") : null,
                provider == Provider.OPENAI ? System.getenv("OPENAI_API_KEY") : null,
                System.getenv("DASHSCOPE_API_KEY"),
                System.getenv("OPENAI_API_KEY"));

        String resolvedBaseUrl = firstNonBlank(baseUrl, System.getenv("AGENT_BASE_URL"));

        return new EngineConfig(provider, modelName, resolved, resolvedBaseUrl,
                workspace, maxIters, systemPrompt, disableShellTool, disableFilesystemTools);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
