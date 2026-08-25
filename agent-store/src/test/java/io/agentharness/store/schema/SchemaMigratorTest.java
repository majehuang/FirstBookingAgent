package io.agentharness.store.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigratorTest {

    @Test
    @DisplayName("注释里的分号不能把语句切断 —— 先去注释再切分")
    void 注释中的分号不影响切分() {
        String sql = """
                -- 语句之间用 ";" 分隔，不要在这里写函数体
                CREATE TABLE a (id int);
                -- 又一段注释；里面也有分号
                CREATE TABLE b (id int);
                """;

        List<String> statements = SchemaMigrator.parse(sql);

        assertThat(statements).containsExactly(
                "CREATE TABLE a (id int)",
                "CREATE TABLE b (id int)");
    }

    @Test
    void 注释被剥离_语句本身完整保留() {
        String sql = """
                -- 消息表：真相源
                CREATE TABLE agent_message (
                    session_id varchar(128) NOT NULL
                );
                """;

        assertThat(SchemaMigrator.parse(sql)).singleElement()
                .satisfies(s -> assertThat(s).startsWith("CREATE TABLE agent_message"))
                .satisfies(s -> assertThat(s).doesNotContain("真相源"));
    }

    @Test
    void 末尾分号与空白不产生空语句() {
        assertThat(SchemaMigrator.parse("CREATE TABLE a (id int);\n\n  \n")).hasSize(1);
        assertThat(SchemaMigrator.parse("")).isEmpty();
        assertThat(SchemaMigrator.parse("-- 只有注释\n")).isEmpty();
    }

    @Test
    void 内置schema能被完整解析出五张表() {
        List<String> statements = SchemaMigrator.parse(readSchema());

        // 只允许 CREATE / ALTER：迁移器按分号切分，出现别的语句形态说明 schema.sql 被写坏了
        assertThat(statements).allSatisfy(s ->
                assertThat(s).matches("(?s)^(CREATE|ALTER)\\b.*"));
        assertThat(statements).filteredOn(s -> s.startsWith("CREATE TABLE")).hasSize(5);
        assertThat(statements).filteredOn(s -> s.startsWith("CREATE INDEX")).hasSize(5);
        // 已有库靠 ALTER 补齐列宽，这条不能丢
        assertThat(statements).anySatisfy(s ->
                assertThat(s).contains("ALTER COLUMN block_id TYPE varchar(128)"));
    }

    private static String readSchema() {
        try (var in = SchemaMigrator.class.getResourceAsStream(
                "/io/agentharness/store/schema/schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取内置 schema.sql 失败", e);
        }
    }
}
