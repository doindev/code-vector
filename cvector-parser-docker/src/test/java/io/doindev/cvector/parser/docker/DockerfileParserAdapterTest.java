package io.doindev.cvector.parser.docker;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileParserAdapterTest {

    @Test
    void acceptsCommonDockerfileNames(@TempDir Path root) throws Exception {
        DockerfileParserAdapter parser = new DockerfileParserAdapter();
        assertThat(parser.accepts(root.resolve("Dockerfile"))).isTrue();
        assertThat(parser.accepts(root.resolve("dockerfile"))).isTrue();
        assertThat(parser.accepts(root.resolve("Containerfile"))).isTrue();
        assertThat(parser.accepts(root.resolve("Dockerfile.prod"))).isTrue();
        assertThat(parser.accepts(root.resolve("api.dockerfile"))).isTrue();
        assertThat(parser.accepts(root.resolve("README.md"))).isFalse();
    }

    @Test
    void parsesFromExposeEnvAndStage(@TempDir Path root) throws Exception {
        Path file = root.resolve("Dockerfile");
        Files.writeString(file, """
                FROM eclipse-temurin:21-jdk AS builder
                ENV APP_HOME=/opt/app
                WORKDIR /opt/app
                COPY . .
                RUN ./mvnw -q -DskipTests package
                EXPOSE 8080
                ENTRYPOINT ["java","-jar","app.jar"]
                """);

        List<GraphEvent> events = parse(root, file);

        assertThat(nodeFqNames(events, "ContainerImage")).contains("eclipse-temurin:21-jdk");
        assertThat(nodeFqNames(events, "ContainerStage")).anyMatch(s -> s.endsWith("::builder"));
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.NodeUpsert u && "ContainerPort".equals(u.key().label()))
                .extracting(e -> ((GraphEvent.NodeUpsert) e).props().get("port"))
                .contains(8080);
        assertThat(nodeFqNames(events, "EnvVar")).contains("APP_HOME");
        assertThat(nodeFqNames(events, "ContainerCommand"))
                .anyMatch(s -> s.endsWith("::entrypoint"));
    }

    @Test
    void multiStageEmitsTwoStagesAndLinksToBaseImages(@TempDir Path root) throws Exception {
        Path file = root.resolve("Dockerfile");
        Files.writeString(file, """
                FROM node:20 AS build
                WORKDIR /app
                COPY . .
                RUN npm ci && npm run build

                FROM nginx:1.27-alpine AS runtime
                COPY --from=build /app/dist /usr/share/nginx/html
                EXPOSE 80
                """);
        List<GraphEvent> events = parse(root, file);

        assertThat(nodeFqNames(events, "ContainerStage"))
                .anyMatch(s -> s.endsWith("::build"))
                .anyMatch(s -> s.endsWith("::runtime"));
        assertThat(nodeFqNames(events, "ContainerImage"))
                .contains("node:20", "nginx:1.27-alpine");

        List<String> dependsOn = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "DEPENDS_ON".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(dependsOn).anyMatch(s -> s.endsWith("::build -> node:20"));
        assertThat(dependsOn).anyMatch(s -> s.endsWith("::runtime -> nginx:1.27-alpine"));
    }

    @Test
    void ignoresHashCommentLines(@TempDir Path root) throws Exception {
        Path file = root.resolve("Dockerfile");
        Files.writeString(file, """
                # FROM commented-out:latest AS commented_stage
                # EXPOSE 9999
                # ENV FAKE_VAR=fake
                FROM alpine:3.20 AS real
                EXPOSE 8080
                ENV REAL_VAR=real
                """);
        List<GraphEvent> events = parse(root, file);

        assertThat(nodeFqNames(events, "ContainerImage")).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(nodeFqNames(events, "ContainerImage")).contains("alpine:3.20");
        assertThat(nodeFqNames(events, "ContainerStage")).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(nodeFqNames(events, "EnvVar")).doesNotContain("FAKE_VAR");
        assertThat(nodeFqNames(events, "EnvVar")).contains("REAL_VAR");

        List<Integer> ports = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ContainerPort".equals(u.key().label()))
                .map(e -> (Integer) ((GraphEvent.NodeUpsert) e).props().get("port"))
                .toList();
        assertThat(ports).doesNotContain(9999).contains(8080);
    }

    @Test
    void supportsBackslashContinuationLines(@TempDir Path root) throws Exception {
        Path file = root.resolve("Dockerfile");
        Files.writeString(file, """
                FROM alpine:3.20
                ENV ONE=1 \\
                    TWO=2 \\
                    THREE=3
                EXPOSE 80 443/tcp 53/udp
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> envs = nodeFqNames(events, "EnvVar");
        assertThat(envs).contains("ONE", "TWO", "THREE");

        List<Map<String, Object>> ports = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ContainerPort".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).props())
                .toList();
        assertThat(ports).extracting(p -> p.get("port")).contains(80, 443, 53);
        assertThat(ports).extracting(p -> p.get("protocol")).contains("tcp", "udp");
    }

    @Test
    void parsesImageWithDigestAndPlatform(@TempDir Path root) throws Exception {
        Path file = root.resolve("Dockerfile");
        Files.writeString(file, """
                FROM --platform=linux/amd64 alpine@sha256:abcdef1234567890 AS pinned
                """);
        List<GraphEvent> events = parse(root, file);
        GraphEvent.NodeUpsert img = (GraphEvent.NodeUpsert) events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ContainerImage".equals(u.key().label()))
                .findFirst().orElseThrow();
        assertThat(img.props()).containsEntry("repository", "alpine");
        assertThat((String) img.props().get("digest")).startsWith("sha256:");
    }

    @Test
    void parsesArgDirective(@TempDir Path root) throws Exception {
        Path file = root.resolve("Dockerfile");
        Files.writeString(file, """
                ARG NODE_VERSION=20
                FROM node:${NODE_VERSION} AS app
                ARG BUILD_ID
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> args = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ContainerBuildArg".equals(u.key().label()))
                .map(e -> (String) ((GraphEvent.NodeUpsert) e).props().get("name"))
                .toList();
        assertThat(args).contains("NODE_VERSION", "BUILD_ID");
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
        new DockerfileParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
