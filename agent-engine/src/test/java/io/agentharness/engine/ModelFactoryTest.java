package io.agentharness.engine;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTO 路径的凭据传递。
 *
 * <p>这条路径原先调的是 {@code ModelRegistry.resolve(modelName)} 单参重载，
 * 它内部用 {@code ModelCreationContext.empty()} —— 于是 apiKey 与 baseUrl
 * 一个都到不了 provider，上游只能退回读它自己认的环境变量
 * （{@code DASHSCOPE_API_KEY} / {@code OPENAI_API_KEY} …）。
 * 而 {@link EngineOptions} 那道守卫看的是 {@code AGENT_API_KEY}，会放行 ——
 * 症状是「守卫通过了，然后炸在上游那句话上」。
 */
class ModelFactoryTest {

    private static final String MODEL_ID = "test-model-auto";

    @AfterEach
    void tearDown() {
        // 注册表是静态的，不清会漏到别的用例
        ModelRegistry.reset();
    }

    /** 捕获 provider 实际收到的 context。返回值只是为了让注册表拿到一个非 null 的 Model。 */
    private static AtomicReference<ModelCreationContext> captureContext() {
        AtomicReference<ModelCreationContext> seen = new AtomicReference<>();
        ModelRegistry.registerFactory(MODEL_ID, (modelId, context) -> {
            seen.set(context);
            return stubModel(modelId);
        });
        return seen;
    }

    private static Model stubModel(String modelId) {
        return OpenAIChatModel.builder().apiKey("stub").modelName(modelId).build();
    }

    private static EngineConfig autoConfig(String apiKey, String baseUrl) {
        return new EngineConfig(EngineConfig.Provider.AUTO, MODEL_ID, apiKey, baseUrl,
                null, 20, null, true, true);
    }

    @Test
    @DisplayName("AUTO 也要把 AGENT_API_KEY 交给 provider，否则上游只认自己的环境变量")
    void auto路径把apiKey传给provider() {
        AtomicReference<ModelCreationContext> seen = captureContext();

        ModelFactory.create(autoConfig("sk-from-agent-api-key", null));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getApiKey()).isEqualTo("sk-from-agent-api-key");
    }

    @Test
    @DisplayName("--base-url 在 AUTO 下同样不能丢，走内部网关时全靠它")
    void auto路径把baseUrl传给provider() {
        AtomicReference<ModelCreationContext> seen = captureContext();

        ModelFactory.create(autoConfig("sk-test", "https://api.kimi.com/coding/"));

        assertThat(seen.get().getBaseUrl()).isEqualTo("https://api.kimi.com/coding/");
    }

    @Test
    @DisplayName("流式与显式 provider 两条分支保持一致 —— 关掉的话 TUI 会一次性收到整段")
    void auto路径保持流式() {
        AtomicReference<ModelCreationContext> seen = captureContext();

        ModelFactory.create(autoConfig("sk-test", null));

        assertThat(seen.get().getStream()).isTrue();
    }

    @Test
    @DisplayName("没有 Key 时不硬塞空串，留给 provider 自己的环境变量兜底")
    void 缺Key时不下发空凭据() {
        AtomicReference<ModelCreationContext> seen = captureContext();

        ModelFactory.create(autoConfig(null, null));

        assertThat(seen.get().getApiKey()).isNull();
    }

    @Test
    @DisplayName("走真实 SPI：qwen-max 在 AUTO 下不再要求 DASHSCOPE_API_KEY")
    @DisabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
    void 默认模型在AUTO下认AGENT_API_KEY() {
        // 不注册桩工厂，让它落到 classpath 上真正的 DashScopeModelProvider。
        // 修之前这里抛的是上游那句
        // "Environment variable DASHSCOPE_API_KEY is required to auto-create model: qwen-max" ——
        // 也就是"守卫说 Key 有、上游说 Key 没有"的那个现场
        assertThat(ModelFactory.create(
                new EngineConfig(EngineConfig.Provider.AUTO, "qwen-max", "sk-fake", null,
                        null, 20, null, true, true)))
                .isNotNull();
    }

    @Test
    @DisplayName("解析不出提供者时报的仍是我们的话，而不是上游的堆栈")
    void 无人能解析时给出可执行的提示() {
        assertThatThrownBy(() -> ModelFactory.create(
                new EngineConfig(EngineConfig.Provider.AUTO, "no-such-model-xyz", "sk-test", null,
                        null, 20, null, true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--provider openai|dashscope");
    }
}
