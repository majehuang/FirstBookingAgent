package io.agentharness.cli;

import io.agentharness.engine.AgentScopeEngine;
import io.agentharness.engine.EngineConfig;
import io.agentharness.engine.ScriptedTurnEngine;
import io.agentharness.engine.ToolBundle;
import io.agentharness.engine.TurnEngine;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.tools.hotel.HotelTools;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** 引擎参数。会话模式与 worker 共用。 */
public final class EngineOptions {

    /** 引擎种类。 */
    public enum Kind {
        /** 真实 AgentScope 引擎。 */
        AGENTSCOPE,
        /**
         * 可控引擎，给定输入必定产出同一串事件。
         *
         * <p>端到端测试用它取代模型：真实模型既不确定又要花钱，
         * 拿它验证"1000 个片段是否保持顺序"会又慢又偶发失败。
         */
        SCRIPTED
    }

    /** 装配哪套业务工具。 */
    public enum Tools {
        /** 只有框架自带的工具。 */
        NONE,
        /** 酒店场景：查询工具 + 卡片表达工具 + 补全 middleware + 渲染器。 */
        HOTEL
    }

    @Option(names = "--engine", description = "引擎：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}）")
    private Kind kind = Kind.AGENTSCOPE;

    @Option(names = "--tools", description = "业务工具：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}）")
    private Tools tools = Tools.NONE;

    @Option(names = "--provider", description = "模型提供者：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}）")
    private EngineConfig.Provider provider = EngineConfig.Provider.AUTO;

    @Option(names = "--model", description = "模型名（默认 ${DEFAULT-VALUE}）")
    private String model = "qwen-max";

    @Option(names = "--base-url", description = "模型服务地址，走内部网关时用；留空读 AGENT_BASE_URL")
    private String baseUrl;

    @Option(names = "--max-iters", description = "单轮最大推理迭代数（默认 ${DEFAULT-VALUE}）")
    private int maxIters = 20;


    @Option(names = "--system-prompt", description = "系统提示词")
    private String systemPrompt;

    @Option(names = "--workspace", description = "workspace 根目录（默认 ~/.agent-cli/workspace）")
    private Path workspace = defaultWorkspace();

    @Option(names = "--enable-shell",
            description = "打开 shell 工具。本期不用沙箱，命令会直接在本机执行 —— 默认关闭")
    private boolean enableShell;

    @Option(names = "--enable-filesystem",
            description = "打开文件系统工具（读写/列目录/glob）。实测可经 ../ 穿越到宿主机根目录 —— 默认关闭")
    private boolean enableFilesystem;

    /**
     * 列出被显式指定、但在 {@code --backend redis} 下<b>不会生效</b>的引擎参数。
     *
     * <p>远端模式下客户端根本不调模型，推理全在 worker 里 ——
     * 这些参数配在客户端上完全没有作用，而症状是"模型好像没换"，
     * 极难联想到是配错了进程。
     */
    java.util.List<String> optionsIgnoredInRemoteMode() {
        java.util.List<String> ignored = new java.util.ArrayList<>();
        if (kind != Kind.AGENTSCOPE) {
            ignored.add("--engine");
        }
        if (tools != Tools.NONE) {
            ignored.add("--tools");
        }
        if (provider != EngineConfig.Provider.AUTO) {
            ignored.add("--provider");
        }
        if (!"qwen-max".equals(model)) {
            ignored.add("--model");
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            ignored.add("--base-url");
        }
        if (maxIters != DEFAULT_MAX_ITERS) {
            ignored.add("--max-iters");
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ignored.add("--system-prompt");
        }
        return java.util.List.copyOf(ignored);
    }

    private static final int DEFAULT_MAX_ITERS = 20;

    String modelName() {
        return kind == Kind.SCRIPTED ? "scripted" : model;
    }

    /** 按 {@code --engine} 建引擎。 */
    TurnEngine createEngine(Jdbc jdbc) {
        // 可控引擎也带上渲染器：伪造的只是工具调用与结果，渲染要走真实路径，
        // 否则端到端验的是另一套代码
        return kind == Kind.SCRIPTED
                ? new ScriptedTurnEngine(toolBundle().renderers())
                : createAgentScopeEngine(jdbc);
    }

    AgentScopeEngine createAgentScopeEngine(Jdbc jdbc) {
        EngineConfig config = new EngineConfig(provider, model, null, baseUrl, workspace,
                maxIters, systemPrompt, !enableShell, !enableFilesystem).resolveCredentials();

        // 刻意不放过 AUTO。原先这里写的是 provider != AUTO，
        // 于是默认路径（provider 就是 AUTO）缺 Key 根本不报错，
        // 一路建到引擎里、直到第一次调模型才炸，报的还是上游的话。
        // 守卫只在用户显式指定 provider 时生效 —— 而那恰恰是他已经想清楚的情况
        if (!config.hasApiKey()) {
            throw new IllegalStateException(missingKeyDiagnostic(config));
        }
        return AgentScopeEngine.create(config, jdbc, toolBundle());
    }

    /**
     * 缺 Key 时把当前解析结果摊开。
     *
     * <p>和数据库那边同一个思路：光说"缺 Key"看不出问题在哪，
     * 而"提供者 AUTO（默认值）、模型 qwen-max（默认值）"一眼能看出
     * 是整套模型参数都没送到，而不只是少一个环境变量。
     */
    String missingKeyDiagnostic(EngineConfig config) {
        return "缺少模型 API Key。\n"
                + "\n  提供者  " + describe(provider.name(), provider != EngineConfig.Provider.AUTO)
                + "\n  模型    " + describe(model, !"qwen-max".equals(model))
                + "\n  地址    " + (config.baseUrl() == null || config.baseUrl().isBlank()
                        ? "未设置，走提供者的默认端点" : config.baseUrl())
                + "\n"
                + "\n  这些环境变量都是空的：AGENT_API_KEY、DASHSCOPE_API_KEY、OPENAI_API_KEY"
                + "\n  在 IDE 里运行要在「运行配置 → 环境变量」里设 —— 它不继承 shell 的 export。"
                + "\n"
                + "\n  接 Kimi 这类 OpenAI 兼容端点还要三个参数："
                + "\n    --provider openai --base-url https://api.kimi.com/coding/ --model kimi-for-coding"
                + "\n"
                + "\n  只想验链路、不调模型的话用 --engine scripted，它不需要任何 Key。";
    }

    private static String describe(String value, boolean explicit) {
        return value + (explicit ? "（命令行）" : "（默认值）");
    }

    private ToolBundle toolBundle() {
        return tools == Tools.HOTEL ? HotelTools.demo() : ToolBundle.empty();
    }

    /**
     * 默认 workspace 放在用户目录下，而不是框架默认的 {@code ./.agentscope/workspace}。
     *
     * <p>后者会在当前工作目录里长出一个目录树 —— 在项目根下跑一次就污染了仓库。
     */
    private static Path defaultWorkspace() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".agent-cli", "workspace");
    }
}
