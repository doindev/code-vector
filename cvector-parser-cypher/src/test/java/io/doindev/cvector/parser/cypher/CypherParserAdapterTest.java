package io.doindev.cvector.parser.cypher;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CypherParserAdapterTest {

    @Test
    void parsesQueryAndEmitsFileNode(@TempDir Path root) throws Exception {
        Path file = root.resolve("query.cypher");
        Files.writeString(file, """
                MATCH (n:Person)-[:KNOWS]->(m:Person)
                WHERE n.name = 'Alice'
                RETURN m.name
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(events).anyMatch(e -> e instanceof GraphEvent.NodeUpsert u
                && "File".equals(u.key().label())
                && u.key().fqName().endsWith("query.cypher"));
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new CypherParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
