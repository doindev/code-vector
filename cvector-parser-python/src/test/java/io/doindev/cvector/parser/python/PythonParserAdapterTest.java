package io.doindev.cvector.parser.python;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PythonParserAdapterTest {

    @Test
    void emitsClassesAndFunctionsFromRealCode(@TempDir Path root) throws Exception {
        Path file = root.resolve("real.py");
        Files.writeString(file, """
                def real_func():
                    return 1

                class RealPyClass:
                    def real_method(self):
                        return 2
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");
        assertThat(classes).anyMatch(s -> s.endsWith("RealPyClass"));
        assertThat(methods).anyMatch(s -> s.endsWith("real_func"));
        assertThat(methods).anyMatch(s -> s.endsWith("real_method"));
    }

    @Test
    void ignoresDeclarationsInLineCommentsAndDocstrings(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.py");
        Files.writeString(file, """
                # def commented_line_func(): pass
                # class CommentedLineClass: pass

                \"\"\"
                def looks_like_func_in_docstring(): pass
                class LooksLikeClassInDocstring: pass
                \"\"\"

                def real_py_func():
                    \"\"\"
                    Example:
                        def nested_in_docstring(): pass
                        class NestedClassInDocstring: pass
                    \"\"\"
                    return 1

                class RealPyClass:
                    def real_method(self):
                        # def commented_in_method(): pass
                        return 2
                """);

        List<GraphEvent> events = parse(root, file);

        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");

        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("lookslike")
                || s.toLowerCase().contains("looks_like")
                || s.toLowerCase().contains("nested"));
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("looks_like")
                || s.toLowerCase().contains("nested_in"));

        assertThat(classes).anyMatch(s -> s.endsWith("RealPyClass"));
        assertThat(methods).anyMatch(s -> s.endsWith("real_py_func"));
        assertThat(methods).anyMatch(s -> s.endsWith("real_method"));
    }

    @Test
    void ignoresDeclarationsInSingleQuotedStrings(@TempDir Path root) throws Exception {
        Path file = root.resolve("strings.py");
        Files.writeString(file, """
                x = "def fake_string_func(): pass"
                y = 'class FakeStringClass: pass'

                def real_string_func():
                    return x
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");

        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("fakestring"));
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("fake_string"));
        assertThat(methods).anyMatch(s -> s.endsWith("real_string_func"));
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
        new PythonParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
