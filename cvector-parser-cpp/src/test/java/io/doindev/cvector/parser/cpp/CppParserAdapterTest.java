package io.doindev.cvector.parser.cpp;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CppParserAdapterTest {

    @Test
    void emitsClassAndFunctions(@TempDir Path root) throws Exception {
        Path file = root.resolve("main.cpp");
        Files.writeString(file, """
                class Point {
                public:
                    int x;
                    int y;
                };

                int add(int a, int b) {
                    return a + b;
                }

                int main() {
                    return add(1, 2);
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Class")).anyMatch(s -> s.endsWith("Point"));
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
        new CppParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
