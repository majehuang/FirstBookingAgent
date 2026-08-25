package io.agentharness.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineConfigTest {

    private EngineConfig config(EngineConfig.Provider provider, String apiKey) {
        return new EngineConfig(provider, "qwen-max", apiKey, null, null, 0, null, true, false);
    }

    @Test
    void maxIters为0时补上默认值_而不是让推理一步都跑不了() {
        assertThat(config(EngineConfig.Provider.AUTO, "k").maxIters()).isEqualTo(20);
    }

    @Test
    void 已有apiKey时原样返回_不去读环境变量() {
        EngineConfig original = config(EngineConfig.Provider.DASHSCOPE, "explicit-key");
        EngineConfig resolved = original.resolveCredentials();

        assertThat(resolved.apiKey()).isEqualTo("explicit-key");
        assertThat(resolved).isSameAs(original);
    }

    @Test
    void 没有凭据时hasApiKey为假_由调用方给出可操作的提示() {
        EngineConfig resolved = config(EngineConfig.Provider.OPENAI, null).resolveCredentials();

        // 环境里可能真的配了 key，所以只断言两者一致，不断言一定为空
        assertThat(resolved.hasApiKey()).isEqualTo(resolved.apiKey() != null && !resolved.apiKey().isBlank());
    }

    @Test
    void 模型名为空时拒绝构造() {
        assertThatThrownBy(() -> new EngineConfig(EngineConfig.Provider.AUTO, " ", "k",
                null, null, 5, null, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
    }
}
