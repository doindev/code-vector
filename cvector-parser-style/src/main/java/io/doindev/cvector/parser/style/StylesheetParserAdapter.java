package io.doindev.cvector.parser.style;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StylesheetParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(StylesheetParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("css", "scss", "sass", "less", "styl", "stylus");

    enum Dialect {
        CSS(false, false),
        SCSS(false, true),
        SASS(true, true),
        LESS(false, true),
        STYLUS(true, true);

        final boolean indented;
        final boolean supportsLineComments;
        Dialect(boolean indented, boolean supportsLineComments) {
            this.indented = indented;
            this.supportsLineComments = supportsLineComments;
        }
    }

    private static final Pattern IMPORT_RULE = Pattern.compile(
            "@(import|use|forward|require)\\s+(?:url\\(\\s*)?['\"]([^'\"]+)['\"]");
    private static final Pattern CUSTOM_PROPERTY_DEF = Pattern.compile(
            "(?m)(--[A-Za-z_][\\w-]*)\\s*:\\s*([^;\\n}]+)");
    private static final Pattern VAR_REFERENCE = Pattern.compile(
            "var\\(\\s*(--[A-Za-z_][\\w-]*)");
    private static final Pattern SCSS_VARIABLE_DEF = Pattern.compile(
            "(?m)(?:^|[^\\w$])(\\$[A-Za-z_][\\w-]*)\\s*:\\s*([^;\\n}]+?)(?:;|$)");
    private static final Pattern SCSS_VARIABLE_REF = Pattern.compile(
            "(?<![\\w$])(\\$[A-Za-z_][\\w-]*)");
    private static final Pattern LESS_VARIABLE_DEF = Pattern.compile(
            "(?m)^\\s*(@[A-Za-z_][\\w-]*)\\s*:\\s*([^;\\n}]+);");
    private static final Pattern LESS_VARIABLE_REF = Pattern.compile(
            "(?<![\\w@])(@[A-Za-z_][\\w-]*)(?![\\w-]*\\s*\\{)");
    private static final Pattern STYLUS_VARIABLE_DEF = Pattern.compile(
            "(?m)^\\s*([a-zA-Z_][\\w-]*)\\s*=\\s*([^=\\n][^\\n]*)$");
    private static final Pattern MIXIN_DEF_SCSS = Pattern.compile(
            "@mixin\\s+([A-Za-z_-][\\w-]*)\\s*(?:\\([^)]*\\))?");
    private static final Pattern MIXIN_DEF_SASS_SHORT = Pattern.compile(
            "(?m)^\\s*=\\s*([A-Za-z_-][\\w-]*)");
    private static final Pattern INCLUDE_USE = Pattern.compile(
            "@include\\s+(?:[\\w.-]+\\.)?([A-Za-z_-][\\w-]*)");
    private static final Pattern SASS_INCLUDE_SHORTHAND = Pattern.compile(
            "(?m)^\\s*\\+([A-Za-z_-][\\w-]*)");
    private static final Pattern EXTEND_USE = Pattern.compile(
            "@extend\\s+\\.([A-Za-z_-][\\w-]*)");
    private static final Pattern CLASS_SELECTOR = Pattern.compile(
            "(?<![\\w-])\\.([A-Za-z_-][\\w-]*)");

    private static final Set<String> LESS_AT_RULES = Set.of(
            "@media", "@import", "@use", "@forward", "@require", "@charset", "@font-face",
            "@keyframes", "@page", "@supports", "@namespace", "@layer", "@container",
            "@mixin", "@include", "@extend", "@function", "@return", "@if", "@else",
            "@each", "@for", "@while", "@debug", "@warn", "@error"
    );

    private static final Set<String> STYLUS_PROPERTY_GUARDS = Set.of(
            "color", "background", "margin", "padding", "border", "font", "width", "height",
            "display", "position", "top", "left", "right", "bottom", "opacity", "z-index"
    );

    @Override public String name() { return "stylesheet"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            log.warn("could not read {}: {}", file, e.getMessage());
            return;
        }
        parseSource(source, file, null, ctx, sink, true);
    }

    /**
     * Parse a stylesheet source string. Used by the Vue SFC parser to pipe {@code <style>}
     * block contents through the same collector chain without first writing to disk.
     *
     * @param source        the stylesheet text
     * @param identityPath  path the resulting node identities derive from (the {@code File}
     *                      NodeKey and CssClass/DesignToken fqNames are keyed off this). For Vue
     *                      this is the .vue file path so nodes attribute back to the SFC.
     * @param dialectHint   optional explicit dialect name ({@code "CSS"}, {@code "SCSS"},
     *                      {@code "SASS"}, {@code "LESS"}, {@code "STYLUS"}). When null, derived
     *                      from {@code identityPath}'s extension. Set this for Vue's
     *                      {@code <style lang="scss">} since the .vue extension hides the dialect.
     * @param emitFileNode  pass {@code false} when the caller has already emitted a File node
     *                      for {@code identityPath}.
     */
    public void parseSource(String source, Path identityPath, String dialectHint,
                            ProjectContext ctx, Consumer<GraphEvent> sink, boolean emitFileNode) {
        Dialect dialect = (dialectHint != null)
                ? Dialect.valueOf(dialectHint.toUpperCase(java.util.Locale.ROOT))
                : detectDialect(identityPath);
        String stripped = stripComments(source, dialect.supportsLineComments);
        String relPath = ctx.rootPath().relativize(identityPath).toString().replace('\\', '/');
        String language = dialect.name().toLowerCase();
        boolean isModule = relPath.toLowerCase().contains(".module.");

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        if (emitFileNode) {
            Map<String, Object> fileProps = new HashMap<>();
            fileProps.put("path", relPath);
            fileProps.put("language", language);
            fileProps.put("lineCount", source.lines().count());
            if (isModule) fileProps.put("cssModule", true);
            sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));
        }

        collectImports(stripped, ctx, fileKey, sink);
        collectCustomProperties(stripped, ctx, fileKey, sink);
        collectVarReferences(stripped, ctx, fileKey, sink);

        switch (dialect) {
            case SCSS, SASS -> collectScssLikeVariables(stripped, ctx, fileKey, relPath, sink);
            case LESS -> collectLessVariables(stripped, ctx, fileKey, relPath, sink);
            case STYLUS -> collectStylusVariables(stripped, ctx, fileKey, relPath, sink);
            case CSS -> { /* custom properties already covered */ }
        }

        collectMixinDefinitions(stripped, ctx, fileKey, relPath, dialect, sink);
        collectMixinUses(stripped, ctx, fileKey, dialect, sink);
        collectExtendUses(stripped, ctx, fileKey, sink);
        collectClassSelectors(stripped, ctx, fileKey, relPath, isModule, dialect, sink);
    }

    private static Dialect detectDialect(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".scss")) return Dialect.SCSS;
        if (name.endsWith(".sass")) return Dialect.SASS;
        if (name.endsWith(".less")) return Dialect.LESS;
        if (name.endsWith(".styl") || name.endsWith(".stylus")) return Dialect.STYLUS;
        return Dialect.CSS;
    }

    private void collectImports(String text, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        Set<String> seen = new HashSet<>();
        Matcher m = IMPORT_RULE.matcher(text);
        while (m.find()) {
            String target = m.group(2);
            if (target == null || target.isBlank() || !seen.add(target)) continue;
            NodeKey moduleKey = new NodeKey(ctx.projectId(), "Module", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(moduleKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", moduleKey, Map.of("via", "@" + m.group(1))));
        }
    }

    private void collectCustomProperties(String text, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        Set<String> seen = new HashSet<>();
        Matcher m = CUSTOM_PROPERTY_DEF.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!seen.add(name)) continue;
            emitToken(ctx, fileKey, name, name, m.group(2).trim(),
                    "custom-property", lineOf(text, m.start()), true, sink);
        }
    }

    private void collectVarReferences(String text, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        Set<String> seen = new HashSet<>();
        Matcher m = VAR_REFERENCE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!seen.add(name)) continue;
            emitTokenStub(ctx, name, name, "custom-property", sink);
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "READS_CONFIG",
                    new NodeKey(ctx.projectId(), "DesignToken", name), Map.of()));
        }
    }

    private void collectScssLikeVariables(String text, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        Set<String> definedHere = new HashSet<>();
        Matcher def = SCSS_VARIABLE_DEF.matcher(text);
        while (def.find()) {
            String varName = def.group(1);
            String fqName = relPath + "::" + varName;
            if (!definedHere.add(fqName)) continue;
            emitToken(ctx, fileKey, varName, fqName, def.group(2).trim(),
                    "scss-variable", lineOf(text, def.start()), true, sink);
        }
        Set<String> referenced = new HashSet<>();
        Matcher ref = SCSS_VARIABLE_REF.matcher(text);
        while (ref.find()) {
            String name = ref.group(1);
            if (definedHere.contains(relPath + "::" + name)) continue;
            if (!referenced.add(name)) continue;
            emitTokenStub(ctx, name, name, "scss-variable-ref", sink);
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "READS_CONFIG",
                    new NodeKey(ctx.projectId(), "DesignToken", name), Map.of()));
        }
    }

    private void collectLessVariables(String text, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        Set<String> definedHere = new HashSet<>();
        Matcher def = LESS_VARIABLE_DEF.matcher(text);
        while (def.find()) {
            String varName = def.group(1);
            String fqName = relPath + "::" + varName;
            if (!definedHere.add(fqName)) continue;
            emitToken(ctx, fileKey, varName, fqName, def.group(2).trim(),
                    "less-variable", lineOf(text, def.start()), true, sink);
        }
        Set<String> referenced = new HashSet<>();
        Matcher ref = LESS_VARIABLE_REF.matcher(text);
        while (ref.find()) {
            String name = ref.group(1);
            if (LESS_AT_RULES.contains(name.toLowerCase())) continue;
            if (definedHere.contains(relPath + "::" + name)) continue;
            if (!referenced.add(name)) continue;
            emitTokenStub(ctx, name, name, "less-variable-ref", sink);
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "READS_CONFIG",
                    new NodeKey(ctx.projectId(), "DesignToken", name), Map.of()));
        }
    }

    private void collectStylusVariables(String text, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        Set<String> definedHere = new HashSet<>();
        Matcher def = STYLUS_VARIABLE_DEF.matcher(text);
        while (def.find()) {
            String varName = def.group(1);
            String value = def.group(2).trim();
            if (STYLUS_PROPERTY_GUARDS.contains(varName.toLowerCase())) continue;
            String fqName = relPath + "::" + varName;
            if (!definedHere.add(fqName)) continue;
            emitToken(ctx, fileKey, varName, fqName, value,
                    "stylus-variable", lineOf(text, def.start()), true, sink);
        }
    }

    private void collectMixinDefinitions(String text, ProjectContext ctx, NodeKey fileKey, String relPath,
                                          Dialect dialect, Consumer<GraphEvent> sink) {
        Matcher m1 = MIXIN_DEF_SCSS.matcher(text);
        while (m1.find()) emitMixin(m1.group(1), m1.start(), text, ctx, fileKey, relPath, sink);
        if (dialect == Dialect.SASS) {
            Matcher m2 = MIXIN_DEF_SASS_SHORT.matcher(text);
            while (m2.find()) emitMixin(m2.group(1), m2.start(), text, ctx, fileKey, relPath, sink);
        }
    }

    private void emitMixin(String name, int pos, String text, ProjectContext ctx, NodeKey fileKey,
                            String relPath, Consumer<GraphEvent> sink) {
        String fqName = relPath + "::" + name;
        NodeKey mixinKey = new NodeKey(ctx.projectId(), "CssMixin", fqName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", fqName);
        props.put("fileId", fileKey.id());
        props.put("startLine", lineOf(text, pos));
        sink.accept(new GraphEvent.NodeUpsert(mixinKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", mixinKey, Map.of()));
    }

    private void collectMixinUses(String text, ProjectContext ctx, NodeKey fileKey, Dialect dialect, Consumer<GraphEvent> sink) {
        Set<String> seen = new HashSet<>();
        Matcher m = INCLUDE_USE.matcher(text);
        while (m.find()) emitMixinUse(m.group(1), m.start(), text, ctx, fileKey, "@include", seen, sink);
        if (dialect == Dialect.SASS) {
            Matcher s = SASS_INCLUDE_SHORTHAND.matcher(text);
            while (s.find()) emitMixinUse(s.group(1), s.start(), text, ctx, fileKey, "+", seen, sink);
        }
    }

    private void emitMixinUse(String name, int pos, String text, ProjectContext ctx, NodeKey fileKey,
                               String via, Set<String> seen, Consumer<GraphEvent> sink) {
        if (!seen.add(via + ":" + name)) return;
        NodeKey stub = new NodeKey(ctx.projectId(), "CssMixin", name);
        Map<String, Object> stubProps = new HashMap<>();
        stubProps.put("name", name);
        stubProps.put("fqName", name);
        sink.accept(new GraphEvent.NodeUpsert(stub, stubProps));
        Map<String, Object> edge = new HashMap<>();
        edge.put("via", via);
        edge.put("callSiteLine", lineOf(text, pos));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CALLS", stub, edge));
    }

    private void collectExtendUses(String text, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        Set<String> seen = new HashSet<>();
        Matcher m = EXTEND_USE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!seen.add(name)) continue;
            NodeKey stub = new NodeKey(ctx.projectId(), "CssClass", name);
            Map<String, Object> stubProps = new HashMap<>();
            stubProps.put("name", name);
            stubProps.put("fqName", name);
            sink.accept(new GraphEvent.NodeUpsert(stub, stubProps));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "EXTENDS", stub, Map.of()));
        }
    }

    private void collectClassSelectors(String text, ProjectContext ctx, NodeKey fileKey, String relPath,
                                       boolean isModule, Dialect dialect, Consumer<GraphEvent> sink) {
        Set<String> emitted = new HashSet<>();
        Matcher m = CLASS_SELECTOR.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!isSelectorContext(text, m.start(), dialect)) continue;
            String fqName = isModule ? relPath + "::" + name : name;
            if (!emitted.add(fqName)) continue;
            NodeKey classKey = new NodeKey(ctx.projectId(), "CssClass", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("fileId", fileKey.id());
            props.put("startLine", lineOf(text, m.start()));
            if (isModule) props.put("moduleExport", true);
            sink.accept(new GraphEvent.NodeUpsert(classKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", classKey, Map.of()));
        }
    }

    private static boolean isSelectorContext(String text, int pos, Dialect dialect) {
        if (dialect.indented) {
            int j = pos - 1;
            while (j >= 0 && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) j--;
            return j < 0 || text.charAt(j) == '\n';
        }
        int i = pos + 1;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '-' || text.charAt(i) == '_')) i++;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i >= text.length()) return false;
        char c = text.charAt(i);
        return c == '{' || c == ',' || c == '>' || c == '+' || c == '~'
                || c == '.' || c == '#' || c == ':' || c == '[' || c == '&'
                || c == '\n' || c == '(';
    }

    private void emitToken(ProjectContext ctx, NodeKey fileKey, String name, String fqName,
                            String value, String kind, int line, boolean contain, Consumer<GraphEvent> sink) {
        NodeKey tokenKey = new NodeKey(ctx.projectId(), "DesignToken", fqName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", fqName);
        props.put("value", value);
        props.put("kind", kind);
        props.put("fileId", fileKey.id());
        props.put("startLine", line);
        sink.accept(new GraphEvent.NodeUpsert(tokenKey, props));
        if (contain) sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", tokenKey, Map.of()));
    }

    private void emitTokenStub(ProjectContext ctx, String name, String fqName, String kind, Consumer<GraphEvent> sink) {
        NodeKey tokenKey = new NodeKey(ctx.projectId(), "DesignToken", fqName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", fqName);
        props.put("kind", kind);
        sink.accept(new GraphEvent.NodeUpsert(tokenKey, props));
    }

    private static String stripComments(String source, boolean lineComments) {
        if (source == null || source.isEmpty()) return source == null ? "" : source;
        char[] arr = source.toCharArray();
        StringBuilder out = new StringBuilder(arr.length);
        int i = 0;
        while (i < arr.length) {
            char c = arr[i];
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < arr.length && arr[i] != quote) {
                    if (arr[i] == '\\' && i + 1 < arr.length) {
                        out.append(arr[i]).append(arr[i + 1]);
                        i += 2;
                        continue;
                    }
                    out.append(arr[i]);
                    if (arr[i] == '\n') { i++; break; }
                    i++;
                }
                if (i < arr.length) { out.append(arr[i]); i++; }
            } else if (lineComments && c == '/' && i + 1 < arr.length && arr[i + 1] == '/') {
                while (i < arr.length && arr[i] != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < arr.length && arr[i + 1] == '*') {
                out.append("  ");
                i += 2;
                while (i < arr.length - 1 && !(arr[i] == '*' && arr[i + 1] == '/')) {
                    out.append(arr[i] == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < arr.length - 1) { out.append("  "); i += 2; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf(String text, int position) {
        int line = 1;
        int upTo = Math.min(position, text.length());
        for (int i = 0; i < upTo; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
