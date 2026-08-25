package io.agentharness.store.schema;

import io.agentharness.store.StoreException;
import io.agentharness.store.jdbc.Jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 建表。全部 {@code IF NOT EXISTS}，可以反复执行。
 *
 * <p>不是通用迁移框架 —— 没有版本表、没有回滚。
 * 表结构真正开始演进时换 Flyway，那之前多一层框架只是负担。
 */
public final class SchemaMigrator {

    private static final String SCHEMA_RESOURCE = "/io/agentharness/store/schema/schema.sql";

    private final Jdbc jdbc;

    public SchemaMigrator(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 执行的语句条数 */
    public int migrate() {
        List<String> statements = parse(readSchema());
        for (String statement : statements) {
            jdbc.execute(statement);
        }
        return statements.size();
    }

    private static String readSchema() {
        try (InputStream in = SchemaMigrator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new StoreException("找不到表结构定义 " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("读取表结构定义失败", e);
        }
    }

    /**
     * 先去注释，再按分号切分。
     *
     * <p><b>顺序不能颠倒。</b>先切分的话，注释里出现的分号会把一条注释拦腰截断，
     * 后半截跟着下一条语句一起被送去执行 —— 报出来的错会指向一条看起来毫无问题的
     * CREATE TABLE，排查时很难想到问题出在上一行注释里。
     *
     * <p>schema.sql 里不允许出现函数体（{@code $$ ... $$}），否则按分号切依然会切错。
     */
    static List<String> parse(String sql) {
        List<String> statements = new ArrayList<>();
        for (String chunk : stripComments(sql).split(";")) {
            String cleaned = chunk.strip();
            if (!cleaned.isEmpty()) {
                statements.add(cleaned);
            }
        }
        return List.copyOf(statements);
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
