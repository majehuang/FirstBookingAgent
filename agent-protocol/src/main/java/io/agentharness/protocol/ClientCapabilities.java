package io.agentharness.protocol;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 客户端能力声明 —— 建连时由客户端上报，服务端据此决定下发什么。
 *
 * <p>为什么需要它：富消息（卡片、图片、语音）是逐步加上去的，
 * 而客户端的升级节奏不由服务端控制。没有能力协商的话，
 * 服务端一上线新类型，老客户端就只能看到一行 {@code fallbackText} ——
 * 那已经是最好的情况，更糟的是它根本没实现那个分支。
 *
 * <p>协商的语义是<b>服务端降级</b>而不是拒绝：不支持的类型在推送前被压成纯文本，
 * 内容不丢，只是失去了结构。
 */
public record ClientCapabilities(String protocolVersion, Set<MessageType> supportedTypes) {

    public ClientCapabilities {
        Validate.notBlank(protocolVersion, "protocolVersion");
        Validate.required(supportedTypes, "supportedTypes");
        supportedTypes = supportedTypes.isEmpty()
                ? textOnly()
                : Set.copyOf(supportedTypes);
    }

    /**
     * 缺省能力：**只保证文本**。
     *
     * <p>客户端不上报时按这个走。保守的默认值意味着新客户端必须显式声明才能拿到卡片，
     * 但反过来（默认全支持）会让任何一个老客户端在新类型上线时静默出问题。
     */
    public static ClientCapabilities defaults() {
        return new ClientCapabilities(Protocol.VERSION, textOnly());
    }

    /** 全能力，服务端自测与新客户端使用。 */
    public static ClientCapabilities full() {
        return new ClientCapabilities(Protocol.VERSION, EnumSet.allOf(MessageType.class));
    }

    public static ClientCapabilities of(String protocolVersion, Set<MessageType> supported) {
        return new ClientCapabilities(protocolVersion, supported);
    }

    public boolean supports(MessageType type) {
        return supportedTypes.contains(type);
    }

    /**
     * 按能力降级一条消息。
     *
     * <p>不支持的类型压成 {@link MessageType#TEXT}，正文取 {@code fallbackText} ——
     * 这正是 fallbackText 必填的原因：它是降级路径上唯一还剩下的东西。
     * payload 一并丢弃，因为客户端既然不认识这个类型，payload 对它就是噪音。
     */
    public ClientMessage degrade(ClientMessage message) {
        if (supports(message.type())) {
            return message;
        }
        return new ClientMessage(message.msgSeq(), message.replyId(), message.blockId(),
                message.role(), MessageType.TEXT, message.fallbackText(),
                Map.of(), message.createdAt());
    }

    private static Set<MessageType> textOnly() {
        return EnumSet.of(MessageType.TEXT, MessageType.TEXT_DELTA, MessageType.TEXT_END,
                MessageType.SYSTEM, MessageType.ERROR, MessageType.UNKNOWN);
    }
}
