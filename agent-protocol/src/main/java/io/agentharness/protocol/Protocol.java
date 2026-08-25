package io.agentharness.protocol;

/** 协议版本。 */
public final class Protocol {

    /**
     * 当前版本。
     *
     * <p>v1.1 相对 v1 的唯一变更是<b>用户消息也落库并推流</b>：
     * {@link ClientMessage} 增加 {@code role}，{@link MessageType} 增加 {@code TEXT}。
     * 客户端不再本地回显，自己发的话要等服务端推回来才渲染。
     *
     * <p>这是一次<b>破坏性变更</b>：v1 客户端解析 v1.1 消息时 {@code role} 会缺失。
     * 由于 v1 未曾对外发布，直接推进版本号而不做双版本并行。
     */
    public static final String VERSION = "1.1";

    private Protocol() {
    }
}
