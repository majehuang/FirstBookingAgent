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

    /*
     * 每个参数都带一个环境变量兜底，顺序是 命令行 > 环境变量 > 默认值。
     *
     * 理由是默认形态下同一套引擎参数要喂给两个地方：会话进程里的内嵌 worker，
     * 以及可能另起的 agent worker。两条命令上各写一遍、然后指望它们一直一致，
     * 是这类系统里最常见的一种"配置漂移"—— 症状是"换了模型只有一半生效"。
     *
     * 用 picocli 的 ${env:VAR:-默认} 插值而不是自己读：这样 --help 里
     * ${DEFAULT-VALUE} 显示的是解析之后的真实默认，而不是代码里写死的那个。
     */

    @Option(names = "--engine", defaultValue = "${env:AGENT_ENGINE:-AGENTSCOPE}",
            description = "引擎：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}，env AGENT_ENGINE）")
    private Kind kind;

    @Option(names = "--tools", defaultValue = "${env:AGENT_TOOLS:-NONE}",
            description = "业务工具：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}，env AGENT_TOOLS）")
    private Tools tools;

    @Option(names = "--provider", defaultValue = "${env:AGENT_PROVIDER:-AUTO}",
            description = "模型提供者：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}，env AGENT_PROVIDER）")
    private EngineConfig.Provider provider;

    @Option(names = "--model", defaultValue = "${env:AGENT_MODEL:-qwen-max}",
            description = "模型名（默认 ${DEFAULT-VALUE}，env AGENT_MODEL）")
    private String model;

    @Option(names = "--base-url", defaultValue = "${env:AGENT_BASE_URL:-}",
            description = "模型服务地址，走内部网关时用（env AGENT_BASE_URL）")
    private String baseUrl;

    @Option(names = "--max-iters", defaultValue = "${env:AGENT_MAX_ITERS:-20}",
            description = "单轮最大推理迭代数（默认 ${DEFAULT-VALUE}，env AGENT_MAX_ITERS）")
    private int maxIters;

    @Option(names = "--system-prompt", defaultValue = "${env:AGENT_SYSTEM_PROMPT:-}",
            description = "系统提示词（env AGENT_SYSTEM_PROMPT）")
    private String systemPrompt;

    @Option(names = "--workspace", defaultValue = "${env:AGENT_WORKSPACE:-}",
            description = "workspace 根目录（默认 ~/.agent-cli/workspace，env AGENT_WORKSPACE）")
    private Path workspace;

    @Option(names = "--enable-shell",
            description = "打开 shell 工具。本期不用沙箱，命令会直接在本机执行 —— 默认关闭")
    private boolean enableShell;

    @Option(names = "--enable-filesystem",
            description = "打开文件系统工具（读写/列目录/glob）。实测可经 ../ 穿越到宿主机根目录 —— 默认关闭")
    private boolean enableFilesystem;

    /**
     * 列出被显式指定、但在 {@code --sole} 下<b>不会生效</b>的引擎参数。
     *
     * <p>{@code --sole} 下客户端根本不调模型，推理全在外部 worker 里 ——
     * 这些参数配在客户端上完全没有作用，而症状是"模型好像没换"，
     * 极难联想到是配错了进程。
     */
    java.util.List<String> optionsIgnoredWithoutWorker() {
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
        EngineConfig config = new EngineConfig(provider, model, null, baseUrl, resolveWorkspace(),
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
                + "\n  提供者  " + describe(provider.name(),
                        provider != EngineConfig.Provider.AUTO, "AGENT_PROVIDER")
                + "\n  模型    " + describe(model, !"qwen-max".equals(model), "AGENT_MODEL")
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

    /**
     * 标出这个值<b>从哪来</b>。
     *
     * <p>光报值看不出问题在哪，而"模型 qwen-max（默认值）"一眼能看出
     * 是整套参数都没送到 —— IDE 的运行配置不继承 shell 的 export，这个坑最常见。
     */
    private static String describe(String value, boolean explicit, String envName) {
        if (!explicit) {
            return value + "（默认值）";
        }
        String fromEnv = System.getenv(envName);
        boolean matchesEnv = fromEnv != null && fromEnv.equalsIgnoreCase(value);
        return value + (matchesEnv ? "（环境变量 " + envName + "）" : "（命令行）");
    }

    private ToolBundle toolBundle() {
        return tools == Tools.HOTEL ? HotelTools.demo() : ToolBundle.empty();
    }

    /**
     * 默认 workspace 放在用户目录下，而不是框架默认的 {@code ./.agentscope/workspace}。
     *
     * <p>后者会在当前工作目录里长出一个目录树 —— 在项目根下跑一次就污染了仓库。
     *
     * <p>空串按"没给"处理：{@code ${env:AGENT_WORKSPACE:-}} 在环境变量缺席时
     * 给出的是空路径，不是 null。
     */
    private Path resolveWorkspace() {
        if (workspace != null && !workspace.toString().isBlank()) {
            return workspace;
        }
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".agent-cli", "workspace");
    }
}
