package io.doindev.cvector.parser.docker;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DockerfileParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(DockerfileParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("dockerfile");
    private static final Set<String> FILENAMES = Set.of("dockerfile", "containerfile");

    private static final Set<String> DIRECTIVES = Set.of(
            "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY",
            "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL",
            "HEALTHCHECK", "SHELL"
    );

    private static final Pattern PORT_PATTERN = Pattern.compile(
            "(\\d{1,5})(?:/(tcp|udp))?");

    private static final Pattern ENV_KV = Pattern.compile(
            "([A-Za-z_][\\w]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|\\S+)");

    @Override public String name() { return "dockerfile"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (FILENAMES.contains(name)) return true;
        if (name.endsWith(".dockerfile")) return true;
        if (name.startsWith("dockerfile.")) return true;
        return false;
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            log.warn("could not read {}: {}", file, e.getMessage());
            return;
        }
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "dockerfile");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        List<Directive> directives = parseDirectives(source);
        emit(directives, ctx, fileKey, relPath, sink);
    }

    private void emit(List<Directive> directives, ProjectContext ctx, NodeKey fileKey, String relPath,
                      Consumer<GraphEvent> sink) {
        EmitState s = new EmitState();
        for (Directive d : directives) {
            switch (d.keyword) {
                case "FROM" -> handleFrom(d, ctx, fileKey, relPath, sink, s);
                case "EXPOSE" -> handleExpose(d, ctx, fileKey, relPath, sink, s);
                case "ENV" -> handleEnv(d, ctx, fileKey, sink, s);
                case "ARG" -> handleArg(d, ctx, fileKey, relPath, sink, s);
                case "ENTRYPOINT", "CMD" -> handleCommand(d, ctx, sink, s);
                default -> { /* RUN/COPY/ADD/etc. not first-class for now */ }
            }
        }
    }

    private static final class EmitState {
        final List<NodeKey> stages = new ArrayList<>();
        final Set<String> emittedImages = new HashSet<>();
        final Set<Integer> emittedPorts = new HashSet<>();
        final Set<String> emittedEnv = new HashSet<>();
        NodeKey ownerStage(NodeKey fallback) {
            return stages.isEmpty() ? fallback : stages.get(stages.size() - 1);
        }
    }

    private void handleFrom(Directive d, ProjectContext ctx, NodeKey fileKey, String relPath,
                             Consumer<GraphEvent> sink, EmitState s) {
        FromInfo info = parseFrom(d.value);
        String stageName = info.stageName != null ? info.stageName : "stage-" + s.stages.size();
        String stageFq = relPath + "::" + stageName;
        NodeKey stageKey = new NodeKey(ctx.projectId(), "ContainerStage", stageFq);
        Map<String, Object> stageProps = new HashMap<>();
        stageProps.put("name", stageName);
        stageProps.put("fqName", stageFq);
        stageProps.put("startLine", d.line);
        stageProps.put("fileId", fileKey.id());
        stageProps.put("baseImage", info.image);
        sink.accept(new GraphEvent.NodeUpsert(stageKey, stageProps));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", stageKey, Map.of()));

        if (s.emittedImages.add(info.image)) {
            NodeKey imageKey = new NodeKey(ctx.projectId(), "ContainerImage", info.image);
            Map<String, Object> imageProps = new HashMap<>();
            imageProps.put("name", info.image);
            imageProps.put("fqName", info.image);
            imageProps.put("repository", info.repository);
            if (info.tag != null) imageProps.put("tag", info.tag);
            if (info.digest != null) imageProps.put("digest", info.digest);
            imageProps.put("isBase", true);
            sink.accept(new GraphEvent.NodeUpsert(imageKey, imageProps));
        }
        NodeKey imageKey = new NodeKey(ctx.projectId(), "ContainerImage", info.image);
        sink.accept(new GraphEvent.EdgeUpsert(stageKey, "DEPENDS_ON", imageKey, Map.of("kind", "baseImage")));
        s.stages.add(stageKey);
    }

    private void handleExpose(Directive d, ProjectContext ctx, NodeKey fileKey, String relPath,
                               Consumer<GraphEvent> sink, EmitState s) {
        Matcher m = PORT_PATTERN.matcher(d.value);
        while (m.find()) {
            int port = Integer.parseInt(m.group(1));
            String proto = m.group(2) != null ? m.group(2) : "tcp";
            if (!s.emittedPorts.add(port * 10 + (proto.equals("udp") ? 1 : 0))) continue;
            String fqName = relPath + "::port:" + port + "/" + proto;
            NodeKey portKey = new NodeKey(ctx.projectId(), "ContainerPort", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", port + "/" + proto);
            props.put("fqName", fqName);
            props.put("port", port);
            props.put("protocol", proto);
            props.put("startLine", d.line);
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(portKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(s.ownerStage(fileKey), "EXPOSES", portKey, Map.of()));
        }
    }

    private void handleEnv(Directive d, ProjectContext ctx, NodeKey fileKey,
                            Consumer<GraphEvent> sink, EmitState s) {
        for (Map.Entry<String, String> kv : parseEnv(d.value).entrySet()) {
            if (!s.emittedEnv.add(kv.getKey())) continue;
            NodeKey envKey = new NodeKey(ctx.projectId(), "EnvVar", kv.getKey());
            Map<String, Object> props = new HashMap<>();
            props.put("name", kv.getKey());
            props.put("fqName", kv.getKey());
            props.put("value", kv.getValue());
            props.put("source", "dockerfile");
            props.put("startLine", d.line);
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(envKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(s.ownerStage(fileKey), "DECLARES", envKey, Map.of()));
        }
    }

    private void handleArg(Directive d, ProjectContext ctx, NodeKey fileKey, String relPath,
                            Consumer<GraphEvent> sink, EmitState s) {
        String argName = parseArgName(d.value);
        if (argName == null) return;
        NodeKey argKey = new NodeKey(ctx.projectId(), "ContainerBuildArg", relPath + "::" + argName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", argName);
        props.put("fqName", relPath + "::" + argName);
        props.put("startLine", d.line);
        props.put("fileId", fileKey.id());
        sink.accept(new GraphEvent.NodeUpsert(argKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(s.ownerStage(fileKey), "DECLARES", argKey, Map.of()));
    }

    private void handleCommand(Directive d, ProjectContext ctx, Consumer<GraphEvent> sink, EmitState s) {
        if (s.stages.isEmpty()) return;
        NodeKey stage = s.stages.get(s.stages.size() - 1);
        String kind = d.keyword.toLowerCase(Locale.ROOT);
        String fq = stage.fqName() + "::" + kind;
        NodeKey cmdKey = new NodeKey(ctx.projectId(), "ContainerCommand", fq);
        Map<String, Object> props = new HashMap<>();
        props.put("name", kind);
        props.put("fqName", fq);
        props.put("kind", kind);
        props.put("command", d.value.trim());
        props.put("startLine", d.line);
        sink.accept(new GraphEvent.NodeUpsert(cmdKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(stage, "DECLARES", cmdKey, Map.of()));
    }

    record Directive(String keyword, String value, int line) {}

    /** Parse logical Dockerfile directives, honoring `\\` continuations and ignoring comment-only lines. */
    static List<Directive> parseDirectives(String source) {
        List<Directive> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String stripped = raw.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                i++;
                continue;
            }
            int directiveLine = i + 1;
            StringBuilder logical = new StringBuilder(raw);
            // Continuation support: trailing backslash continues to next line, comments between are skipped.
            while (endsWithBackslashContinuation(logical.toString())) {
                logical.setLength(logical.length() - 1);
                int j = i + 1;
                while (j < lines.length && lines[j].stripLeading().startsWith("#")) j++;
                if (j >= lines.length) break;
                logical.append('\n').append(lines[j]);
                i = j;
            }
            String full = logical.toString().trim();
            int firstWs = firstWhitespace(full);
            if (firstWs < 0) { i++; continue; }
            String keyword = full.substring(0, firstWs).toUpperCase(Locale.ROOT);
            if (!DIRECTIVES.contains(keyword)) { i++; continue; }
            String value = full.substring(firstWs).trim();
            out.add(new Directive(keyword, value, directiveLine));
            i++;
        }
        return out;
    }

    private static int firstWhitespace(String s) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == ' ' || c == '\t') return k;
        }
        return -1;
    }

    private static boolean endsWithBackslashContinuation(String s) {
        int n = s.length();
        if (n == 0) return false;
        // Trim trailing spaces/tabs but not newlines.
        int k = n - 1;
        while (k >= 0 && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k--;
        if (k < 0) return false;
        return s.charAt(k) == '\\';
    }

    record FromInfo(String image, String repository, String tag, String digest, String stageName) {}

    static FromInfo parseFrom(String value) {
        String v = value.trim();
        // strip --platform=...
        while (v.startsWith("--")) {
            int sp = v.indexOf(' ');
            if (sp < 0) { v = ""; break; }
            v = v.substring(sp + 1).trim();
        }
        String stage = null;
        String[] parts = v.split("\\s+");
        if (parts.length >= 3 && parts[1].equalsIgnoreCase("AS")) {
            stage = parts[2];
        }
        String imageRef = parts[0];
        String repository = imageRef;
        String tag = null;
        String digest = null;
        int atIdx = imageRef.indexOf('@');
        if (atIdx >= 0) {
            repository = imageRef.substring(0, atIdx);
            digest = imageRef.substring(atIdx + 1);
        } else {
            int colon = imageRef.lastIndexOf(':');
            int slash = imageRef.lastIndexOf('/');
            if (colon > slash) {
                repository = imageRef.substring(0, colon);
                tag = imageRef.substring(colon + 1);
            }
        }
        return new FromInfo(imageRef, repository, tag, digest, stage);
    }

    static Map<String, String> parseEnv(String value) {
        Map<String, String> out = new HashMap<>();
        String v = value.trim();
        if (v.isEmpty()) return out;
        // Form 1: ENV KEY VALUE (single key, value runs to end-of-line).
        // Form 2: ENV KEY=VALUE [KEY=VALUE ...]
        if (!v.contains("=")) return out;
        if (firstWhitespaceBefore(v, '=') >= 0 && !looksLikeMultiKv(v)) {
            int sp = firstWhitespace(v);
            if (sp > 0) {
                String key = v.substring(0, sp).trim();
                String val = v.substring(sp + 1).trim();
                if (!key.isEmpty()) out.put(key, unquote(val));
                return out;
            }
        }
        Matcher m = ENV_KV.matcher(v);
        while (m.find()) {
            out.put(m.group(1), unquote(m.group(2)));
        }
        return out;
    }

    private static int firstWhitespaceBefore(String s, char c) {
        int eq = s.indexOf(c);
        if (eq < 0) return -1;
        for (int i = 0; i < eq; i++) {
            char ch = s.charAt(i);
            if (ch == ' ' || ch == '\t') return i;
        }
        return -1;
    }

    private static boolean looksLikeMultiKv(String v) {
        int eq = v.indexOf('=');
        if (eq < 0) return false;
        return v.indexOf('=', eq + 1) >= 0
                || (v.indexOf(' ', eq) > 0 && hasIdentBeforeEq(v.substring(v.indexOf(' ', eq)).trim()));
    }

    private static boolean hasIdentBeforeEq(String s) {
        int eq = s.indexOf('=');
        if (eq <= 0) return false;
        for (int i = 0; i < eq; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char f = s.charAt(0);
            char l = s.charAt(s.length() - 1);
            if ((f == '"' || f == '\'') && f == l) return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String parseArgName(String value) {
        String v = value.trim();
        int eq = v.indexOf('=');
        String name = eq >= 0 ? v.substring(0, eq).trim() : v;
        int ws = firstWhitespace(name);
        if (ws >= 0) name = name.substring(0, ws);
        return name.isEmpty() ? null : name;
    }
}
