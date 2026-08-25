package io.agentharness.trace;

/**
 * 一条消息从投出去到用户看见，中间必经的六个环节。
 *
 * <p>这六个是<b>跨进程边界或跨存储边界</b>的点 —— 也就是出问题时最可能断掉、
 * 而断掉之后现场只剩"机器人不理我"的那些点。中间纯内存的转换不在此列：
 * 那些用单测能覆盖，不需要在生产链路上留痕。
 *
 * <p>标签留了对齐宽度，因为追踪最常见的用法是两个终端并排看，
 * 列对不齐就得逐行找。
 */
public enum TraceStage {

    /** 指令写进 inbox。客户端进程经手的唯一一环。 */
    INBOX_IN("→ inbox"),

    /** ready 令牌被摘到、执行权归属已定。 */
    READY_CLAIMED("✦ ready"),

    /** turn 启动。 */
    TURN_START("▶ turn"),

    /** turn 内的一个 step 事件。 */
    STEP_EVENT("· step"),

    /** 控制帧进入 ctrl-stream。 */
    CTRL_OUT("⇄ ctrl"),

    /** 消息进入 outbox。 */
    MESSAGE_OUT("← outbox");

    static final int LABEL_WIDTH = 8;

    private final String label;

    TraceStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
