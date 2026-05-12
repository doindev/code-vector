package io.doindev.cvector.parser.style;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StylesheetParserAdapterTest {

    @Test
    void cssExtractsClassesAndCustomProperties(@TempDir Path root) throws Exception {
        Path file = root.resolve("site.css");
        Files.writeString(file, """
                :root { --real-token: blue; }
                .real-css-class { color: var(--real-token); }
                """);

        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "CssClass")).contains("real-css-class");
        assertThat(nodeFqNames(events, "DesignToken")).contains("--real-token");
    }

    @Test
    void cssIgnoresBlockComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.css");
        Files.writeString(file, """
                /* .commented-class { color: red; } */
                /* :root { --commented-token: red; } */
                .real-css-class { color: blue; }
                :root { --real-token: blue; }
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "CssClass");
        List<String> tokens = nodeFqNames(events, "DesignToken");

        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(tokens).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(classes).contains("real-css-class");
        assertThat(tokens).contains("--real-token");
    }

    @Test
    void scssIgnoresLineAndBlockComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("theme.scss");
        Files.writeString(file, """
                // $commented-line-var: red;
                // .commented-line-class { color: red; }
                /* $commented-block-var: red;
                   .commented-block-class { color: red; } */
                $real-scss-var: green;
                .real-scss-class { color: $real-scss-var; }
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "CssClass");
        List<String> tokens = nodeFqNames(events, "DesignToken");

        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(tokens).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(classes).contains("real-scss-class");
        assertThat(tokens).anyMatch(s -> s.endsWith("$real-scss-var"));
    }

    @Test
    void doesNotConsumeSelectorsInsideQuotedStrings(@TempDir Path root) throws Exception {
        Path file = root.resolve("strings.scss");
        Files.writeString(file, """
                $real-scss-var: "string with .fake-class-in-string inside";
                .real-scss-class { content: ".another-fake-in-content"; }
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "CssClass");
        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("fake"));
        assertThat(classes).contains("real-scss-class");
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
        new StylesheetParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
