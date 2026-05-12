package io.doindev.cvector.parser.javascript;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.javascript.internal.JavaScriptLexer;
import io.doindev.cvector.parser.javascript.internal.JavaScriptParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
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

public class JavaScriptParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(JavaScriptParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("js", "mjs", "cjs");
    private static final Set<String> SKIP_NAMES = Set.of("vite.config.js", "next.config.js");

    @Override public String name() { return "javascript"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString();
        if (SKIP_NAMES.contains(name)) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
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
        fileProps.put("language", "javascript");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        JavaScriptLexer lexer = new JavaScriptLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        JavaScriptParser parser = new JavaScriptParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        JavaScriptParser.ProgramContext tree;
        try {
            tree = parser.program();
        } catch (RuntimeException e) {
            log.warn("JS parse failed for {}: {}", file, e.getMessage());
            return;
        }

        new Walker(ctx, fileKey, relPath, sink).walk(tree);
    }

    private static final class Walker {
        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final String relPath;
        private final Consumer<GraphEvent> sink;
        private final Set<String> seenImports = new HashSet<>();

        Walker(ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.relPath = relPath;
            this.sink = sink;
        }

        void walk(ParseTree node) {
            if (node instanceof JavaScriptParser.ClassDeclarationContext cd) emitClass(cd);
            else if (node instanceof JavaScriptParser.FunctionDeclarationContext fd) emitFunction(fd);
            else if (node instanceof JavaScriptParser.ImportStatementContext is) emitImport(is);
            for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i));
        }

        private void emitClass(JavaScriptParser.ClassDeclarationContext cd) {
            String name = cd.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Class", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", "class");
            props.put("startLine", lineOf(cd));
            props.put("endLine", endLineOf(cd));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            for (JavaScriptParser.ClassElementContext el : cd.classTail().classElement()) {
                JavaScriptParser.MethodDefinitionContext md = el.methodDefinition();
                if (md == null) continue;
                JavaScriptParser.ClassElementNameContext nameCtx = md.classElementName();
                if (nameCtx == null) continue;
                String mname = nameCtx.getText();
                String mfq = fqName + "." + mname;
                NodeKey mkey = new NodeKey(ctx.projectId(), "Method", mfq);
                Map<String, Object> mp = new HashMap<>();
                mp.put("name", mname);
                mp.put("fqName", mfq);
                mp.put("startLine", lineOf(md));
                mp.put("classId", key.id());
                sink.accept(new GraphEvent.NodeUpsert(mkey, mp));
                sink.accept(new GraphEvent.EdgeUpsert(key, "CONTAINS", mkey, Map.of()));
            }
        }

        private void emitFunction(JavaScriptParser.FunctionDeclarationContext fd) {
            String name = fd.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(fd));
            props.put("endLine", endLineOf(fd));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }

        private void emitImport(JavaScriptParser.ImportStatementContext is) {
            String target = findFirstStringLiteralInChildren(is);
            if (target == null || target.isBlank() || !seenImports.add(target)) return;
            NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(mod, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
        }

        private static String findFirstStringLiteralInChildren(ParseTree node) {
            if (node instanceof TerminalNode tn) {
                String t = tn.getText();
                if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'' || t.charAt(0) == '`')) {
                    return stripQuotes(t);
                }
                return null;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                String s = findFirstStringLiteralInChildren(node.getChild(i));
                if (s != null) return s;
            }
            return null;
        }

        private static String stripQuotes(String s) {
            if (s == null) return null;
            if (s.length() >= 2) {
                char f = s.charAt(0);
                char l = s.charAt(s.length() - 1);
                if ((f == '"' || f == '\'' || f == '`') && f == l) return s.substring(1, s.length() - 1);
            }
            return s;
        }

        private static int lineOf(ParserRuleContext c) {
            Token t = c.getStart();
            return t != null ? t.getLine() : 0;
        }

        private static int endLineOf(ParserRuleContext c) {
            Token t = c.getStop();
            return t != null ? t.getLine() : 0;
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            log.debug("JS ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
