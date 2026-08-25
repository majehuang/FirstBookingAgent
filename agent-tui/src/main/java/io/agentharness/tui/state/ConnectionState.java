package io.agentharness.tui.state;

/** 与后端的连接状态，只影响状态行展示与是否允许输入。 */
public enum ConnectionState {

    CONNECTING("连接中"),
    CONNECTED("已连接"),
    RECONNECTING("重连中"),
    DISCONNECTED("已断开");

    private final String label;

    ConnectionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean canSend() {
        return this == CONNECTED;
    }
}
