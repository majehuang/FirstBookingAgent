package io.agentharness.cli;

import io.agentharness.cli.check.CheckResult;
import io.agentharness.cli.check.PostgresProbe;
import io.agentharness.cli.redis.RedisProbe;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.tui.port.Diagnostics;
import io.agentharness.tui.render.DisplayWidth;
import io.agentharness.tui.render.LineKind;
import io.agentharness.tui.render.RenderedLine;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /doctor} 与 {@code /keys} 的实现。
 *
 * <p>{@code /keys} 接手了原先 {@code ingress}／{@code egress}／{@code dispatcher}
 * 三个命令里唯一有排查价值的东西，并且比它们强一档：
 * 那三个打的是示例会话 {@code s-local} 的键，这里打的是<b>当前会话真实的键</b>，
 * 可以直接贴进 {@code redis-cli}。键名一律从 {@link KeyNamespace} 现算 ——
 * 写死的文档会在某次改名之后悄悄变成谎话。
 */
final class SessionDiagnostics implements Diagnostics {

    private final String redisUri;
    private final DataSourceProvider dataSource;
    private final String jdbcUrl;

    SessionDiagnostics(String redisUri, DataSourceProvider dataSource, String jdbcUrl) {
        this.redisUri = redisUri;
        this.dataSource = dataSource;
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public List<RenderedLine> doctor() {
        List<RenderedLine> lines = new ArrayList<>();

        lines.add(RenderedLine.of(LineKind.SYSTEM, "Redis  " + redisUri));
        List<CheckResult> redis = new RedisProbe(redisUri).run();
        redis.forEach(result -> lines.add(render(result)));

        lines.add(RenderedLine.of(LineKind.SYSTEM, "PostgreSQL  " + jdbcUrl));
        List<CheckResult> postgres = new PostgresProbe(dataSource, jdbcUrl).run();
        postgres.forEach(result -> lines.add(render(result)));

        long blocking = java.util.stream.Stream.concat(redis.stream(), postgres.stream())
                .filter(CheckResult::blocking).count();
        lines.add(blocking == 0
                ? RenderedLine.hint("  全部通过。")
                : RenderedLine.of(LineKind.ERROR, "  " + blocking + " 项阻塞。"));
        return List.copyOf(lines);
    }

    @Override
    public List<RenderedLine> keys(SessionRef session) {
        String id = session.sessionId();
        List<RenderedLine> lines = new ArrayList<>();
        lines.add(RenderedLine.of(LineKind.SYSTEM, "会话 " + session + " 用到的键"));
        lines.add(entry("inbox", KeyNamespace.inbox(id), "本进程写，worker 抽干"));
        lines.add(entry("ready 队列", KeyNamespace.READY, "唤醒队列，全局共用一条"));
        lines.add(entry("outbox", KeyNamespace.outbox(id), "worker 写，本进程订阅"));
        lines.add(entry("ctrl", KeyNamespace.ctrlStream(id), "控制帧，状态条的来源"));
        lines.add(entry("cursor", KeyNamespace.cursor(id), "抽干水位"));
        lines.add(entry("lease", KeyNamespace.lease(id), "执行权，被谁持着"));
        lines.add(entry("state", KeyNamespace.state(id), "会话状态"));
        lines.add(RenderedLine.hint(""));
        lines.add(RenderedLine.hint("  没有回复时的看法：inbox 有条目而 outbox 没有 = worker 没接上；"));
        lines.add(RenderedLine.hint("  两边都空 = 投递就没成功；lease 挂着不动 = 上一轮没收尾。"));
        return List.copyOf(lines);
    }

    /**
     * 一行键表。
     *
     * <p>按<b>终端列数</b>补空格，不是按字符数 —— "ready 队列"里的中文一个字占两列，
     * 用 {@code %-8s} 排出来的表在中文那一行会错开。状态条那边早就踩过这个坑。
     */
    private static RenderedLine entry(String name, String key, String note) {
        return RenderedLine.hint("  " + pad(name, NAME_COLUMNS) + " " + pad(key, KEY_COLUMNS)
                + " " + note);
    }

    private static final int NAME_COLUMNS = 10;
    private static final int KEY_COLUMNS = 34;

    private static String pad(String text, int columns) {
        int missing = columns - DisplayWidth.of(text);
        return missing <= 0 ? text : text + " ".repeat(missing);
    }

    private static RenderedLine render(CheckResult result) {
        return result.blocking()
                ? RenderedLine.of(LineKind.ERROR, result.format())
                : RenderedLine.hint(result.format());
    }
}
