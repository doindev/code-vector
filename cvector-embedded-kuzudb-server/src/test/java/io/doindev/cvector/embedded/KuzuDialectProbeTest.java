package io.doindev.cvector.embedded;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes what Kuzu's Cypher dialect supports. These are not contract tests — they document what
 * works so the production ingestor can take the right path. Each test prints a one-line summary;
 * none of them are expected to fail, but some assertions may be relaxed because the goal is
 * discovery.
 */
class KuzuDialectProbeTest {

    @Test
    void mergeWithPropertyMapShortcutWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("MERGE (n:Node {id: 'a'}) SET n.name = 'Alpha', n.label = 'X'");
            var rows = k.read("MATCH (n:Node {id: 'a'}) RETURN n.name AS name, n.label AS label");
            assertThat(rows).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("name", "Alpha").containsEntry("label", "X");
            });
        }
    }

    @Test
    void perRowMergeWithSimpleParamsWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            // Kuzu's parameter binding from our generic toValue() handles primitives + strings but
            // can't currently pass a List<Map> as a LIST parameter through Value(T). KuzuIngestor
            // works around this by issuing per-row writes instead of a single UNWIND $rows batch.
            k.write("MERGE (n:Node {id: $id}) SET n.projectId = $pid, n.label = $label, n.name = $name",
                    Map.of("id", "n1", "pid", "p", "label", "File", "name", "f1.java"));
            k.write("MERGE (n:Node {id: $id}) SET n.projectId = $pid, n.label = $label, n.name = $name",
                    Map.of("id", "n2", "pid", "p", "label", "Class", "name", "Foo"));
            var rows = k.read("MATCH (n:Node) WHERE n.projectId = 'p' RETURN count(n) AS c");
            assertThat(rows.get(0).get("c")).isEqualTo(2L);
        }
    }

    @Test
    void edgeMergeFromIdToIdWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'a', label: 'Class', projectId: 'p', name: 'A'})");
            k.write("CREATE (:Node {id: 'b', label: 'Method', projectId: 'p', name: 'b'})");
            k.write("MATCH (a:Node {id: 'a'}), (b:Node {id: 'b'}) MERGE (a)-[:CONTAINS]->(b)");
            var rows = k.read("MATCH (a:Node)-[:CONTAINS]->(b:Node) RETURN a.id AS from, b.id AS to");
            assertThat(rows).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("from", "a").containsEntry("to", "b");
            });
        }
    }

    @Test
    void optionalMatchAndCoalesceWork(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'x', label: 'File', projectId: 'p', name: 'x.java', path: 'src/x.java'})");
            var rows = k.read("""
                    MATCH (f:Node {id: 'x'})
                    OPTIONAL MATCH (f)-[:CONTAINS]->(c:Node)
                    RETURN f.path AS path, coalesce(c.name, '<none>') AS child
                    """);
            assertThat(rows).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("path", "src/x.java")
                        .containsEntry("child", "<none>");
            });
        }
    }

    @Test
    void detachDeleteByPredicateWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'k1', label: 'Method', projectId: 'p', name: 'k1'})");
            k.write("CREATE (:Node {id: 'k2', label: 'Method', projectId: 'p', name: 'k2'})");
            // Kuzu uses DETACH DELETE the same way Neo4j does.
            k.write("MATCH (n:Node {id: 'k1'}) DETACH DELETE n");
            var rows = k.read("MATCH (n:Node) WHERE n.projectId = 'p' RETURN n.id AS id ORDER BY id");
            assertThat(rows).singleElement()
                    .extracting(r -> r.get("id"))
                    .isEqualTo("k2");
        }
    }

    @Test
    void timestampBindingFromStringWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            // Kuzu's TIMESTAMP column accepts a string literal via the timestamp() cast function.
            k.write("MERGE (n:Node {id: 'ts1'}) SET n.label = 'File', n.projectId = 'p', n.lastIngestedAt = timestamp($ts)",
                    Map.of("ts", "2025-05-12T01:23:45"));
            var rows = k.read("MATCH (n:Node {id: 'ts1'}) RETURN n.lastIngestedAt AS ts");
            assertThat(rows).hasSize(1);
            // We don't pin the exact return type — just confirm a non-null value came back.
            assertThat(rows.get(0).get("ts")).isNotNull();
        }
    }

    @Test
    void startsWithAndStringContainsWork(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'r1', label: 'Method', projectId: 'p', fqName: 'unresolved.foo:2'})");
            k.write("CREATE (:Node {id: 'r2', label: 'Method', projectId: 'p', fqName: 'com.example.Foo.bar'})");
            var rows = k.read(
                    "MATCH (n:Node) WHERE n.fqName STARTS WITH 'unresolved.' AND n.fqName CONTAINS ':' "
                            + "RETURN n.id AS id");
            assertThat(rows).singleElement()
                    .extracting(r -> r.get("id"))
                    .isEqualTo("r1");
        }
    }

    @Test
    void variableLengthPathTraversalWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'a', label: 'Method', projectId: 'p', name: 'a'})");
            k.write("CREATE (:Node {id: 'b', label: 'Method', projectId: 'p', name: 'b'})");
            k.write("CREATE (:Node {id: 'c', label: 'Method', projectId: 'p', name: 'c'})");
            k.write("MATCH (a:Node {id: 'a'}), (b:Node {id: 'b'}) MERGE (a)-[:CALLS]->(b)");
            k.write("MATCH (b:Node {id: 'b'}), (c:Node {id: 'c'}) MERGE (b)-[:CALLS]->(c)");
            // Kuzu's `*1..N` traversal — used by impact-style queries.
            var rows = k.read(
                    "MATCH (start:Node {id: 'a'})-[:CALLS*1..3]->(reached:Node) "
                            + "RETURN DISTINCT reached.id AS id ORDER BY id");
            assertThat(rows).extracting(r -> r.get("id")).containsExactly("b", "c");
        }
    }

    @Test
    void collectAggregationWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'h', label: 'Method', projectId: 'p', name: 'handle'})");
            k.write("CREATE (:Node {id: 'a', label: 'Method', projectId: 'p', name: 'a'})");
            k.write("CREATE (:Node {id: 'b', label: 'Method', projectId: 'p', name: 'b'})");
            k.write("MATCH (h:Node {id: 'h'}), (a:Node {id: 'a'}) MERGE (h)-[:CALLS]->(a)");
            k.write("MATCH (h:Node {id: 'h'}), (b:Node {id: 'b'}) MERGE (h)-[:CALLS]->(b)");
            // collect()'s aggregation pattern feeds cv_flows's "reaches" lists.
            var rows = k.read(
                    "MATCH (h:Node {id: 'h'})-[:CALLS]->(c:Node) "
                            + "RETURN h.name AS handler, collect(DISTINCT c.name) AS reaches");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("reaches").toString()).contains("a").contains("b");
        }
    }

    @Test
    void regexpMatchesFunctionWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: '1', label: 'Method', projectId: 'p', fqName: 'org.springframework.web.RestTemplate.exchange'})");
            k.write("CREATE (:Node {id: '2', label: 'Method', projectId: 'p', fqName: 'com.foo.Bar.baz'})");
            // Kuzu's regexp_matches(string, pattern) replaces Neo4j's =~ — used by cv_service_links.
            var rows = k.read(
                    "MATCH (n:Node) WHERE regexp_matches(n.fqName, '.*(RestTemplate|WebClient|HttpClient).*') "
                            + "RETURN n.id AS id");
            assertThat(rows).singleElement()
                    .extracting(r -> r.get("id"))
                    .isEqualTo("1");
        }
    }

    @Test
    void containsForSubstringFilterWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: '1', label: 'Method', projectId: 'p', name: 'fetchUser', fqName: 'com.x.fetchUser'})");
            k.write("CREATE (:Node {id: '2', label: 'Method', projectId: 'p', name: 'fetchOrder', fqName: 'com.x.fetchOrder'})");
            k.write("CREATE (:Node {id: '3', label: 'Method', projectId: 'p', name: 'saveUser', fqName: 'com.x.saveUser'})");
            // CONTAINS is standard Cypher; Kuzu supports it. Case-insensitive matching is via lower().
            var rows = k.read(
                    "MATCH (n:Node) WHERE lower(n.name) CONTAINS 'fetch' RETURN n.id AS id ORDER BY id");
            assertThat(rows).extracting(r -> r.get("id")).containsExactly("1", "2");
        }
    }

    @Test
    void copyFromCsvWorksForNodeTable(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            // Kuzu's COPY FROM requires the row to have exactly the same number of fields as the
            // table has columns, in declared order. Discover the column count dynamically so this
            // test doesn't drift when the schema gains/loses columns.
            int columnCount = k.read("CALL TABLE_INFO('Node') RETURN name").size();
            Path csv = tmp.resolve("nodes.csv");
            java.nio.file.Files.writeString(csv,
                    pad("a,p,Method,com.x.foo", columnCount) + "\n"
                            + pad("b,p,Method,com.x.bar", columnCount) + "\n");
            // On Windows the Cypher tokenizer treats backslashes as escape characters in string
            // literals, so paths must be forward-slashed before inlining.
            String cypherPath = csv.toString().replace('\\', '/');
            k.write("COPY Node FROM '" + cypherPath + "'");
            var rows = k.read("MATCH (n:Node) WHERE n.label = 'Method' RETURN n.id AS id ORDER BY id");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("id")).isEqualTo("a");
            assertThat(rows.get(1).get("id")).isEqualTo("b");
        }
    }

    /** Pad a comma-separated row out to {@code columnCount} fields with empty (NULL) values. */
    private static String pad(String leadingFields, int columnCount) {
        int present = leadingFields.split(",", -1).length;
        StringBuilder sb = new StringBuilder(leadingFields);
        for (int i = present; i < columnCount; i++) sb.append(',');
        return sb.toString();
    }

    @Test
    void variableLengthMultiRelTypeWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 't', label: 'Method', projectId: 'p', isTest: true, name: 't'})");
            k.write("CREATE (:Node {id: 'a', label: 'Method', projectId: 'p', name: 'a'})");
            k.write("CREATE (:Node {id: 'b', label: 'Method', projectId: 'p', name: 'b'})");
            k.write("MATCH (t:Node {id: 't'}), (a:Node {id: 'a'}) MERGE (t)-[:CALLS]->(a)");
            k.write("MATCH (a:Node {id: 'a'}), (b:Node {id: 'b'}) MERGE (a)-[:REFERENCES]->(b)");
            // Cypher's union edge type — `[:CALLS|REFERENCES*1..3]` — is what cv_test_impact wants.
            // If Kuzu doesn't support it the test fails and we fall back to the Java-BFS shape.
            var rows = k.read(
                    "MATCH (t:Node {id: 't'})-[:CALLS|REFERENCES*1..3]->(reached:Node) "
                            + "RETURN DISTINCT reached.id AS id ORDER BY id");
            assertThat(rows).extracting(r -> r.get("id")).containsExactly("a", "b");
        }
    }

    @Test
    void labelInListPredicateWorks(@TempDir Path tmp) throws IOException {
        try (EmbeddedKuzu k = new EmbeddedKuzu(tmp.resolve("db.kuzu"))) {
            new KuzuSchemaBootstrap(k).bootstrap();
            k.write("CREATE (:Node {id: 'm', label: 'Method', projectId: 'p'})");
            k.write("CREATE (:Node {id: 'c', label: 'Class', projectId: 'p'})");
            k.write("CREATE (:Node {id: 'pj', label: 'Project', projectId: 'p'})");
            // Kuzu supports list literals in IN predicates. This is the pattern cleanupStale uses
            // to target retired labels while keeping Project nodes around.
            var rows = k.read(
                    "MATCH (n:Node) WHERE n.label IN ['Method', 'Class'] RETURN n.id AS id ORDER BY id");
            assertThat(rows).hasSize(2);
        }
    }
}
