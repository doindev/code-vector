package io.doindev.cvector.parser.csharp;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CSharpParserAdapterTest {

    @Test
    void emitsNamespaceClassAndMethods(@TempDir Path root) throws Exception {
        Path file = root.resolve("Real.cs");
        Files.writeString(file, """
                namespace Demo.Real
                {
                    public class RealClass
                    {
                        public int RealMethod()
                        {
                            return 1;
                        }

                        private void Helper() { }
                    }
                }
                """);
        List<GraphEvent> events = parse(root, file);

        assertThat(nodeFqNames(events, "Namespace")).contains("Demo.Real");
        assertThat(nodeFqNames(events, "Class")).contains("Demo.Real.RealClass");
        assertThat(nodeFqNames(events, "Method"))
                .contains("Demo.Real.RealClass.RealMethod", "Demo.Real.RealClass.Helper");
    }

    @Test
    void supportsFileScopedNamespace(@TempDir Path root) throws Exception {
        Path file = root.resolve("FileScoped.cs");
        Files.writeString(file, """
                namespace Demo.FileScoped;

                public class Thing
                {
                    public string Name() => "x";
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Class")).contains("Demo.FileScoped.Thing");
        assertThat(nodeFqNames(events, "Method")).contains("Demo.FileScoped.Thing.Name");
    }

    @Test
    void ignoresDeclarationsInsideLineAndBlockComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("Audit.cs");
        Files.writeString(file, """
                namespace Demo.Audit
                {
                    // public class CommentedLineClass { public void ShouldNotAppear() {} }
                    /* public class CommentedBlockClass
                       {
                           public void ShouldNotAppearBlock() {}
                       } */
                    /// <summary>Doc says: class XmlDocFakeClass {}</summary>
                    public class Real
                    {
                        public void RealMethod()
                        {
                            // public void CommentedInsideMethod() {}
                        }
                        // public void CommentedInsideClass() {}
                    }
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");

        assertThat(classes).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("xmldocfake"));
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("shouldnotappear")
                || s.toLowerCase().contains("commentedinside"));

        assertThat(classes).contains("Demo.Audit.Real");
        assertThat(methods).contains("Demo.Audit.Real.RealMethod");
    }

    @Test
    void ignoresDeclarationsInStringLiterals(@TempDir Path root) throws Exception {
        Path file = root.resolve("Strings.cs");
        Files.writeString(file, """
                namespace Demo.Strings;

                public class Real
                {
                    public void Run()
                    {
                        var a = "public class FakeStringClass { public void FakeStringMethod() {} }";
                        var b = @"public class VerbatimFake { public void VerbatimFakeMethod() {} }";
                        var c = $"interpolation: class InterpFakeClass {{}}";
                    }
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> classes = nodeFqNames(events, "Class");
        List<String> methods = nodeFqNames(events, "Method");
        assertThat(classes).noneMatch(s -> {
            String low = s.toLowerCase();
            return low.contains("fakestring") || low.contains("verbatimfake") || low.contains("interpfake");
        });
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("fake"));
        assertThat(classes).contains("Demo.Strings.Real");
        assertThat(methods).contains("Demo.Strings.Real.Run");
    }

    @Test
    void emitsUsingsAsImports(@TempDir Path root) throws Exception {
        Path file = root.resolve("Imports.cs");
        Files.writeString(file, """
                using System;
                using System.Collections.Generic;
                using static System.Math;
                using Json = System.Text.Json;

                namespace Demo.Imports;

                public class Holder {}
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> imports = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "IMPORTS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(imports).contains("System", "System.Collections.Generic", "System.Math", "System.Text.Json");
    }

    @Test
    void detectsAspNetHttpAttributesAndRoutes(@TempDir Path root) throws Exception {
        Path file = root.resolve("UsersController.cs");
        Files.writeString(file, """
                namespace Demo.Api
                {
                    [Route("api/users")]
                    public class UsersController
                    {
                        [HttpGet]
                        public string GetAll() => "all";

                        [HttpGet("{id}")]
                        public string GetOne(int id) => id.ToString();

                        [HttpPost]
                        public void Create() { }
                    }
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> endpoints = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ApiEndpoint".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(endpoints)
                .anyMatch(s -> s.equals("GET /api/users"))
                .anyMatch(s -> s.equals("GET /api/users/{id}"))
                .anyMatch(s -> s.equals("POST /api/users"));
    }

    @Test
    void detectsRecordAndInterfaceAndStruct(@TempDir Path root) throws Exception {
        Path file = root.resolve("Types.cs");
        Files.writeString(file, """
                namespace Demo.Types;

                public interface IThing
                {
                    void Do();
                }

                public struct Point
                {
                    public int X;
                }

                public record Person(string Name);
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Interface")).contains("Demo.Types.IThing");
        assertThat(nodeFqNames(events, "Struct")).contains("Demo.Types.Point");
        assertThat(nodeFqNames(events, "Record")).contains("Demo.Types.Person");
    }

    @Test
    void emitsInheritanceEdges(@TempDir Path root) throws Exception {
        Path file = root.resolve("Inherit.cs");
        Files.writeString(file, """
                namespace Demo.Inherit;

                public interface IService { }
                public class Base { }
                public class Real : Base, IService { }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> extends_ = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "EXTENDS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        List<String> implements_ = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "IMPLEMENTS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(extends_).contains("Demo.Inherit.Real -> Base");
        assertThat(implements_).contains("Demo.Inherit.Real -> IService");
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
        new CSharpParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
