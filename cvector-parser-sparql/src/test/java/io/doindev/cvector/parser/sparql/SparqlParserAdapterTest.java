package io.doindev.cvector.parser.sparql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SparqlParserAdapterTest {

    @Test
    void parsesSelectQuery(@TempDir Path root) throws Exception {
        Path file = root.resolve("q.sparql");
        Files.writeString(file, """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?name WHERE { ?p foaf:name ?name }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(events).anyMatch(e -> e instanceof GraphEvent.NodeUpsert u
                && "SparqlQuery".equals(u.key().label()));
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new SparqlParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
