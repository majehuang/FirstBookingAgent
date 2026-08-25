package io.agentharness.tui.port;

import io.agentharness.protocol.SessionRef;
import io.agentharness.tui.render.RenderedLine;

import java.util.List;

/**
 * 会话内的诊断能力。
 *
 * <p>接手了原先 {@code ingress} / {@code egress} / {@code dispatcher} 三个命令里
 * 唯一有排查价值的那部分 —— 而且比它们强：那三个打的是示例会话的键
 * （{@code agent:inbox:{s-local}}），这里打的是<b>当前会话真实的键</b>，
 * 可以直接贴进 {@code redis-cli}。
 *
 * <p>放在 port 里而不是让 TUI 自己算：键的形态属于 Redis 那一层，
 * 终端界面不该知道 —— 换 HTTP 门面时被替换掉的正是这个实现。
 */
public interface Diagnostics {

    /** Redis 与 PostgreSQL 自检。会阻塞若干秒。 */
    List<RenderedLine> doctor();

    /** 当前会话用到的键。纯计算，不碰网络。 */
    List<RenderedLine> keys(SessionRef session);
}
