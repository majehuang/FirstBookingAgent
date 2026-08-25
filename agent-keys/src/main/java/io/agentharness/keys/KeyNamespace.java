package io.agentharness.keys;

import java.nio.charset.StandardCharsets;

/**
 * Redis 键名的<b>唯一</b>生成入口。
 *
 * <p>模块之间没有 RPC、没有服务发现、没有共享内存 —— 接口就是这张键表
 * （见 开发规划.md C 节 / D 节）。所以这个类不是工具类，它是<b>模块间的契约本身</b>：
 * 任何地方手工拼接 key，契约就出现了第二个版本，而两个版本不一致时
 * 表现是"消息投进去了但没人消费"，没有任何报错。
 *
 * <p>{@code KeyLiteralGuardTest} 会扫描源码阻止绕过这里。
 *
 * <h2>为什么要 hash tag</h2>
 * 让同一个 session 的几个 key 落在同一个槽，摘牌脚本才可能原子执行
 * （校验 lease + 检查 inbox + DEL lease 必须同槽，INV-2）。
 * 标准版上 hash tag 退化为普通字符、零成本；将来迁集群版时键名一个字都不用改。
 *
 * <h2>分片数 256 永不修改</h2>
 * 改分片数等于把所有已存在的 key 换一个名字 —— 那是数据迁移，不是配置调整（INV-9）。
 */
public final class KeyNamespace {

    /**
     * 分片数。**永不修改。**
     *
     * <p>改这个值会让同一个 sessionId 算出不同的 hash tag，
     * 于是所有在途的 inbox / lease / cursor 都变成孤儿：
     * 旧 key 没人读、新 key 没有数据，而 Redis 视角一切正常。
     */
    public static final int SHARD_COUNT = 256;

    /**
     * hash tag 的数字位数。
     *
     * <p>三位零填充：分片取值是 0–255，两位在 100 以上会溢出成不定长，
     * 而不定长的 key 前缀会让运维按前缀扫描时漏掉一部分。
     * 开发规划 C 节里的 {@code {s07}} 是示意写法，这里取其含义而非字面长度。
     */
    private static final int SHARD_DIGITS = 3;

    private static final String SESSION_PREFIX = ":sess:";

    /**
     * 全局唤醒队列。**刻意不分片**：每个 pod 只监听一条 stream、只占一条阻塞连接。
     *
     * <p>代价是投递侧无法原子（ready 与分片 inbox 必然不同槽），
     * 退化为两条命令且顺序不可颠倒 —— inbox 先、ready 后（INV-1）。
     */
    public static final String READY = "ready";

    /** FNV-1a 32 位的标准参数。 */
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private static final int MAX_SESSION_ID_LENGTH = 128;

    private KeyNamespace() {
    }

    // ---------- 分片 ----------

    /**
     * sessionId 的分片号，取值 {@code 0..255}。
     *
     * <p>用 FNV-1a 而不是 {@code String.hashCode()}：后者虽然在 Java 规范里有定义，
     * 但它的低位分布对 {@code % 256} 并不均匀（短字符串尤其明显），
     * 而且一旦哪天想换语言实现同一套 key，规范就只能靠"照抄 JDK 的算法"来传达。
     * FNV-1a 是完全写在这里的十几行运算，跨语言、跨版本都能得到同一个结果。
     */
    public static int shardOf(String sessionId) {
        validate(sessionId);
        int hash = FNV_OFFSET_BASIS;
        for (byte b : sessionId.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        // 无符号取模：直接 % 会因为 hash 为负而得到负数，
        // 而 Math.abs(Integer.MIN_VALUE) 仍然是负数 —— 这是这类代码最经典的一个坑
        return Integer.remainderUnsigned(hash, SHARD_COUNT);
    }

    /** 形如 {@code {s007}}。同一 session 的所有 key 共用它，从而落在同一个槽。 */
    public static String hashTag(String sessionId) {
        return "{s" + pad(shardOf(sessionId)) + "}";
    }

    // ---------- session 级 key ----------

    /** 有序指令队列（消息 + 控制），按条数或时间裁剪。 */
    public static String inbox(String sessionId) {
        return sessionKey(sessionId, "inbox");
    }

    /** 客户端消息批，保留 5–10 分钟，受 turnStartId 保护（INV-6）。 */
    public static String outbox(String sessionId) {
        return sessionKey(sessionId, "outbox");
    }

    /**
     * 双游标（msg / ctrl）。
     *
     * <p>保留期<b>必须 ≥ inbox 保留期</b>：cursor 先过期的话，
     * 空闲久的 session 被唤醒会从头重放全部历史指令。最省事的做法是不设 TTL，
     * 随 session 一起显式清理（开发规划 C 节的陷阱）。
     */
    public static String cursor(String sessionId) {
        return sessionKey(sessionId, "cursor");
    }

    /** 执行权。值每次抢占唯一，释放用值比对删除（INV-3）。 */
    public static String lease(String sessionId) {
        return sessionKey(sessionId, "lease");
    }

    /** 控制状态快照，含 ctrlId 水位。 */
    public static String state(String sessionId) {
        return sessionKey(sessionId, "state");
    }

    /** 控制状态帧序列，每次变更追加一帧。 */
    public static String ctrlStream(String sessionId) {
        return sessionKey(sessionId, "ctrl-stream");
    }

    /**
     * 判断两个 key 是否同槽 —— 摘牌脚本与 ctrl 脚本的前提条件。
     *
     * <p>集群版上不同槽的 key 出现在同一个 Lua 脚本里会直接报 CROSSSLOT；
     * 标准版上不报错，于是问题会一直藏到迁集群那天。
     */
    public static boolean sameSlot(String keyA, String keyB) {
        String tagA = extractHashTag(keyA);
        String tagB = extractHashTag(keyB);
        return tagA != null && tagA.equals(tagB);
    }

    static String extractHashTag(String key) {
        int open = key.indexOf('{');
        if (open < 0) {
            return null;
        }
        int close = key.indexOf('}', open);
        return close < 0 ? null : key.substring(open, close + 1);
    }

    private static String sessionKey(String sessionId, String suffix) {
        return hashTag(sessionId) + SESSION_PREFIX + sessionId + ":" + suffix;
    }

    private static String pad(int shard) {
        return String.format("%0" + SHARD_DIGITS + "d", shard);
    }

    /**
     * sessionId 必须不含 key 的结构字符。
     *
     * <p>放进去一个 {@code :} 就能让 {@code a:b} 与 {@code a} + {@code b} 撞成同一个 key；
     * 放进去 <code>{</code> 会改变 hash tag 的解析结果，进而改变落槽 ——
     * 两者都是"平时看不出、某个用户的 id 恰好带了特殊字符时才炸"的类型。
     */
    private static void validate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "sessionId 超长：上限 " + MAX_SESSION_ID_LENGTH + "，实际 " + sessionId.length());
        }
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            if (c == ':' || c == '{' || c == '}' || Character.isWhitespace(c)) {
                throw new IllegalArgumentException(
                        "sessionId 含有非法字符 '" + c + "'：不允许 : { } 与空白");
            }
        }
    }
}
