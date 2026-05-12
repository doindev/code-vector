package io.doindev.cvector.embedded;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedKuzuTest {

    @Test
    void opensDatabaseAndAnswersRoundTripQuery(@TempDir Path tmp) throws IOException {
        Path db = tmp.resolve("graph.kuzu");
        try (EmbeddedKuzu k = new EmbeddedKuzu(db)) {
            assertThat(k.ping()).isTrue();
            assertThat(k.read("RETURN 42 AS x"))
                    .singleElement()
                    .extracting(row -> row.get("x"))
                    .isEqualTo(42L);
        }
    }

    @Test
    void schemaBootstrapDeclaresNodeAndEdgeTables(@TempDir Path tmp) throws IOException {
        Path db = tmp.resolve("graph.kuzu");
        try (EmbeddedKuzu k = new EmbeddedKuzu(db)) {
            new KuzuSchemaBootstrap(k).bootstrap();
            // Kuzu exposes `CALL SHOW_TABLES() RETURN *` to enumerate user tables.
            List<Map<String, Object>> tables = k.read("CALL SHOW_TABLES() RETURN *");
            assertThat(tables).isNotEmpty();
            assertThat(tables.stream().map(r -> String.valueOf(r.get("name"))).toList())
                    .contains("Node")
                    .anyMatch(s -> s.equalsIgnoreCase("CALLS"))
                    .anyMatch(s -> s.equalsIgnoreCase("CONTAINS"));
        }
    }

    @Test
    void writesAndReadsBackNodes(@TempDir Path tmp) throws IOException {
        Path db = tmp.resolve("graph.kuzu");
        try (EmbeddedKuzu k = new EmbeddedKuzu(db)) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'a1', projectId: 'p', label: 'Class', fqName: 'demo.Foo', name: 'Foo'})");
            k.write("CREATE (:Node {id: 'm1', projectId: 'p', label: 'Method', fqName: 'demo.Foo.bar()', name: 'bar', paramCount: 0})");
            k.write("""
                    MATCH (c:Node {id: 'a1'}), (m:Node {id: 'm1'})
                    CREATE (c)-[:CONTAINS]->(m)
                    """);
            List<Map<String, Object>> hits = k.read("""
                    MATCH (c:Node {label: 'Class'})-[:CONTAINS]->(m:Node {label: 'Method'})
                    RETURN c.fqName AS klass, m.fqName AS method
                    """);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0)).containsEntry("klass", "demo.Foo")
                    .containsEntry("method", "demo.Foo.bar()");
        }
    }

    @Test
    void parameterisedQueriesBindCorrectly(@TempDir Path tmp) throws IOException {
        Path db = tmp.resolve("graph.kuzu");
        try (EmbeddedKuzu k = new EmbeddedKuzu(db)) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: $id, projectId: 'p', label: 'File', name: $name, path: $name})",
                    Map.of("id", "f1", "name", "main.java"));
            List<Map<String, Object>> rows = k.read(
                    "MATCH (n:Node {id: $id}) RETURN n.name AS name",
                    Map.of("id", "f1"));
            assertThat(rows).singleElement()
                    .extracting(r -> r.get("name"))
                    .isEqualTo("main.java");
        }
    }
}
