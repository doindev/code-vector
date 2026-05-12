package io.doindev.cvector.parser.ts;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.ts.internal.TypeScriptLexer;
import io.doindev.cvector.parser.ts.internal.TypeScriptParser;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeScriptParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("ts", "tsx", "js", "jsx", "mjs", "cjs");
    private static final Set<String> SKIP_NAMES = Set.of("vite.config.ts", "next.config.js");

    @Override public String name() { return "typescript"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".d.ts")) return false;
        if (SKIP_NAMES.contains(name)) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
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
        String language = detectLanguage(file);

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", language);
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        TypeScriptLexer lexer = new TypeScriptLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TypeScriptParser parser = new TypeScriptParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        TypeScriptParser.ProgramContext tree;
        try {
            tree = parser.program();
        } catch (RuntimeException e) {
            log.warn("TS parse failed for {}: {}", file, e.getMessage());
            return;
        }

        new Walker(ctx, fileKey, relPath, sink).walk(tree);
    }

    private static String detectLanguage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tsx")) return "tsx";
        if (name.endsWith(".jsx")) return "jsx";
        if (name.endsWith(".ts")) return "typescript";
        if (name.endsWith(".mjs") || name.endsWith(".cjs")) return "javascript";
        return "javascript";
    }

    private static final class Walker {

        private static final Pattern EXPRESS_PATH = Pattern.compile("(?:'([^']+)'|\"([^\"]+)\"|`([^`]+)`)");

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final String relPath;
        private final Consumer<GraphEvent> sink;
        private final Map<String, NodeKey> declarations = new LinkedHashMap<>();
        private final Set<String> seenImports = new HashSet<>();

        Walker(ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.relPath = relPath;
            this.sink = sink;
        }

        void walk(ParseTree node) {
            if (node instanceof TypeScriptParser.ClassDeclarationContext cd) {
                emitClass(cd);
            } else if (node instanceof TypeScriptParser.FunctionDeclarationContext fd) {
                emitFunction(fd);
            } else if (node instanceof TypeScriptParser.InterfaceDeclarationContext id) {
                emitInterface(id);
            } else if (node instanceof TypeScriptParser.ImportStatementContext is) {
                emitImport(is);
            } else if (node instanceof TypeScriptParser.ExpressionStatementContext es) {
                handleExpressionStatement(es);
            }
            for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i));
        }

        private void emitClass(TypeScriptParser.ClassDeclarationContext cd) {
            String name = cd.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey classKey = new NodeKey(ctx.projectId(), "Class", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", "class");
            props.put("startLine", lineOf(cd));
            props.put("endLine", endLineOf(cd));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(classKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", classKey, Map.of()));
            declarations.put(name, classKey);

            // Walk class body for methods.
            TypeScriptParser.ClassTailContext tail = cd.classTail();
            if (tail == null) return;
            for (TypeScriptParser.ClassElementContext el : tail.classElement()) {
                if (el.propertyMemberDeclaration() != null) {
                    TypeScriptParser.PropertyMemberDeclarationContext pmd = el.propertyMemberDeclaration();
                    if (pmd instanceof TypeScriptParser.MethodDeclarationExpressionContext mde) {
                        String mname = mde.propertyName().getText();
                        emitMethodNode(mname, classKey, mde);
                    }
                }
            }
        }

        private void emitMethodNode(String name, NodeKey classKey, ParserRuleContext src) {
            String fqName = classKey.fqName() + "." + name;
            NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(src));
            props.put("classId", classKey.id());
            sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(classKey, "CONTAINS", methodKey, Map.of()));
            declarations.putIfAbsent(name, methodKey);
        }

        private void emitFunction(TypeScriptParser.FunctionDeclarationContext fd) {
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
            declarations.putIfAbsent(name, key);
        }

        private void emitInterface(TypeScriptParser.InterfaceDeclarationContext id) {
            String name = id.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Interface", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", "interface");
            props.put("startLine", lineOf(id));
            props.put("endLine", endLineOf(id));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }

        private void emitImport(TypeScriptParser.ImportStatementContext is) {
            TypeScriptParser.ImportFromBlockContext block = is.importFromBlock();
            if (block == null) return;
            String target;
            if (block.StringLiteral() != null) {
                target = stripQuotes(block.StringLiteral().getText());
            } else if (block.importFrom() != null) {
                target = stripQuotes(block.importFrom().StringLiteral().getText());
            } else {
                return;
            }
            if (target == null || target.isBlank() || !seenImports.add(target)) return;
            NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(mod, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
        }

        private void handleExpressionStatement(TypeScriptParser.ExpressionStatementContext es) {
            // Look for express-style routes: <ident>.<verb>("/path", ...)
            String text = es.getText();
            int dot = text.indexOf('.');
            if (dot <= 0) return;
            // Identify the verb token after the dot, then look for an arguments rule under this statement.
            // We use the parse tree to find a string literal first argument.
            String head = text.substring(0, dot);
            int paren = text.indexOf('(', dot);
            if (paren < 0) return;
            String verb = text.substring(dot + 1, paren);
            int generic = verb.indexOf('<');
            if (generic >= 0) verb = verb.substring(0, generic);
            String verbLower = verb.toLowerCase(Locale.ROOT);
            Set<String> verbs = Set.of("get", "post", "put", "delete", "patch", "head", "options", "all");
            if (!verbs.contains(verbLower)) return;
            // Confirm head looks like a router identifier (heuristic).
            if (!head.matches("[A-Za-z_$][\\w$]*")) return;
            // Extract first string literal from the descendant string-literal tokens.
            String path = findFirstStringLiteralInChildren(es);
            if (path == null || path.isEmpty()) return;
            String httpMethod = verbLower.equals("all") ? "ANY" : verbLower.toUpperCase(Locale.ROOT);
            String endpointName = httpMethod + " " + path;
            NodeKey endpointKey = new NodeKey(ctx.projectId(), "ApiEndpoint", endpointName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", endpointName);
            props.put("fqName", endpointName);
            props.put("httpMethod", httpMethod);
            props.put("path", path);
            props.put("framework", "express");
            props.put("startLine", lineOf(es));
            sink.accept(new GraphEvent.NodeUpsert(endpointKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "EXPOSES", endpointKey, Map.of()));
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
            log.debug("TS ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
