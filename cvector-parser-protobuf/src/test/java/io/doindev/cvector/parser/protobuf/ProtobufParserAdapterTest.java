package io.doindev.cvector.parser.protobuf;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtobufParserAdapterTest {

    @Test
    void emitsMessagesEnumsServicesAndRpcs(@TempDir Path root) throws Exception {
        Path file = root.resolve("users.proto");
        Files.writeString(file, """
                syntax = "proto3";

                package users;

                import "google/protobuf/empty.proto";

                message User {
                    string id = 1;
                    string email = 2;
                }

                enum Role {
                    ADMIN = 0;
                    USER = 1;
                }

                service UserService {
                    rpc GetUser(User) returns (User);
                    rpc ListUsers(User) returns (User);
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "ProtobufMessage")).anyMatch(s -> s.endsWith("User"));
        assertThat(nodeFqNames(events, "ProtobufEnum")).anyMatch(s -> s.endsWith("Role"));
        assertThat(nodeFqNames(events, "ProtobufService")).anyMatch(s -> s.endsWith("UserService"));
        assertThat(nodeFqNames(events, "ProtobufRpc")).anyMatch(s -> s.endsWith("UserService.GetUser"));
        assertThat(nodeFqNames(events, "Module")).contains("google/protobuf/empty.proto");
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
        new ProtobufParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
