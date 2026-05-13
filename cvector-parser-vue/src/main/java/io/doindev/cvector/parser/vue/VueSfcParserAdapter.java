package io.doindev.cvector.parser.vue;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.style.StylesheetParserAdapter;
import io.doindev.cvector.parser.ts.TypeScriptParserAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue Single-File Component parser. A .vue file is three regions in one document:
 * {@code <template>}, {@code <script lang="?">}, {@code <style lang="?" scoped?>}.
 *
 * <p>Each region is the domain of an existing parser. We extract the script/style block
 * contents and pipe them through {@link TypeScriptParserAdapter#parseSource} and
 * {@link StylesheetParserAdapter#parseSource} with the .vue file as the identity path,
 * so Class / Method / CssClass nodes attribute back to the SFC instead of a synthetic
 * file. The template block gets a lightweight regex pass to pick up component-tag
 * references, which become {@code REFERENCES_COMPONENT} edges from the File node to
 * a small {@code VueComponent} node keyed by tag name.
 *
 * <p><b>Known scaffold limitations:</b>
 * <ul>
 *   <li>Line numbers reported by the script/style delegates are relative to the start of
 *       each block's content, not the start of the .vue file. A future pass could offset
 *       them by counting newlines before the matched block.</li>
 *   <li>The template regex doesn't understand v-bind / dynamic component / {@code <component is="...">}
 *       patterns; only literal {@code <PascalCase>} or {@code <kebab-cased>} tags are picked up.</li>
 *   <li>{@code <script setup>} is treated the same as a normal script block, which is correct for
 *       extraction but loses the binding-as-template-export semantic.</li>
 * </ul>
 */
public class VueSfcParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(VueSfcParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("vue");

    // Block extractors. (?is) = case-insensitive, DOTALL. Tolerant of attributes after the tag
    // name (lang="ts", setup, scoped, src="...") by matching everything up to the closing '>'.
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "(?is)<script\\b([^>]*)>(.*?)</script>");
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "(?is)<style\\b([^>]*)>(.*?)</style>");
    private static final Pattern TEMPLATE_BLOCK = Pattern.compile(
            "(?is)<template\\b[^>]*>(.*?)</template>");
    private static final Pattern LANG_ATTR = Pattern.compile(
            "(?i)\\blang\\s*=\\s*['\"]([\\w]+)['\"]");
    // Component refs inside a template: <PascalCase> or <kebab-case-with-dash>. Filtered to
    // exclude plain HTML elements (single lowercase words like <div>, <span>, <input>).
    private static final Pattern COMPONENT_REF = Pattern.compile(
            "<([A-Z][A-Za-z0-9]*|[a-z][a-z0-9]*(?:-[a-z0-9]+)+)\\b");

    private final TypeScriptParserAdapter scriptParser;
    private final StylesheetParserAdapter styleParser;

    public VueSfcParserAdapter(TypeScriptParserAdapter scriptParser, StylesheetParserAdapter styleParser) {
        this.scriptParser = scriptParser;
        this.styleParser = styleParser;
    }

    @Override public String name() { return "vue"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            log.warn("failed to read {}: {}", file, e.getMessage());
            return;
        }
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "vue");
        fileProps.put("lineCount", (long) content.split("\n", -1).length);
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        parseScriptBlocks(content, file, ctx, sink);
        parseStyleBlocks(content, file, ctx, sink);
        parseTemplateBlock(content, ctx, fileKey, sink);
    }

    private void parseScriptBlocks(String content, Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        Matcher m = SCRIPT_BLOCK.matcher(content);
        while (m.find()) {
            String attrs = m.group(1);
            String body = m.group(2);
            String langHint = extractLang(attrs);
            String tsLang = switch (langHint == null ? "" : langHint.toLowerCase(Locale.ROOT)) {
                case "ts", "typescript" -> "typescript";
                case "tsx" -> "tsx";
                case "jsx" -> "jsx";
                default -> "javascript";
            };
            try {
                scriptParser.parseSource(body, file, tsLang, ctx, sink, false);
            } catch (RuntimeException e) {
                log.warn("script block parse failed in {}: {}", file, e.getMessage());
            }
        }
    }

    private void parseStyleBlocks(String content, Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        Matcher m = STYLE_BLOCK.matcher(content);
        while (m.find()) {
            String attrs = m.group(1);
            String body = m.group(2);
            String langHint = extractLang(attrs);
            String dialect = switch (langHint == null ? "" : langHint.toLowerCase(Locale.ROOT)) {
                case "scss" -> "SCSS";
                case "sass" -> "SASS";
                case "less" -> "LESS";
                case "styl", "stylus" -> "STYLUS";
                default -> "CSS";
            };
            try {
                styleParser.parseSource(body, file, dialect, ctx, sink, false);
            } catch (RuntimeException e) {
                log.warn("style block parse failed in {}: {}", file, e.getMessage());
            }
        }
    }

    private static void parseTemplateBlock(String content, ProjectContext ctx, NodeKey fileKey,
                                           Consumer<GraphEvent> sink) {
        Matcher m = TEMPLATE_BLOCK.matcher(content);
        if (!m.find()) return;
        Set<String> seen = new HashSet<>();
        Matcher mc = COMPONENT_REF.matcher(m.group(1));
        while (mc.find()) {
            String tag = mc.group(1);
            if (!seen.add(tag)) continue;
            NodeKey ref = new NodeKey(ctx.projectId(), "VueComponent", tag);
            Map<String, Object> props = new HashMap<>();
            props.put("name", tag);
            sink.accept(new GraphEvent.NodeUpsert(ref, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "REFERENCES_COMPONENT", ref, Map.of()));
        }
    }

    private static String extractLang(String attrs) {
        Matcher m = LANG_ATTR.matcher(attrs);
        return m.find() ? m.group(1) : null;
    }
}
