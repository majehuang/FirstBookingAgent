package io.agentharness.cli;

import io.agentharness.cli.check.CheckResult;
import io.agentharness.cli.check.PostgresProbe;
import io.agentharness.cli.redis.RedisProbe;
import io.agentharness.store.datasource.DataSourceProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 环境自检 —— 本地起好依赖之后第一件事就是跑它。
 *
 * <p>Redis 那部分是开发计划 P0-1 的自动化版本：
 * 引擎版本、架构、Lua、以及脚本内 {@code XADD ... *}。
 * 任一项失败都会改变 P3 的设计，所以越早跑越好。
 *
 * <p>退出码：0 全部通过；2 存在阻塞项。CI 里可以直接当门禁用。
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "检查 Redis 与 PostgreSQL 是否满足设计前提")
public final class DoctorCommand implements Callable<Integer> {

    @Option(names = {"-r", "--redis"}, description = "Redis 连接串（默认 ${DEFAULT-VALUE}）")
    private String redisUri = "redis://localhost:6379";

    @Option(names = "--skip-redis", description = "跳过 Redis 检查")
    private boolean skipRedis;

    @Option(names = "--skip-db", description = "跳过 PostgreSQL 检查")
    private boolean skipDb;

    @Mixin
    private DbOptions db = new DbOptions();

    @Override
    public Integer call() {
        List<CheckResult> all = new ArrayList<>();

        if (!skipRedis) {
            System.out.println("Redis  " + redisUri);
            List<CheckResult> results = new RedisProbe(redisUri).run();
            results.forEach(r -> System.out.println(r.format()));
            all.addAll(results);
            System.out.println();
        }

        if (!skipDb) {
            System.out.println("PostgreSQL  " + db.jdbcUrl);
            List<CheckResult> results = probePostgres();
            results.forEach(r -> System.out.println(r.format()));
            all.addAll(results);
            System.out.println();
        }

        long blocking = all.stream().filter(CheckResult::blocking).count();
        if (blocking == 0) {
            System.out.println("  全部通过。");
            return 0;
        }
        System.out.println("  " + blocking + " 项阻塞。Redis 侧任一项失败都会改变 P3 的设计，"
                + "详见 开发计划.md 的 P0-1 与风险 R-1。");
        return 2;
    }

    private List<CheckResult> probePostgres() {
        try (DataSourceProvider provider = db.openProvider()) {
            return new PostgresProbe(provider, db.jdbcUrl).run();
        } catch (RuntimeException e) {
            return List.of(CheckResult.fail("连接", db.jdbcUrl + " —— " + e.getMessage()));
        }
    }
}
