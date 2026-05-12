package io.doindev.cvector.parser.config;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class YamlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(YamlParserAdapter.class);

    @Override
    public String name() { return "yaml"; }

    @Override
    public Set<String> supportedExtensions() { return Set.of("yml", "yaml"); }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "yaml");
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(file)) {
            for (Object doc : yaml.loadAll(in)) {
                flatten("", doc, ctx, fileKey, sink);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("failed to parse yaml {}: {}", file, e.getMessage());
        }
    }

    private void flatten(String prefix, Object node, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String full = prefix.isEmpty() ? key : prefix + "." + key;
                flatten(full, e.getValue(), ctx, fileKey, sink);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), ctx, fileKey, sink);
            }
        } else {
            if (prefix.isEmpty()) return;
            emitConfigKey(prefix, node, ctx, fileKey, sink);
        }
    }

    private void emitConfigKey(String key, Object value, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        NodeKey configKey = new NodeKey(ctx.projectId(), "ConfigKey", key);
        Map<String, Object> props = new HashMap<>();
        props.put("name", key);
        props.put("fqName", key);
        props.put("fileId", fileKey.id());
        props.put("value", value == null ? "" : String.valueOf(value));
        sink.accept(new GraphEvent.NodeUpsert(configKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", configKey, Map.of()));
    }
}
