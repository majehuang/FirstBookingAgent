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

        if (!config.hasApiKey() && provider != EngineConfig.Provider.AUTO) {
            throw new IllegalStateException(
                    "缺少 API Key。设置 AGENT_API_KEY，或按提供者设置 DASHSCOPE_API_KEY / OPENAI_API_KEY");
        }
        return AgentScopeEngine.create(config, jdbc, toolBundle());
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
