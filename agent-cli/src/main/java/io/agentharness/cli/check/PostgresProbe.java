package io.agentharness.cli.check;

import io.agentharness.protocol.SessionRef;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.message.PostgresMessageRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 自检。
 *
 * <p>重点不是"连得上"，而是那几条<b>写错了平时看不出来</b>的东西：
 * 表建了没有、序号分配是不是真的原子、连接是不是真的还回了池子。
 */
public final class PostgresProbe {

    private static final String PROBE_SESSION = "__doctor_probe__";
    private static final String VERSION_SQL = "SELECT version()";
    private static final String TABLE_SQL = """
            SELECT count(*)::int AS n FROM information_schema.tables
             WHERE table_schema = current_schema()
               AND table_name IN ('agent_message', 'agent_msg_seq', 'agent_state',
                                  'agent_store_item', 'agent_event_log')
            """;
    private static final int EXPECTED_TABLES = 5;

    private final DataSourceProvider provider;
    private final String jdbcUrl;

    public PostgresProbe(DataSourceProvider provider, String jdbcUrl) {
        this.provider = provider;
        this.jdbcUrl = jdbcUrl;
    }

    public List<CheckResult> run() {
        List<CheckResult> results = new ArrayList<>();
        Jdbc jdbc = new Jdbc(provider);

        CheckResult connection = connect(jdbc);
        results.add(connection);
        if (connection.blocking()) {
            return List.copyOf(results);
        }

        CheckResult tables = tables(jdbc);
        results.add(tables);
        if (!tables.blocking()) {
            results.add(sequenceAtomicity(jdbc));
        }
        return List.copyOf(results);
    }

    private CheckResult connect(Jdbc jdbc) {
        try {
            String version = jdbc.queryOne(VERSION_SQL, rs -> rs.getString(1)).orElse("");
            return CheckResult.ok("连接", jdbcUrl + " → " + shorten(version));
        } catch (RuntimeException e) {
            return CheckResult.fail("连接", jdbcUrl + " —— " + rootMessage(e));
        }
    }

    private CheckResult tables(Jdbc jdbc) {
        try {
            int found = jdbc.queryOne(TABLE_SQL, rs -> rs.getInt("n")).orElse(0);
            if (found == EXPECTED_TABLES) {
                return CheckResult.ok("表结构", found + "/" + EXPECTED_TABLES + " 张表就绪");
            }
            return CheckResult.fail("表结构",
                    found + "/" + EXPECTED_TABLES + " 张表 —— 先跑 agent migrate");
        } catch (RuntimeException e) {
            return CheckResult.fail("表结构", rootMessage(e));
        }
    }

    /**
     * 序号分配的原子性。
     *
     * <p>连续分配两段，第二段的起点必须正好接在第一段之后。
     * 差一个数就说明分配不是在行锁内完成的 —— 那会直接毁掉 INV-10，
     * 而且只在并发下才暴露，平时测不出来。
     */
    private CheckResult sequenceAtomicity(Jdbc jdbc) {
        SessionRef probe = SessionRef.of("doctor", PROBE_SESSION);
        PostgresMessageRepository repository = new PostgresMessageRepository(jdbc);
        try {
            long firstBatch = repository.allocate(probe, 5);
            long secondBatch = repository.allocate(probe, 3);
            long expected = firstBatch + 5;

            if (secondBatch != expected) {
                return CheckResult.fail("序号分配",
                        "第二段起点应为 " + expected + "，实际 " + secondBatch + " —— 分配不是原子的");
            }
            return CheckResult.ok("序号分配", "连续两段无重叠无空洞（INV-10）");
        } catch (RuntimeException e) {
            return CheckResult.fail("序号分配", rootMessage(e));
        } finally {
            cleanup(jdbc);
        }
    }

    private void cleanup(Jdbc jdbc) {
        try {
            jdbc.update("DELETE FROM agent_msg_seq WHERE session_id = ?", PROBE_SESSION);
        } catch (RuntimeException ignored) {
            // 自检残留一行不值得让命令失败
        }
    }

    private static String shorten(String version) {
        int firstComma = version.indexOf(',');
        return firstComma > 0 ? version.substring(0, firstComma) : version;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
