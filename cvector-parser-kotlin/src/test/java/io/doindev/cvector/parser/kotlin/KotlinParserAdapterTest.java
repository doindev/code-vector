package io.doindev.cvector.parser.kotlin;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinParserAdapterTest {

    @Test
    void emitsClassFunctionAndImport(@TempDir Path root) throws Exception {
        Path file = root.resolve("Real.kt");
        Files.writeString(file, """
                import kotlinx.coroutines.Job

                class Greeter {
                    fun hello(): Int { return 1 }
                }

                fun realFunc(): Int = 2
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Class")).anyMatch(s -> s.endsWith("Greeter"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("hello"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("realFunc"));
        assertThat(nodeFqNames(events, "Module")).contains("kotlinx.coroutines.Job");
    }

    @Test
    void ignoresDeclarationsInComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("Audit.kt");
        Files.writeString(file, """
                // class CommentedLineClass
                /* class CommentedBlockClass {} */
                fun realFunc(): Int = 1
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Class")).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("realFunc"));
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
        new KotlinParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
