package io.agentharness.tui.port;

import java.util.Optional;

/**
 * 装配用的组合端口。TUI 只认这一个类型，
 * 换 loopback / Redis / HTTP 实现时不需要改主流程。
 */
public interface AgentBackend extends ChatGateway, InstructionSink, AutoCloseable {

    /** 后端名称，展示在状态行上，便于确认当前连的是谁。 */
    String name();

    /**
     * 历史拉取能力。
     *
     * <p>可选：还没有消息表的后端返回 empty，此时 TUI 检测到空窗只能提示，
     * 补不回数据。有真相源的后端返回实现，空窗就能自愈。
     */
    default Optional<HistorySource> history() {
        return Optional.empty();
    }

    /**
     * 诊断能力（{@code /doctor}、{@code /keys}）。
     *
     * <p>可选：拿不出来的后端返回 empty，此时那两条斜杠命令会说明为什么没有，
     * 而不是打一段空表。
     */
    default Optional<Diagnostics> diagnostics() {
        return Optional.empty();
    }

    /** 追踪开关（{@code /trace}）。可选：没有追踪落点的后端返回 empty。 */
    default Optional<TraceControl> traceControl() {
        return Optional.empty();
    }

    @Override
    default void close() {
    }
}
