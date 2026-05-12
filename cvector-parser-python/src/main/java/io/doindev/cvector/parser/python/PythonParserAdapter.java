package io.doindev.cvector.parser.python;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.python.internal.PythonLexer;
import io.doindev.cvector.parser.python.internal.PythonParser;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PythonParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(PythonParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("py", "pyi");

    @Override public String name() { return "python"; }

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
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "python");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        PythonLexer lexer = new PythonLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PythonParser parser = new PythonParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        PythonParser.File_inputContext tree;
        try {
            tree = parser.file_input();
        } catch (RuntimeException e) {
            log.warn("Python parse failed for {}: {}", file, e.getMessage());
            return;
        }

        Walker w = new Walker(ctx, fileKey, relPath, sink);
        w.walk(tree, null);
        w.emitCalls(tree);
    }

    private static final class Walker {

        private static final Pattern HTTP_VERB = Pattern.compile(
                "(get|post|put|delete|patch|head|options|websocket)", Pattern.CASE_INSENSITIVE);
        private static final Pattern PATH_LITERAL = Pattern.compile(
                "[\"']([^\"']+)[\"']");

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final String relPath;
        private final Consumer<GraphEvent> sink;
        private final Map<String, NodeKey> declarations = new LinkedHashMap<>();
        private final Map<NodeKey, ParserRuleContext> functionBodies = new LinkedHashMap<>();
        private final Map<NodeKey, ParserRuleContext> classBodies = new LinkedHashMap<>();

        Walker(ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.relPath = relPath;
            this.sink = sink;
        }

        void walk(ParseTree node, NodeKey enclosing) {
            if (node instanceof PythonParser.Class_defContext cd) {
                NodeKey classKey = emitClass(cd, enclosing);
                classBodies.put(classKey, cd);
                for (int i = 0; i < cd.getChildCount(); i++) walk(cd.getChild(i), classKey);
                return;
            }
            if (node instanceof PythonParser.Function_defContext fd) {
                NodeKey methodKey = emitFunction(fd, enclosing);
                functionBodies.put(methodKey, fd);
                emitRouteFromDecorators(fd, methodKey);
                for (int i = 0; i < fd.getChildCount(); i++) walk(fd.getChild(i), methodKey);
                return;
            }
            if (node instanceof PythonParser.Import_nameContext in) {
                emitImportName(in);
                return;
            }
            if (node instanceof PythonParser.Import_fromContext ifrom) {
                emitImportFrom(ifrom);
                return;
            }
            for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), enclosing);
        }

        private NodeKey emitClass(PythonParser.Class_defContext cd, NodeKey enclosing) {
            PythonParser.Class_def_rawContext raw = cd.class_def_raw();
            String name = raw.name().getText();
            String fqName = qualifiedName(name, enclosing);
            NodeKey key = new NodeKey(ctx.projectId(), "Class", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(cd));
            props.put("endLine", endLineOf(cd));
            props.put("fileId", fileKey.id());
            String bases = baseClasses(raw);
            String kind = detectClassKind(bases);
            props.put("kind", kind);
            String decs = decoratorNames(cd.decorators());
            if (decs != null) {
                props.put("decorators", decs);
                if (decs.contains("dataclass")) props.put("isDataclass", true);
                if (decs.contains("final")) props.put("isFinal", true);
            }
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            NodeKey owner = enclosing != null ? enclosing : fileKey;
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", key, Map.of()));
            emitBaseClassEdges(raw, key);
            declarations.put(name, key);
            return key;
        }

        private void emitBaseClassEdges(PythonParser.Class_def_rawContext raw, NodeKey classKey) {
            PythonParser.ArgumentsContext args = raw.arguments();
            if (args == null) return;
            for (PythonParser.ArgsContext arg : args.getRuleContexts(PythonParser.ArgsContext.class)) {
                for (ParseTree c : children(arg)) {
                    if (c instanceof PythonParser.ExpressionContext ex) emitBaseRef(ex.getText(), classKey);
                }
            }
            // Some grammar versions surface expressions directly under arguments.
            for (PythonParser.ExpressionContext ex : args.getRuleContexts(PythonParser.ExpressionContext.class)) {
                emitBaseRef(ex.getText(), classKey);
            }
        }

        private void emitBaseRef(String name, NodeKey classKey) {
            if (name == null) return;
            int paren = name.indexOf('(');
            if (paren > 0) name = name.substring(0, paren);
            name = name.trim();
            if (name.isEmpty() || "object".equals(name)) return;
            NodeKey target = new NodeKey(ctx.projectId(), "Class", name);
            Map<String, Object> stub = new HashMap<>();
            stub.put("name", name);
            stub.put("fqName", name);
            sink.accept(new GraphEvent.NodeUpsert(target, stub));
            sink.accept(new GraphEvent.EdgeUpsert(classKey, "EXTENDS", target, Map.of()));
        }

        private static String baseClasses(PythonParser.Class_def_rawContext raw) {
            PythonParser.ArgumentsContext args = raw.arguments();
            return args == null ? null : args.getText();
        }

        private static String detectClassKind(String bases) {
            if (bases == null) return "class";
            String b = bases.toLowerCase();
            if (b.contains("enum")) return "enum";
            if (b.contains("protocol")) return "protocol";
            if (b.contains("typeddict")) return "typeddict";
            if (b.contains("exception") || b.endsWith("error")) return "exception";
            return "class";
        }

        private NodeKey emitFunction(PythonParser.Function_defContext fd, NodeKey enclosing) {
            PythonParser.Function_def_rawContext raw = fd.function_def_raw();
            String name = raw.name().getText();
            boolean isAsync = raw.getStart() != null && "async".equals(raw.getStart().getText());

            String fqName;
            NodeKey container;
            if (enclosing != null && "Class".equals(enclosing.label())) {
                fqName = enclosing.fqName() + "." + name;
                container = enclosing;
            } else {
                fqName = qualifiedName(name, enclosing);
                container = enclosing != null ? enclosing : fileKey;
            }
            NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);

            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(fd));
            props.put("endLine", endLineOf(fd));
            props.put("fileId", fileKey.id());
            if (enclosing != null && "Class".equals(enclosing.label())) props.put("classId", enclosing.id());
            if (isAsync) props.put("isAsync", true);
            if (name.startsWith("test_") || name.startsWith("Test")) props.put("isTest", true);
            if (name.startsWith("__") && name.endsWith("__")) props.put("visibility", "public");
            else if (name.startsWith("__")) props.put("visibility", "private");
            else if (name.startsWith("_")) props.put("visibility", "private");
            else props.put("visibility", "public");

            String decs = decoratorNames(fd.decorators());
            if (decs != null) {
                props.put("decorators", decs);
                if (decs.contains("pytest.fixture")) props.put("isFixture", true);
                if (decs.contains("pytest.mark")) props.put("isTest", true);
                if (decs.contains("staticmethod")) props.put("isStatic", true);
                if (decs.contains("classmethod")) props.put("isClassmethod", true);
                if (decs.contains("property")) props.put("isProperty", true);
            }

            sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(container, "CONTAINS", methodKey, Map.of()));
            declarations.putIfAbsent(name, methodKey);
            return methodKey;
        }

        private static String decoratorNames(PythonParser.DecoratorsContext decs) {
            if (decs == null) return null;
            StringBuilder sb = new StringBuilder();
            for (PythonParser.Named_expressionContext ne :
                    decs.getRuleContexts(PythonParser.Named_expressionContext.class)) {
                if (sb.length() > 0) sb.append(',');
                sb.append(stripCallArgs(ne.getText()));
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static String stripCallArgs(String decoratorExpr) {
            int paren = decoratorExpr.indexOf('(');
            return paren >= 0 ? decoratorExpr.substring(0, paren) : decoratorExpr;
        }

        private void emitImportName(PythonParser.Import_nameContext in) {
            for (PythonParser.Dotted_as_nameContext n :
                    in.dotted_as_names().getRuleContexts(PythonParser.Dotted_as_nameContext.class)) {
                String mod = n.dotted_name().getText();
                emitImport(mod);
            }
        }

        private void emitImportFrom(PythonParser.Import_fromContext ifrom) {
            PythonParser.Dotted_nameContext dotted = ifrom.dotted_name();
            String mod = dotted != null ? dotted.getText() : "";
            int dots = 0;
            for (ParseTree c : children(ifrom)) {
                if (c instanceof TerminalNode tn) {
                    String t = tn.getText();
                    if (".".equals(t)) dots++;
                    else if ("...".equals(t)) dots += 3;
                }
            }
            String full = dots == 0 ? mod : ".".repeat(dots) + mod;
            if (!full.isEmpty()) emitImport(full);
        }

        private final Set<String> seenImports = new HashSet<>();

        private void emitImport(String target) {
            if (target == null || target.isBlank() || !seenImports.add(target)) return;
            NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(mod, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
        }

        private void emitRouteFromDecorators(PythonParser.Function_defContext fd, NodeKey methodKey) {
            PythonParser.DecoratorsContext decs = fd.decorators();
            if (decs == null) return;
            for (PythonParser.Named_expressionContext ne :
                    decs.getRuleContexts(PythonParser.Named_expressionContext.class)) {
                String txt = ne.getText();
                String[] route = extractRoute(txt);
                if (route == null) continue;
                String httpMethod = route[0];
                String path = route[1];
                String framework = route[2];
                String endpointName = httpMethod + " " + path;
                NodeKey endpoint = new NodeKey(ctx.projectId(), "ApiEndpoint", endpointName);
                Map<String, Object> props = new HashMap<>();
                props.put("name", endpointName);
                props.put("fqName", endpointName);
                props.put("httpMethod", httpMethod);
                props.put("path", path);
                props.put("framework", framework);
                props.put("startLine", lineOf(decs));
                sink.accept(new GraphEvent.NodeUpsert(endpoint, props));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "EXPOSES", endpoint, Map.of()));
                sink.accept(new GraphEvent.EdgeUpsert(endpoint, "HANDLES", methodKey, Map.of()));
            }
        }

        private static String[] extractRoute(String decoratorText) {
            // @app.get("/x"), @router.post("/x"), @app.route("/x", methods=[...])
            int paren = decoratorText.indexOf('(');
            if (paren <= 0) return null;
            String head = decoratorText.substring(0, paren);
            int dot = head.lastIndexOf('.');
            if (dot < 0) return null;
            String verb = head.substring(dot + 1);
            String args = decoratorText.substring(paren + 1, decoratorText.lastIndexOf(')'));
            Matcher pm = PATH_LITERAL.matcher(args);
            if (!pm.find()) return null;
            String path = pm.group(1);
            if (!path.startsWith("/")) return null;

            if (verb.equalsIgnoreCase("route")) {
                Matcher methodsMatch = Pattern.compile("methods\\s*=\\s*\\[([^\\]]+)\\]").matcher(args);
                if (methodsMatch.find()) {
                    Matcher firstVerb = HTTP_VERB.matcher(methodsMatch.group(1));
                    if (firstVerb.find()) return new String[]{firstVerb.group(1).toUpperCase(), path, "flask"};
                }
                return new String[]{"GET", path, "flask"};
            }

            Matcher vm = HTTP_VERB.matcher(verb);
            if (vm.matches()) return new String[]{verb.toUpperCase(), path, "flask-fastapi"};
            return null;
        }

        void emitCalls(PythonParser.File_inputContext root) {
            for (Map.Entry<NodeKey, ParserRuleContext> e : functionBodies.entrySet()) {
                NodeKey owner = e.getKey();
                walkCalls(e.getValue(), owner);
            }
        }

        private void walkCalls(ParseTree node, NodeKey owner) {
            if (node instanceof PythonParser.PrimaryContext p) {
                visitPrimary(p, owner);
            }
            for (int i = 0; i < node.getChildCount(); i++) walkCalls(node.getChild(i), owner);
        }

        private void visitPrimary(PythonParser.PrimaryContext p, NodeKey owner) {
            // primary: primary ('.' name | genexp | '(' arguments? ')' | '[' slices ']') | atom
            // Detect call: primary children = primary '(' arguments? ')'
            if (p.getChildCount() < 2) return;
            ParseTree first = p.getChild(0);
            String secondText = p.getChild(1) instanceof TerminalNode ? p.getChild(1).getText() : null;
            if (!"(".equals(secondText)) return;
            String callee = callableName(first);
            if (callee == null) return;
            NodeKey target = declarations.get(callee);
            if (target == null || !"Method".equals(target.label()) || target.equals(owner)) return;
            Map<String, Object> props = new HashMap<>();
            props.put("confidence", 0.7);
            props.put("callSiteLine", lineOf(p));
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CALLS", target, props));
        }

        private static String callableName(ParseTree node) {
            String text = node.getText();
            // Strip trailing whitespace and only keep last identifier-like token.
            int lastDot = text.lastIndexOf('.');
            String tail = lastDot >= 0 ? text.substring(lastDot + 1) : text;
            // Tail must be a bare identifier.
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_')) return null;
            }
            return tail.isEmpty() ? null : tail;
        }

        private String qualifiedName(String name, NodeKey enclosing) {
            if (enclosing == null || enclosing.equals(fileKey)) return relPath + "::" + name;
            return enclosing.fqName() + "." + name;
        }

        private static int lineOf(ParserRuleContext c) {
            Token t = c.getStart();
            return t != null ? t.getLine() : 0;
        }

        private static int endLineOf(ParserRuleContext c) {
            Token t = c.getStop();
            return t != null ? t.getLine() : 0;
        }

        private static List<ParseTree> children(ParserRuleContext c) {
            return c.children != null ? c.children : List.of();
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            log.debug("Python ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
