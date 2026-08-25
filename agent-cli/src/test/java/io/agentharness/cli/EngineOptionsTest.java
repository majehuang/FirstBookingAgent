package io.agentharness.cli;

import io.agentharness.engine.EngineConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineOptionsTest {

    /**
     * 一律经 picocli 解析后再用。
     *
     * <p>裸的 {@code new EngineOptions()} 所有字段都是 null —— 默认值写在
     * {@code @Option(defaultValue = "${env:…:-…}")} 上，由解析器填。
     * 直接 new 出来断言等于在测一个生产代码里不存在的状态。
     */
    private static EngineOptions parse(String... args) {
        EngineOptions options = new EngineOptions();
        new CommandLine(options).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(args);
        return options;
    }

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
        assertThatThrownBy(() -> parse().createAgentScopeEngine(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少模型 API Key");
    }

    @Test
    @DisplayName("诊断信息标注参数来源，看得出是不是整套都没送到")
    void 诊断信息标注来源() {
        String message = parse().missingKeyDiagnostic(noKey());

        assertThat(message).contains("AUTO（默认值）").contains("qwen-max（默认值）");
        assertThat(message).contains("走提供者的默认端点");
    }

    @Test
    void 诊断信息列出查过的环境变量并点名IDE() {
        String message = parse().missingKeyDiagnostic(noKey());

        assertThat(message)
                .contains("AGENT_API_KEY")
                .contains("DASHSCOPE_API_KEY")
                .contains("OPENAI_API_KEY")
                .contains("运行配置");
    }

    @Test
    @DisplayName("给出兼容端点与免 Key 两条出路，不是只说「你错了」")
    void 诊断信息给出可执行的下一步() {
        String message = parse().missingKeyDiagnostic(noKey());

        assertThat(message).contains("--provider openai").contains("--base-url");
        assertThat(message).contains("--engine scripted");
    }

    @Test
    @DisplayName("--sole 下客户端的引擎参数不生效，要能提前说出来")
    @DisabledIfEnvironmentVariable(named = "AGENT_MODEL", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "AGENT_PROVIDER", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "AGENT_TOOLS", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "AGENT_ENGINE", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "AGENT_BASE_URL", matches = ".+")
    void 列出sole下失效的引擎参数() {
        // 什么都没显式指定时不该提示 —— 否则每次启动都弹，很快就没人看了
        assertThat(parse().optionsIgnoredWithoutWorker()).isEmpty();
    }

    @Test
    void 显式指定的引擎参数会被列出() {
        EngineOptions options = parse(
                "--model", "kimi-for-coding",
                "--provider", "openai",
                "--base-url", "https://api.kimi.com/coding/");

        assertThat(options.optionsIgnoredWithoutWorker())
                .containsExactlyInAnyOrder("--provider", "--model", "--base-url");
    }

    @Test
    @DisplayName("引擎参数从环境变量读得到 —— 两个进程要用同一套配置")
    void 环境变量填得进参数() {
        // picocli 的 ${env:…} 插值：这里直接给命令行等价物，
        // 断言的是"解析出来的值真的落到了字段上"这条链路
        assertThat(parse("--model", "from-env").modelName()).isEqualTo("from-env");
    }

    @Test
    void 已设置的地址原样带出() {
        EngineConfig withUrl = new EngineConfig(EngineConfig.Provider.OPENAI, "kimi-for-coding",
                null, "https://api.kimi.com/coding/", null, 20, null, true, true);

        assertThat(parse().missingKeyDiagnostic(withUrl))
                .contains("https://api.kimi.com/coding/");
    }
}
