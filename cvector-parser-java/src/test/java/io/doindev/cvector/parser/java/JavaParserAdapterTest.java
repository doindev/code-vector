package io.doindev.cvector.parser.java;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserAdapterTest {

    @Test
    void emitsClassesAndMethodsFromRealCode(@TempDir Path root) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path file = src.resolve("Real.java");
        Files.writeString(file, """
                package demo;
                public class Real {
                    public void realMethod() { }
                }
                """);

        List<GraphEvent> events = parse(root, file);

        List<String> classes = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Class".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(classes).anyMatch(s -> s.endsWith("Real"));

        List<String> methods = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Method".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(methods).anyMatch(s -> s.contains("realMethod"));
    }

    @Test
    void ignoresDeclarationsInsideLineAndBlockComments(@TempDir Path root) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path file = src.resolve("CommentAudit.java");
        Files.writeString(file, """
                package demo;
                // class CommentedOutLineClass { public void shouldNotAppearLine() {} }
                // public class AnotherCommentedClass {}
                /* class CommentedOutBlockClass {
                       public void shouldNotAppearBlock() {}
                   }
                */
                /** Javadoc with fake decl: class JavadocFakeClass {} */
                public class Real {
                    public void realMethod() { }
                    // public void commentedInsideClass() {}
                    /* public void blockCommentedInside() {} */
                }
                """);

        List<GraphEvent> events = parse(root, file);

        List<String> classNames = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Class".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(classNames).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("javadocfake"));

        List<String> methodNames = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Method".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(methodNames).noneMatch(s -> s.toLowerCase().contains("shouldnotappear")
                || s.toLowerCase().contains("commentedinside")
                || s.toLowerCase().contains("blockcommented"));

        assertThat(classNames).anyMatch(s -> s.endsWith("Real"));
        assertThat(methodNames).anyMatch(s -> s.contains("realMethod"));
    }

    @Test
    void doesNotTreatStringLiteralsLookingLikeDeclarationsAsCode(@TempDir Path root) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path file = src.resolve("Strings.java");
        Files.writeString(file, """
                package demo;
                public class Strings {
                    public void run() {
                        String s = "public class FakeStringClass { void fakeStringMethod() {} }";
                        String t = "// class CommentedInStringClass {}";
                    }
                }
                """);

        List<GraphEvent> events = parse(root, file);
        List<String> classNames = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "Class".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(classNames).noneMatch(s -> s.toLowerCase().contains("fakestring")
                || s.toLowerCase().contains("commentedinstring"));
    }

    private static List<GraphEvent> parse(Path root, Path file) throws Exception {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        JavaParserAdapter adapter = new JavaParserAdapter();
        adapter.prepare(ctx);
        try (Stream<Path> ignored = Stream.empty()) {
            adapter.parse(file, ctx, events::add);
        }
        return events;
    }
}
