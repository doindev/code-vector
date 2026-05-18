package io.doindev.cvector.parser.config;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

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
            // YAML parse failures shouldn't disappear into a stderr line — surface them as
            // queryable graph nodes so `cv_health`, the dashboard, and any rule looking for
            // "files that need attention" can see them. The tab-character-in-yaml case
            // (ScannerException with a precise Mark) is the canonical example; the same
            // path catches every other YAMLException / IOException too.
            emitParseError(file, relPath, ctx, fileKey, e, sink);
        }
    }

    /**
     * Emit a {@code ParseError} node + a {@code CONTAINS} edge from the offending file. The
     * node carries enough info for a human reader to find the bad line: line + column when
     * we can extract them from a {@link MarkedYAMLException} (covers SnakeYAML's
     * {@code ScannerException}, {@code ParserException}, {@code ComposerException},
     * {@code ConstructorException}), plus the exception class name and the raw message.
     *
     * <p>The fqName uses {@code <path>#<line>:<col>} (or {@code <path>#unknown} when the
     * exception has no mark) so two errors in the same file don't collapse to one node via
     * NodeKey deduplication.
     */
    private void emitParseError(Path file, String relPath, ProjectContext ctx, NodeKey fileKey,
                                Throwable t, Consumer<GraphEvent> sink) {
        int line = -1;
        int col = -1;
        if (t instanceof MarkedYAMLException mye) {
            Mark mark = mye.getProblemMark();
            if (mark != null) {
                // SnakeYAML's Mark is 0-indexed; humans count from 1.
                line = mark.getLine() + 1;
                col = mark.getColumn() + 1;
            }
        }
        String posSuffix = (line > 0)
                ? ("#" + line + ":" + (col > 0 ? col : "?"))
                : "#unknown";
        String fqName = relPath + posSuffix;
        String displayName = relPath + (line > 0 ? (":" + line + (col > 0 ? (":" + col) : "")) : "");
        NodeKey errKey = new NodeKey(ctx.projectId(), "ParseError", fqName);
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        // Bound the stored message so a runaway exception trace can't blow up the row size.
        if (message.length() > 2000) message = message.substring(0, 2000) + "… (truncated)";

        Map<String, Object> props = new HashMap<>();
        props.put("name", displayName);
        props.put("fqName", fqName);
        props.put("path", relPath);
        props.put("language", "yaml");
        props.put("fileId", fileKey.id());
        if (line > 0) {
            props.put("startLine", (long) line);
            props.put("endLine", (long) line);
        }
        if (col > 0) props.put("column", (long) col); // reuses no existing column; safely ignored by schemas without it
        props.put("value", message);
        // `source` is a free-form text column already on Node — repurpose it to carry the
        // exception class name so consumers can filter "yaml syntax error" vs "io error".
        props.put("source", t.getClass().getSimpleName());
        sink.accept(new GraphEvent.NodeUpsert(errKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", errKey, Map.of()));

        log.warn("yaml parse error in {} at line {} col {}: {}", relPath, line, col, message);
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
