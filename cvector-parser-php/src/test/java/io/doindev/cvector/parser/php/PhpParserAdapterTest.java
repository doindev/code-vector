package io.doindev.cvector.parser.php;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhpParserAdapterTest {

    @Test
    void emitsClassesAndFunctions(@TempDir Path root) throws Exception {
        Path file = root.resolve("UserController.php");
        Files.writeString(file, """
                <?php
                namespace App\\Http\\Controllers;

                use App\\Models\\User;

                class UserController {
                    public function index() {
                        return User::all();
                    }
                    public function show($id) {
                        return User::find($id);
                    }
                }

                function helper() { return 1; }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Class")).anyMatch(s -> s.endsWith("UserController"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("UserController.index"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("UserController.show"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("helper"));
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
        new PhpParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
