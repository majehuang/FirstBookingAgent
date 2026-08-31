package io.agentharness.task.dispatch;

/**
 * 消费者名 = <b>pod 名</b>，且<b>不加任何后缀</b>（P3-4 / GRP-004、GRP-005）。
 *
 * <p>加时间戳或 UUID 后缀是个很自然的念头 —— 它能顺手解决"本机多开时重名"。
 * 但消费组里的 consumer 元数据是<b>持久</b>的：每重启一次就留下一个再也不会回来的
 * 消费者，而它名下的 PEL 条目要等清理任务发现（idle &gt; 1h 且 pending = 0）才消失。
 * 后缀让这个集合<b>随重启次数无界增长</b>，重启一百次就有一百个死 consumer，
 * {@code XINFO CONSUMERS} 变得没法看，回收也要在里面翻。
 *
 * <p>而重名这个问题本来就不该在这里解决：<b>同时存活的 pod 本就不会重名</b>
 * （部署系统保证），同名重启则恰好是我们想要的 —— 新实例接上旧实例的 consumer 身份，
 * 原来那些 PEL 条目直接回到自己名下（GRP-007）。
 *
 * <p>所以这个类只做一件事：把名字校验后原样收下，并且<b>不提供任何加后缀的入口</b>。
 */
public record ConsumerName(String value) {

    /**
     * 长度上限。
     *
     * <p>Redis 本身不限制 consumer 名长度，这个上限是给人看的：
     * {@code XINFO CONSUMERS} 的输出、日志、告警里都会出现它，
     * 一个几百字符的名字会让每一处都没法读。
     */
    private static final int MAX_LENGTH = 128;

    public ConsumerName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "消费者名不能为空：它是令牌归属的唯一标识，空名会让整个消费组的"
                            + "PEL 归到同一个匿名消费者名下");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "消费者名超长：上限 " + MAX_LENGTH + "，实际 " + value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                throw new IllegalArgumentException(
                        "消费者名不能含空白字符：'" + value + "'。"
                                + "带空格的名字在 XINFO 输出与告警规则里无法可靠切分");
            }
        }
    }

    public static ConsumerName of(String value) {
        return new ConsumerName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
