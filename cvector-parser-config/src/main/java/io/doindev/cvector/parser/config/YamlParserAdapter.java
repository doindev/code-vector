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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class YamlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(YamlParserAdapter.class);

    /**
     * Per-file dedupe key for the parse-error console log. Maps file path → "line:col:type"
     * of the last error we logged. The watcher re-parses on every file-change event
     * (debounced ~250 ms), and an editor's save flurry (write, format-on-save, save-again)
     * can fire two events back-to-back where the file is still in the broken state — we'd
     * otherwise spam the same error line per event. The graph upsert is idempotent
     * (same NodeKey id), so we always emit the node; only the {@link Logger#warn} call is
     * gated. Cleared per-file on a successful parse so a fresh break of the same line
     * still logs.
     */
    private static final Map<String, String> LAST_LOGGED_ERROR = new ConcurrentHashMap<>();

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
            // Successful parse — clear the dedupe latch so any future break logs fresh,
            // even if the next failure happens at exactly the same line/col/type as the
            // last one we suppressed.
            LAST_LOGGED_ERROR.remove(relPath);
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
     * node carries enough info for a human reader to find the bad line: the absolute file
     * path in {@code name} (so a terminal log line is click-friendly), line + column from
     * the SnakeYAML {@link Mark} when available, the exception class name, and a **short**
     * one-line summary of the problem. No stack trace, no SnakeYAML source-snippet/caret
     * — just the file and what's wrong.
     *
     * <p>The fqName uses {@code <relPath>#<line>:<col>} (or {@code <relPath>#unknown} when
     * the exception has no mark) so two errors in the same file don't collapse to one
     * node via NodeKey deduplication.
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
        String absPath = file.toAbsolutePath().toString().replace('\\', '/');
        String displayName = absPath + (line > 0 ? (":" + line + (col > 0 ? (":" + col) : "")) : "");
        NodeKey errKey = new NodeKey(ctx.projectId(), "ParseError", fqName);
        String shortMessage = shortenMessage(t);

        Map<String, Object> props = new HashMap<>();
        props.put("name", displayName);          // absolute path + line:col — what consumers should display
        props.put("fqName", fqName);
        props.put("path", relPath);              // project-relative — matches the File node's `path`
        props.put("language", "yaml");
        props.put("fileId", fileKey.id());
        if (line > 0) {
            props.put("startLine", (long) line);
            props.put("endLine", (long) line);
        }
        if (col > 0) props.put("column", (long) col);
        props.put("value", shortMessage);
        // `source` is a free-form text column already on Node — repurpose it to carry the
        // exception class name so consumers can filter "yaml syntax error" vs "io error".
        props.put("source", t.getClass().getSimpleName());
        sink.accept(new GraphEvent.NodeUpsert(errKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", errKey, Map.of()));

        // Dedupe the console log per file. The graph upsert above is idempotent (same
        // NodeKey id every time), so emitting on every re-fire is fine — but printing
        // the same warn line for every flush cycle while the file stays broken just
        // adds noise. Key by line:col:exceptionType so a different break in the same
        // file does log fresh, and a clean parse in between clears the latch (see the
        // success path in {@link #parse}).
        String errorKey = line + ":" + col + ":" + t.getClass().getSimpleName();
        String prev = LAST_LOGGED_ERROR.put(relPath, errorKey);
        if (errorKey.equals(prev)) return;
        // Console log: absolute path so terminals can click straight to the file.
        if (line > 0) {
            log.warn("yaml parse error: {}:{}:{} — {}", absPath, line, col > 0 ? col : "?", shortMessage);
        } else {
            log.warn("yaml parse error: {} — {}", absPath, shortMessage);
        }
    }

    /**
     * Reduce a SnakeYAML exception to a single-line summary — no source-line snippet, no
     * caret marker, no stack trace. For {@link MarkedYAMLException}, that's
     * {@code "<context>: <problem>"} (e.g. {@code "while scanning for the next token:
     * found character '\t(TAB)' that cannot start any token"}); for anything else we
     * truncate {@code getMessage()} to ~200 chars and strip newlines. Bounded length keeps
     * the row size predictable and the log line readable on a normal terminal.
     */
    private static String shortenMessage(Throwable t) {
        if (t instanceof MarkedYAMLException mye) {
            String context = mye.getContext();
            String problem = mye.getProblem();
            if (context != null && problem != null) return (context + ": " + problem).trim();
            if (problem != null) return problem.trim();
            if (context != null) return context.trim();
        }
        String raw = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        // Collapse whitespace + drop newlines so the message stays on one log line.
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
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
