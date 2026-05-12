package io.doindev.cvector.parser.ts;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptParserAdapterTest {

    @Test
    void emitsClassesMethodsAndFunctionsFromRealCode(@TempDir Path root) throws Exception {
        Path file = root.resolve("real.ts");
        Files.writeString(file, """
                import express from 'express';

                export class RealTsClass {
                    realTsMethod() { return 1; }
                }

                function realTsFunction() { return 2; }
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");
        assertThat(classes).anyMatch(s -> s.endsWith("RealTsClass"));
        assertThat(methods).anyMatch(s -> s.endsWith("realTsFunction"));
        assertThat(methods).anyMatch(s -> s.endsWith("realTsMethod"));
    }

    @Test
    void ignoresDeclarationsAndRoutesInLineAndBlockComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.ts");
        Files.writeString(file, """
                // class CommentedLineClass {}
                // function commentedLineFunc() {}
                /* class CommentedBlockClass {
                     blockMethod() {}
                   }
                   function commentedBlockFunc() {}
                */
                /** Doc says: class JsDocFakeClass {} */
                import express from 'express';
                // import secretImport from 'should-not-appear';

                const app = express();
                // app.get('/fake-commented-line', () => {});
                /* app.post('/fake-commented-block', () => {}); */
                app.get('/real-route', () => {});

                export class RealTsClass {
                    realTsMethod() { return 1; }
                    // commentedInsideClass() {}
                    /* blockCommentedInsideClass() {} */
                }

                function realTsFunction() { return 2; }
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");
        List<String> imports = nodeFqNames(events, "Module");
        List<String> endpoints = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ApiEndpoint".equals(u.key().label()))
                .map(e -> (String) ((GraphEvent.NodeUpsert) e).props().get("path"))
                .toList();

        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("jsdocfake"));
        assertThat(methods).noneMatch(s -> {
            String low = s.toLowerCase();
            return low.contains("commentedline")
                    || low.contains("commentedblock")
                    || low.contains("commentedinside")
                    || low.contains("blockcommented");
        });
        assertThat(imports).doesNotContain("should-not-appear");
        assertThat(endpoints).noneMatch(p -> p.contains("fake-commented"));

        assertThat(classes).anyMatch(s -> s.endsWith("RealTsClass"));
        assertThat(methods).anyMatch(s -> s.endsWith("realTsFunction"));
        assertThat(methods).anyMatch(s -> s.endsWith("realTsMethod"));
        assertThat(endpoints).anyMatch(p -> p.equals("/real-route"));
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
        new TypeScriptParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
