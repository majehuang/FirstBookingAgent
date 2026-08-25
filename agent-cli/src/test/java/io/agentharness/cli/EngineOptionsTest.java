package io.agentharness.cli;

import io.agentharness.engine.EngineConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineOptionsTest {

    private static EngineConfig noKey() {
        return new EngineConfig(EngineConfig.Provider.AUTO, "qwen-max", null, null,
                null, 20, null, true, true);
    }

    @Test
    @DisplayName("AUTO 也要拦住 —— 原先写的是 provider != AUTO，默认路径直接放行")
    @DisabledIfEnvironmentVariable(named = "AGENT_API_KEY", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void 默认provider缺Key时启动即失败() {
        // provider 不指定就是 AUTO，也就是绝大多数人的默认路径。
        // 放行的话会一路建到引擎里，直到第一次调模型才炸，报的还是上游的话
        assertThatThrownBy(() -> new EngineOptions().createAgentScopeEngine(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少模型 API Key");
    }

    @Test
    @DisplayName("诊断信息标注参数来源，看得出是不是整套都没送到")
    void 诊断信息标注来源() {
        String message = new EngineOptions().missingKeyDiagnostic(noKey());

        assertThat(message).contains("AUTO（默认值）").contains("qwen-max（默认值）");
        assertThat(message).contains("走提供者的默认端点");
    }

    @Test
    void 诊断信息列出查过的环境变量并点名IDE() {
        String message = new EngineOptions().missingKeyDiagnostic(noKey());

        assertThat(message)
                .contains("AGENT_API_KEY")
                .contains("DASHSCOPE_API_KEY")
                .contains("OPENAI_API_KEY")
                .contains("运行配置");
    }

    @Test
    @DisplayName("给出兼容端点与免 Key 两条出路，不是只说「你错了」")
    void 诊断信息给出可执行的下一步() {
        String message = new EngineOptions().missingKeyDiagnostic(noKey());

        assertThat(message).contains("--provider openai").contains("--base-url");
        assertThat(message).contains("--engine scripted");
    }

    @Test
    void 已设置的地址原样带出() {
        EngineConfig withUrl = new EngineConfig(EngineConfig.Provider.OPENAI, "kimi-for-coding",
                null, "https://api.kimi.com/coding/", null, 20, null, true, true);

        assertThat(new EngineOptions().missingKeyDiagnostic(withUrl))
                .contains("https://api.kimi.com/coding/");
    }
}
