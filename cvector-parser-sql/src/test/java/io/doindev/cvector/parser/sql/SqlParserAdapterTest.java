package io.doindev.cvector.parser.sql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlParserAdapterTest {

    @Test
    void emitsTableAndColumnsFromCreateTable(@TempDir Path root) throws Exception {
        Path file = root.resolve("schema.sql");
        Files.writeString(file, """
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    email VARCHAR(255),
                    created_at TIMESTAMP
                );
                """);
        List<GraphEvent> events = parse(root, file);

        assertThat(events).extracting(e -> e instanceof GraphEvent.NodeUpsert u ? u.key().label() : null)
                .contains("Table", "Column");
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.NodeUpsert u && "Column".equals(u.key().label()))
                .hasSize(3);
    }

    @Test
    void emitsReadsAndWritesFromDml(@TempDir Path root) throws Exception {
        Path file = root.resolve("ops.sql");
        Files.writeString(file, """
                SELECT id, email FROM users WHERE id = 42;
                INSERT INTO audit_log (action) VALUES ('login');
                UPDATE users SET email = 'x' WHERE id = 1;
                DELETE FROM audit_log WHERE id < 100;
                """);
        List<GraphEvent> events = parse(root, file);

        assertThat(events).filteredOn(e -> e instanceof GraphEvent.EdgeUpsert eu && "READS_TABLE".equals(eu.type()))
                .extracting(e -> ((GraphEvent.EdgeUpsert) e).to().fqName())
                .contains("users");
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.EdgeUpsert eu && "WRITES_TABLE".equals(eu.type()))
                .extracting(e -> ((GraphEvent.EdgeUpsert) e).to().fqName())
                .contains("audit_log", "users");
    }

    @Test
    void ignoresStatementsInLineAndBlockComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.sql");
        Files.writeString(file, """
                -- CREATE TABLE commented_line_table (id INT);
                -- INSERT INTO commented_line_audit (action) VALUES ('x');
                /* CREATE TABLE commented_block_table (id INT, name VARCHAR(100));
                   SELECT * FROM commented_block_audit; */
                CREATE TABLE real_users (id INT, email VARCHAR(255));
                INSERT INTO real_audit (action) VALUES ('login');
                """);
        List<GraphEvent> events = parse(root, file);

        List<String> tables = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Table".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(tables).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(tables).contains("real_users");

        List<String> writeTargets = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "WRITES_TABLE".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(writeTargets).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(writeTargets).contains("real_audit");
    }

    @Test
    void ignoresStatementsInsideStringLiterals(@TempDir Path root) throws Exception {
        Path file = root.resolve("strings.sql");
        Files.writeString(file, """
                INSERT INTO real_log (msg) VALUES ('CREATE TABLE fake_string_table (id INT)');
                CREATE TABLE real_only (id INT);
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> tables = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Table".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(tables).noneMatch(s -> s.toLowerCase().contains("fake_string"));
        assertThat(tables).contains("real_only");
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new SqlParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
