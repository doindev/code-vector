package io.doindev.cvector.parser.rust;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RustParserAdapterTest {

    @Test
    void emitsFunctionsStructsEnumsAndTraits(@TempDir Path root) throws Exception {
        Path file = root.resolve("lib.rs");
        Files.writeString(file, """
                pub struct User { pub id: u64, pub email: String }

                pub enum Status { Active, Inactive }

                pub trait Service {
                    fn run(&self);
                }

                pub fn real_fn() -> u32 { 1 }

                async fn async_handler() {}
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Struct")).contains("User");
        assertThat(nodeFqNames(events, "Enum")).contains("Status");
        assertThat(nodeFqNames(events, "Trait")).contains("Service");
        assertThat(nodeFqNames(events, "Method")).contains("real_fn", "async_handler");
    }

    @Test
    void emitsImplBlockMethodsAsTypeScoped(@TempDir Path root) throws Exception {
        Path file = root.resolve("impls.rs");
        Files.writeString(file, """
                pub struct User { pub id: u64 }

                impl User {
                    pub fn new(id: u64) -> Self { User { id } }
                    pub fn id(&self) -> u64 { self.id }
                }

                pub trait Greeter {
                    fn greet(&self) -> String;
                }

                impl Greeter for User {
                    fn greet(&self) -> String { String::from("hi") }
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> methods = nodeFqNames(events, "Method");
        assertThat(methods).contains("User::new", "User::id", "User::greet");

        List<String> implements_ = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "IMPLEMENTS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(implements_).contains("User -> Greeter");
    }

    @Test
    void ignoresDeclarationsInLineNestedBlockAndDocComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.rs");
        Files.writeString(file, """
                // fn commented_line_fn() {}
                // struct CommentedLineStruct;
                /* fn commented_block_fn() {}
                   /* nested fn commented_nested_fn() {} */
                   struct CommentedBlockStruct;
                */
                /// fn doc_comment_fake_fn() {}
                //! fn inner_doc_fake_fn() {}

                pub fn real_fn() -> u32 {
                    // fn commented_inside_fn() {}
                    1
                }

                pub struct RealStruct;
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> methods = nodeFqNames(events, "Method");
        List<String> structs = nodeFqNames(events, "Struct");
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("doc_comment_fake")
                || s.toLowerCase().contains("inner_doc"));
        assertThat(structs).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(methods).contains("real_fn");
        assertThat(structs).contains("RealStruct");
    }

    @Test
    void ignoresDeclarationsInsideStringAndRawStringLiterals(@TempDir Path root) throws Exception {
        Path file = root.resolve("strings.rs");
        Files.writeString(file, """
                pub fn real() {
                    let a = "fn fake_string_fn() {} struct FakeStringStruct;";
                    let b = r#"fn raw_fake_fn() {} struct RawFakeStruct;"#;
                    let c = b"fn byte_fake_fn() {}";
                    println!("just a literal: struct InMacroFake;");
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> methods = nodeFqNames(events, "Method");
        List<String> structs = nodeFqNames(events, "Struct");
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("fake"));
        assertThat(structs).noneMatch(s -> s.toLowerCase().contains("fake"));
        assertThat(methods).contains("real");
    }

    @Test
    void emitsUseImportsIncludingGroupedAndAliased(@TempDir Path root) throws Exception {
        Path file = root.resolve("uses.rs");
        Files.writeString(file, """
                use std::collections::HashMap;
                use serde::{Serialize, Deserialize as De};
                pub use crate::utils::helper;
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> imports = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "IMPORTS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(imports).contains("std::collections::HashMap",
                "serde::Serialize", "serde::Deserialize",
                "crate::utils::helper");
    }

    @Test
    void capturesInlineModulesAndQualifiesContents(@TempDir Path root) throws Exception {
        Path file = root.resolve("mods.rs");
        Files.writeString(file, """
                mod inner {
                    pub struct InnerType;
                    pub fn inner_fn() {}
                }

                pub fn outer_fn() {}
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "RustModule")).contains("inner");
        assertThat(nodeFqNames(events, "Struct")).contains("inner::InnerType");
        assertThat(nodeFqNames(events, "Method")).contains("inner::inner_fn", "outer_fn");
    }

    @Test
    void supportsTupleStructAndUnitStruct(@TempDir Path root) throws Exception {
        Path file = root.resolve("tuples.rs");
        Files.writeString(file, """
                pub struct Wrapper(pub u32, pub String);
                pub struct Unit;
                pub struct Normal { pub x: u32 }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Struct")).contains("Wrapper", "Unit", "Normal");
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
        new RustParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
