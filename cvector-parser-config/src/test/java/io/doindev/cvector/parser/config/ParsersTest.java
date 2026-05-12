package io.doindev.cvector.parser.config;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParsersTest {

    @Test
    void pomEmitsMavenDependencies(@TempDir Path root) throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        List<GraphEvent> events = parse(new PomParserAdapter(), root, pom);
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.NodeUpsert u && "MavenDependency".equals(u.key().label()))
                .extracting(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .contains("org.junit.jupiter:junit-jupiter");
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.EdgeUpsert eu && "DEPENDS_ON".equals(eu.type()))
                .isNotEmpty();
    }

    @Test
    void yamlFlattensDottedKeys(@TempDir Path root) throws Exception {
        Path file = root.resolve("application.yml");
        Files.writeString(file, """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/db
                    username: app
                server:
                  port: 8080
                """);
        List<GraphEvent> events = parse(new YamlParserAdapter(), root, file);
        List<String> keys = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ConfigKey".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(keys).contains("spring.datasource.url", "spring.datasource.username", "server.port");
    }

    @Test
    void envParserEmitsEnvVarsForDotEnv(@TempDir Path root) throws Exception {
        Path file = root.resolve(".env");
        Files.writeString(file, """
                # comment
                DB_URL=postgres://localhost/db
                API_TOKEN=abc123

                EMPTY=
                """);
        List<GraphEvent> events = parse(new EnvParserAdapter(), root, file);
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.NodeUpsert u && "EnvVar".equals(u.key().label()))
                .extracting(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .contains("DB_URL", "API_TOKEN", "EMPTY");
    }

    @Test
    void propertiesFileEmitsConfigKeys(@TempDir Path root) throws Exception {
        Path file = root.resolve("app.properties");
        Files.writeString(file, """
                spring.datasource.url=jdbc:h2:mem:test
                feature.flag.enabled=true
                """);
        List<GraphEvent> events = parse(new EnvParserAdapter(), root, file);
        assertThat(events).filteredOn(e -> e instanceof GraphEvent.NodeUpsert u && "ConfigKey".equals(u.key().label()))
                .extracting(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .contains("spring.datasource.url", "feature.flag.enabled");
    }

    @Test
    void pomIgnoresCommentedOutDependencies(@TempDir Path root) throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>audit</groupId>
                    <artifactId>audit</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <!-- <dependency><groupId>commented.out</groupId><artifactId>fake-dep</artifactId><version>1.0</version></dependency> -->
                        <dependency><groupId>real.dep</groupId><artifactId>real-artifact</artifactId><version>2.0</version></dependency>
                    </dependencies>
                </project>
                """);
        List<GraphEvent> events = parse(new PomParserAdapter(), root, pom);
        List<String> deps = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "MavenDependency".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(deps).noneMatch(s -> s.toLowerCase().contains("commented") || s.toLowerCase().contains("fake-dep"));
        assertThat(deps).contains("real.dep:real-artifact");
    }

    @Test
    void yamlIgnoresCommentedKeys(@TempDir Path root) throws Exception {
        Path file = root.resolve("application.yml");
        Files.writeString(file, """
                # commented.out.key: should-not-appear
                real:
                  key: real-value
                # nested.commented: fake
                """);
        List<GraphEvent> events = parse(new YamlParserAdapter(), root, file);
        List<String> keys = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ConfigKey".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(keys).noneMatch(s -> s.toLowerCase().contains("commented")
                || s.toLowerCase().contains("should-not-appear"));
        assertThat(keys).contains("real.key");
    }

    @Test
    void jsonParserEmitsKeysAndIgnoresJsonHasNoComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.json");
        Files.writeString(file, """
                {
                  "real_json_key": "real_value",
                  "nested": { "child": "v" }
                }
                """);
        List<GraphEvent> events = parse(new JsonParserAdapter(), root, file);
        List<String> keys = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ConfigKey".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(keys).contains("real_json_key");
    }

    @Test
    void envParserIgnoresCommentLines(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.env");
        Files.writeString(file, """
                # COMMENTED_LINE_VAR=fake
                #   ANOTHER_COMMENTED_VAR=fake
                REAL_ENV_VAR=real
                """);
        List<GraphEvent> events = parse(new EnvParserAdapter(), root, file);
        List<String> vars = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "EnvVar".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(vars).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(vars).contains("REAL_ENV_VAR");
    }

    @Test
    void propertiesIgnoresHashAndBangCommentLines(@TempDir Path root) throws Exception {
        Path file = root.resolve("app.properties");
        Files.writeString(file, """
                # commented.property = fake
                ! also.commented = fake
                real.property = real-value
                """);
        List<GraphEvent> events = parse(new EnvParserAdapter(), root, file);
        List<String> keys = events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "ConfigKey".equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
        assertThat(keys).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(keys).contains("real.property");
    }

    private static List<GraphEvent> parse(io.doindev.cvector.core.Parser parser, Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        parser.parse(file, ctx, events::add);
        return events;
    }
}
