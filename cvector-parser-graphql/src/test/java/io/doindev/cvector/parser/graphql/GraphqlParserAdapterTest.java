package io.doindev.cvector.parser.graphql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphqlParserAdapterTest {

    @Test
    void emitsTypesAndFields(@TempDir Path root) throws Exception {
        Path file = root.resolve("schema.graphql");
        Files.writeString(file, """
                type User {
                    id: ID!
                    name: String
                }

                interface Node {
                    id: ID!
                }

                enum Role { ADMIN, USER }

                input UserInput {
                    name: String!
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "GraphqlType")).anyMatch(s -> s.endsWith("User"));
        assertThat(nodeFqNames(events, "GraphqlInterface")).anyMatch(s -> s.endsWith("Node"));
        assertThat(nodeFqNames(events, "GraphqlEnum")).anyMatch(s -> s.endsWith("Role"));
        assertThat(nodeFqNames(events, "GraphqlInput")).anyMatch(s -> s.endsWith("UserInput"));
        assertThat(nodeFqNames(events, "GraphqlField")).anyMatch(s -> s.endsWith("User.id"));
        assertThat(nodeFqNames(events, "GraphqlField")).anyMatch(s -> s.endsWith("User.name"));
    }

    private static List<String> nodeFqNames(List<GraphEvent> events, String label) {
        return events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && label.equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new GraphqlParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
