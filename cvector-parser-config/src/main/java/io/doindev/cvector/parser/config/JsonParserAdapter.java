package io.doindev.cvector.parser.config;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class JsonParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(JsonParserAdapter.class);
    private static final Set<String> SKIP_FILES = Set.of("package-lock.json", "yarn.lock", "tsconfig.tsbuildinfo");

    // Enable JSONC affordances (// and /* */ comments, trailing commas) so we can read
    // TypeScript tsconfig.*.json, VS Code .vscode/*.json, and the various other tools whose
    // "JSON" files actually use the JSON-with-comments dialect. Stock JSON would reject these
    // and the parser would log a warning per file without producing any ConfigKey nodes.
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    @Override
    public String name() { return "json"; }

    @Override
    public Set<String> supportedExtensions() { return Set.of("json"); }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString();
        if (SKIP_FILES.contains(name)) return false;
        return name.endsWith(".json");
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "json");
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        JsonNode root;
        try {
            root = mapper.readTree(Files.newInputStream(file));
        } catch (IOException e) {
            log.warn("failed to parse json {}: {}", file, e.getMessage());
            return;
        }
        if (root == null || root.isMissingNode()) return;
        flatten("", root, ctx, fileKey, sink);
    }

    private void flatten(String prefix, JsonNode node, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String full = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(full, e.getValue(), ctx, fileKey, sink);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), ctx, fileKey, sink);
            }
        } else if (!prefix.isEmpty()) {
            NodeKey ck = new NodeKey(ctx.projectId(), "ConfigKey", prefix);
            Map<String, Object> props = new HashMap<>();
            props.put("name", prefix);
            props.put("fqName", prefix);
            props.put("fileId", fileKey.id());
            props.put("value", node.asText());
            sink.accept(new GraphEvent.NodeUpsert(ck, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", ck, Map.of()));
        }
    }
}
