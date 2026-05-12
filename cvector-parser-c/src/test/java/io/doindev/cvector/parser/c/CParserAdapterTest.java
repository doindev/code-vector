package io.doindev.cvector.parser.c;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CParserAdapterTest {

    @Test
    void emitsFunctionsAndStructs(@TempDir Path root) throws Exception {
        Path file = root.resolve("main.c");
        Files.writeString(file, """
                #include <stdio.h>

                struct Point {
                    int x;
                    int y;
                };

                enum Color { RED, GREEN, BLUE };

                int add(int a, int b) {
                    return a + b;
                }

                int main(int argc, char** argv) {
                    return add(1, 2);
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Struct")).anyMatch(s -> s.endsWith("Point"));
        assertThat(nodeFqNames(events, "Enum")).anyMatch(s -> s.endsWith("Color"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("add"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("main"));
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
        new CParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
