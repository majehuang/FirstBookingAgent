package io.agentharness.protocol;

/** 控制状态里的 turn 阶段，驱动 TUI 状态行的图标与文案。 */
public enum TurnPhase {

    IDLE("空闲"),
    QUEUED("排队中"),
    THINKING("思考中"),
    CALLING_TOOL("调用工具"),
    WRITING("生成中"),
    STOPPING("正在停止"),
    DONE("已完成"),
    FAILED("已失败");

    private final String label;

    TurnPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == IDLE;
    }
}
