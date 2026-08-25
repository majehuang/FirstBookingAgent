package io.agentharness.protocol;

/**
 * 会话标识。企业内部系统，{@code userId} 即员工 id，不折进 {@code sessionId}（见 开发规划.md D1）。
 */
public record SessionRef(String userId, String sessionId) {

    private static final int MAX_ID_LENGTH = 128;

    public SessionRef {
        Validate.notBlank(userId, "userId");
        Validate.notBlank(sessionId, "sessionId");
        Validate.maxLength(userId, MAX_ID_LENGTH, "userId");
        Validate.maxLength(sessionId, MAX_ID_LENGTH, "sessionId");
        assertIdentifierSafe(userId, "userId");
        assertIdentifierSafe(sessionId, "sessionId");
    }

    /**
     * id 不得含有 Redis 键名的结构字符。
     *
     * <p>校验放在协议层而不是键名层，是为了让非法 id <b>根本构造不出来</b>：
     * 只在键名层拦的话，用户能一路创建出一个会话，直到消息真的要投递时才失败 ——
     * 那时错误信息指向的是 Redis，而不是当初那个带冒号的 id。
     *
     * <p>放进一个 {@code :} 就能让 {@code a:b} 与 {@code a} + {@code b} 撞成同一个 key；
     * 放进 <code>{</code> 会改变 hash tag 的解析结果，进而改变落槽。
     */
    private static void assertIdentifierSafe(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':' || c == '{' || c == '}' || Character.isWhitespace(c)) {
                throw new ProtocolException(
                        field + " 含有非法字符 '" + c + "'：不允许 : { } 与空白");
            }
        }
    }

    public static SessionRef of(String userId, String sessionId) {
        return new SessionRef(userId, sessionId);
    }

    /** 状态行用的短标识，终端宽度有限，不展示完整 id。 */
    public String shortLabel() {
        return sessionId.length() <= 12 ? sessionId : sessionId.substring(0, 12) + "…";
    }

    @Override
    public String toString() {
        return userId + "/" + sessionId;
    }
}
