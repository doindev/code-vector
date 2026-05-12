package io.doindev.cvector.parser.go;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoParserAdapterTest {

    @Test
    void emitsPackageFuncsStructsAndInterfaces(@TempDir Path root) throws Exception {
        Path file = root.resolve("real.go");
        Files.writeString(file, """
                package svc

                type User struct {
                    ID    uint64
                    Email string
                }

                type Greeter interface {
                    Greet() string
                }

                func NewUser(id uint64) *User {
                    return &User{ID: id}
                }

                func (u *User) Greet() string {
                    return "hello"
                }
                """);
        List<GraphEvent> events = parse(root, file);

        assertThat(nodeFqNames(events, "GoPackage")).contains("svc");
        assertThat(nodeFqNames(events, "Struct")).contains("svc.User");
        assertThat(nodeFqNames(events, "Interface")).contains("svc.Greeter");
        assertThat(nodeFqNames(events, "Method")).contains("svc.NewUser", "svc.User.Greet");
    }

    @Test
    void ignoresDeclarationsInLineAndBlockComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.go");
        Files.writeString(file, """
                package audit

                // func commentedLineFunc() {}
                // type CommentedLineStruct struct {}
                /* func commentedBlockFunc() {}
                   type CommentedBlockStruct struct {}
                */

                func RealFunc() int {
                    // func commentedInsideFunc() {}
                    return 1
                }

                type RealStruct struct {
                    Name string
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> methods = nodeFqNames(events, "Method");
        List<String> structs = nodeFqNames(events, "Struct");
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(structs).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(methods).contains("audit.RealFunc");
        assertThat(structs).contains("audit.RealStruct");
    }

    @Test
    void ignoresDeclarationsInStringAndRawLiterals(@TempDir Path root) throws Exception {
        Path file = root.resolve("strings.go");
        Files.writeString(file, """
                package strs

                func real() {
                    a := "func fakeStringFunc() {} type FakeStringStruct struct {}"
                    b := `func rawFakeFunc() {}
                          type RawFakeStruct struct {}`
                    _ = a
                    _ = b
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> methods = nodeFqNames(events, "Method");
        List<String> structs = nodeFqNames(events, "Struct");
        assertThat(methods).noneMatch(s -> s.toLowerCase().contains("fake"));
        assertThat(structs).noneMatch(s -> s.toLowerCase().contains("fake"));
        assertThat(methods).contains("strs.real");
    }

    @Test
    void parsesSingleAndGroupedImports(@TempDir Path root) throws Exception {
        Path file = root.resolve("imports.go");
        Files.writeString(file, """
                package main

                import "fmt"

                import (
                    "net/http"
                    "encoding/json"
                    log "github.com/sirupsen/logrus"
                    _ "github.com/lib/pq"
                )
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> imports = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "IMPORTS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(imports).contains("fmt", "net/http", "encoding/json",
                "github.com/sirupsen/logrus", "github.com/lib/pq");
    }

    @Test
    void detectsHttpRoutesAndAttachesToHandler(@TempDir Path root) throws Exception {
        Path file = root.resolve("routes.go");
        Files.writeString(file, """
                package api

                func registerRoutes() {
                    http.HandleFunc("/health", healthHandler)
                    r.GET("/users", listUsers)
                    router.POST("/users", createUser)
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> endpoints = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ApiEndpoint".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(endpoints)
                .anyMatch(s -> s.equals("ANY /health"))
                .anyMatch(s -> s.equals("GET /users"))
                .anyMatch(s -> s.equals("POST /users"));

        List<String> exposes = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "EXPOSES".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName())
                .toList();
        assertThat(exposes).contains("api.registerRoutes");
    }

    @Test
    void attachesMethodsToStructViaReceiver(@TempDir Path root) throws Exception {
        Path file = root.resolve("receivers.go");
        Files.writeString(file, """
                package svc

                type User struct { ID uint64 }

                func (u *User) ID64() uint64 { return u.ID }
                func (u User) IDVal() uint64 { return u.ID }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> contains = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "CONTAINS".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(contains).anyMatch(s -> s.equals("svc.User -> svc.User.ID64"));
        assertThat(contains).anyMatch(s -> s.equals("svc.User -> svc.User.IDVal"));
    }

    @Test
    void ignoresRoutesInsideCommentsAndStrings(@TempDir Path root) throws Exception {
        Path file = root.resolve("noroutes.go");
        Files.writeString(file, """
                package api

                func nope() {
                    // http.HandleFunc("/commented-route", h)
                    s := "router.GET(\\"/string-route\\", h)"
                    _ = s
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> endpoints = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ApiEndpoint".equals(u.key().label()))
                .map(e -> (String) ((GraphEvent.NodeUpsert) e).props().get("path"))
                .toList();
        assertThat(endpoints).noneMatch(p -> p.contains("commented-route") || p.contains("string-route"));
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
        new GoParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
