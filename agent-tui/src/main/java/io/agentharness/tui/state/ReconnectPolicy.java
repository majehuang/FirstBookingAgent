package io.agentharness.tui.state;

import java.time.Duration;

/**
 * 断线重连的退避策略（P4-6 / P4-7）。
 *
 * <p>指数退避、有上限：1s → 2s → 4s → … → 30s 封顶。
 * 起步 1 秒而不是立即：断线的最常见原因是服务端或网络整体抖动，
 * 全体客户端零间隔重试等于给刚要恢复的服务端再来一波。
 * 封顶 30 秒而不是无限翻倍：翻到分钟级之后，用户看到的是"网络早就好了它还不连"。
 *
 * <p>纯函数 —— 重连的时序编排在 TuiApp（IO 外壳，豁免覆盖率），
 * 所以"等多久"这个唯一有分支的判定必须放在这里、可以直接断言。
 */
public final class ReconnectPolicy {

    static final Duration BASE = Duration.ofSeconds(1);
    static final Duration MAX = Duration.ofSeconds(30);

    private ReconnectPolicy() {
    }

    /**
     * 第 {@code attempt} 次重连前要等多久。
     *
     * @param attempt 从 1 开始的连续失败次数；成功收到任何一帧后归零
     */
    public static Duration delayFor(int attempt) {
        if (attempt <= 1) {
            return BASE;
        }
        // 先比对指数上限再左移，避免 attempt 很大时移位溢出绕回小值
        if (attempt - 1 >= 5) {
            return MAX;
        }
        Duration doubled = BASE.multipliedBy(1L << (attempt - 1));
        return doubled.compareTo(MAX) > 0 ? MAX : doubled;
    }
}
