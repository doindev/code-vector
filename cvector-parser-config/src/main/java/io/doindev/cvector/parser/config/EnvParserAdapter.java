package io.doindev.cvector.parser.config;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class EnvParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(EnvParserAdapter.class);

    @Override
    public String name() { return "env"; }

    @Override
    public Set<String> supportedExtensions() { return Set.of("env", "properties"); }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString();
        return name.equals(".env") || name.endsWith(".env") || name.endsWith(".properties");
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        boolean isProperties = file.getFileName().toString().endsWith(".properties");
        String language = isProperties ? "properties" : "env";

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", language);
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            log.warn("failed to read {}: {}", file, e.getMessage());
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            String label = isProperties ? "ConfigKey" : "EnvVar";
            NodeKey k = new NodeKey(ctx.projectId(), label, key);
            Map<String, Object> props = new HashMap<>();
            props.put("name", key);
            props.put("fqName", key);
            props.put("value", value);
            props.put("fileId", fileKey.id());
            props.put("startLine", i + 1);
            sink.accept(new GraphEvent.NodeUpsert(k, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", k, Map.of()));
        }
    }
}
