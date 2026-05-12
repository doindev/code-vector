package io.doindev.cvector.parser.javascript;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaScriptParserAdapterTest {

    @Test
    void emitsClassFunctionAndImport(@TempDir Path root) throws Exception {
        Path file = root.resolve("real.js");
        Files.writeString(file, """
                import express from 'express';

                export class Greeter {
                    hello() { return 1; }
                }

                function realFunc() { return 2; }
                """);

        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Class")).anyMatch(s -> s.endsWith("Greeter"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("realFunc"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("hello"));
        assertThat(nodeFqNames(events, "Module")).contains("express");
    }

    @Test
    void ignoresDeclarationsInComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.js");
        Files.writeString(file, """
                // class CommentedLineClass {}
                /* class CommentedBlockClass {} */
                function realFunc() { return 1; }
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
        new JavaScriptParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
