package io.agentharness.cli;

/**
 * Redis 连接串。
 *
 * <p><b>只从环境变量读，没有命令行参数。</b>会话进程与 worker 进程必须连同一个 Redis ——
 * 它们之间没有任何直接调用，连错了的表现是"消息发出去了、永远没有回复"，
 * 而两边的日志都干干净净。一条 {@code export} 同时喂给两个进程，
 * 比在两条命令上各写一遍、然后指望它们一直一致要可靠。
 */
final class RedisEndpoint {

    static final String ENV = "AGENT_REDIS_URI";
    static final String DEFAULT_URI = "redis://localhost:6379";

    private RedisEndpoint() {
    }

    static String resolve() {
        String fromEnv = System.getenv(ENV);
        return fromEnv == null || fromEnv.isBlank() ? DEFAULT_URI : fromEnv;
    }

    /** 带来源的描述，给启动横幅与诊断用 —— 只报值看不出是不是环境变量没送到。 */
    static String describe() {
        String fromEnv = System.getenv(ENV);
        return fromEnv == null || fromEnv.isBlank()
                ? DEFAULT_URI + "（默认值）"
                : fromEnv + "（环境变量）";
    }
}
