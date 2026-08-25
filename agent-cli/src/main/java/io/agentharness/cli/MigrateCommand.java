package io.agentharness.cli;

import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.schema.DatabaseBootstrap;
import io.agentharness.store.schema.SchemaMigrator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/** 建表。幂等，可以反复执行。 */
@Command(name = "migrate", mixinStandardHelpOptions = true,
        description = "在 PostgreSQL 上创建表结构（幂等）")
public final class MigrateCommand implements Callable<Integer> {

    @Option(names = "--create-database",
            description = "目标库不存在时先建库（连维护库 postgres 执行 CREATE DATABASE）")
    private boolean createDatabase;

    @Mixin
    private DbOptions db = new DbOptions();

    @Override
    public Integer call() {
        try {
            if (createDatabase) {
                boolean created = new DatabaseBootstrap(db.resolveJdbcUrl(), db.resolveUser(), db.resolvePassword())
                        .ensureDatabase();
                System.out.println(created ? "✓ 已新建数据库" : "· 数据库已存在，跳过建库");
            }
        } catch (RuntimeException e) {
            System.err.println("✗ 建库失败：" + rootMessage(e));
            return 2;
        }

        try (DataSourceProvider provider = db.openProvider()) {
            int statements = new SchemaMigrator(new Jdbc(provider)).migrate();
            System.out.println("✓ 表结构已就绪（执行 " + statements + " 条语句）  " + db.resolveJdbcUrl());
            return 0;
        } catch (RuntimeException e) {
            System.err.println("✗ 建表失败：" + rootMessage(e));
            return 2;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
