package io.doindev.cvector.parser.hive;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HiveParserAdapterTest {

    @Test
    void parsesHql(@TempDir Path root) throws Exception {
        Path file = root.resolve("query.hql");
        Files.writeString(file, """
                SELECT id FROM users WHERE active = TRUE
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(events).anyMatch(e -> e instanceof GraphEvent.NodeUpsert u
                && "File".equals(u.key().label()));
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new HiveParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
