package io.doindev.cvector.parser.go;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.go.internal.GoLexer;
import io.doindev.cvector.parser.go.internal.GoParser;
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

public class GoParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(GoParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("go");

    @Override public String name() { return "go"; }

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
        fileProps.put("language", "go");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        GoLexer lexer = new GoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        GoParser parser = new GoParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        GoParser.SourceFileContext tree;
        try {
            tree = parser.sourceFile();
        } catch (RuntimeException e) {
            log.warn("Go parse failed for {}: {}", file, e.getMessage());
            return;
        }

        new Walker(ctx, fileKey, sink).walk(tree);
    }

    private static final class Walker {

        private static final Pattern HTTP_VERB = Pattern.compile(
                "(get|post|put|delete|patch|head|options|handle|handlefunc|all|any)",
                Pattern.CASE_INSENSITIVE);

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final Consumer<GraphEvent> sink;
        private final Map<String, NodeKey> typeFqs = new LinkedHashMap<>();
        private final Map<String, NodeKey> declarations = new LinkedHashMap<>();
        private String pkg;
        private NodeKey pkgKey;

        Walker(ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.sink = sink;
        }

        void walk(GoParser.SourceFileContext sf) {
            collectPackage(sf);
            collectImports(sf);
            collectTypeDecls(sf);
            collectFunctions(sf);
        }

        private void collectPackage(GoParser.SourceFileContext sf) {
            if (sf.packageClause() == null) return;
            pkg = sf.packageClause().packageName().getText();
            pkgKey = new NodeKey(ctx.projectId(), "GoPackage", pkg);
            Map<String, Object> props = new HashMap<>();
            props.put("name", pkg);
            props.put("fqName", pkg);
            sink.accept(new GraphEvent.NodeUpsert(pkgKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", pkgKey, Map.of()));
            Map<String, Object> fp = new HashMap<>();
            fp.put("package", pkg);
        }

        private void collectImports(GoParser.SourceFileContext sf) {
            Set<String> seen = new HashSet<>();
            for (GoParser.ImportDeclContext id : sf.importDecl()) {
                for (GoParser.ImportSpecContext s : id.importSpec()) {
                    String pathLit = s.importPath().string_().getText();
                    String target = unquote(pathLit);
                    if (target == null || target.isBlank() || !seen.add(target)) continue;
                    NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
                    Map<String, Object> props = new HashMap<>();
                    props.put("name", target);
                    props.put("fqName", target);
                    sink.accept(new GraphEvent.NodeUpsert(mod, props));
                    sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
                }
            }
        }

        private void collectTypeDecls(GoParser.SourceFileContext sf) {
            for (GoParser.FunctionDeclContext ignore : sf.functionDecl()) { /* type pass first */ }
            for (ParseTree top : children(sf)) {
                if (top instanceof GoParser.DeclarationContext d && d.typeDecl() != null) {
                    for (GoParser.TypeSpecContext ts : d.typeDecl().typeSpec()) {
                        if (ts.typeDef() == null) continue;
                        GoParser.TypeDefContext td = ts.typeDef();
                        String name = td.IDENTIFIER().getText();
                        GoParser.Type_Context typeCtx = td.type_();
                        if (typeCtx == null) continue;
                        emitTypeDef(name, typeCtx, td);
                    }
                }
            }
        }

        private void emitTypeDef(String name, GoParser.Type_Context typeCtx, GoParser.TypeDefContext td) {
            String fqName = pkg != null ? pkg + "." + name : name;
            String label = null;
            String kind = null;
            GoParser.TypeLitContext tl = typeCtx.typeLit();
            if (tl != null) {
                if (tl.structType() != null) { label = "Struct"; kind = "struct"; }
                else if (tl.interfaceType() != null) { label = "Interface"; kind = "interface"; }
            }
            if (label == null) return;

            NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", kind);
            props.put("startLine", lineOf(td));
            props.put("endLine", endLineOf(td));
            props.put("fileId", fileKey.id());
            if (pkg != null) props.put("package", pkg);
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            NodeKey scope = pkgKey != null ? pkgKey : fileKey;
            sink.accept(new GraphEvent.EdgeUpsert(scope, "CONTAINS", key, Map.of()));
            typeFqs.put(name, key);
            declarations.put(name, key);
        }

        private void collectFunctions(GoParser.SourceFileContext sf) {
            for (GoParser.FunctionDeclContext fd : sf.functionDecl()) {
                String name = fd.IDENTIFIER().getText();
                emitFunction(name, fd, null);
            }
            for (GoParser.MethodDeclContext md : sf.methodDecl()) {
                String name = md.IDENTIFIER().getText();
                String receiver = receiverType(md.receiver());
                emitFunction(name, md, receiver);
            }
        }

        private NodeKey emitFunction(String name, ParserRuleContext fd, String receiver) {
            String fqName;
            NodeKey owner;
            if (receiver != null) {
                String typeFq = pkg != null ? pkg + "." + receiver : receiver;
                fqName = typeFq + "." + name;
                owner = typeFqs.getOrDefault(receiver,
                        new NodeKey(ctx.projectId(), "Struct", typeFq));
            } else {
                fqName = pkg != null ? pkg + "." + name : name;
                owner = pkgKey != null ? pkgKey : fileKey;
            }
            NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(fd));
            props.put("endLine", endLineOf(fd));
            props.put("fileId", fileKey.id());
            if (receiver != null) props.put("receiver", receiver);
            if (name.startsWith("Test") || name.startsWith("Benchmark") || name.startsWith("Example"))
                props.put("isTest", true);
            if ("main".equals(name)) props.put("isEntry", true);
            sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", methodKey, Map.of()));
            declarations.putIfAbsent(name, methodKey);

            // Walk body for HTTP routes (router.GET("/x", h)) and internal calls.
            scanBody(fd, methodKey);
            return methodKey;
        }

        private static String receiverType(GoParser.ReceiverContext rcv) {
            if (rcv == null) return null;
            GoParser.ParametersContext params = rcv.parameters();
            if (params == null) return null;
            for (GoParser.ParameterDeclContext p : params.parameterDecl()) {
                GoParser.Type_Context t = p.type_();
                if (t == null) continue;
                String text = t.getText();
                if (text.startsWith("*")) text = text.substring(1);
                return text;
            }
            return null;
        }

        private void scanBody(ParserRuleContext fd, NodeKey owner) {
            scanRoutes(fd, owner);
            scanCalls(fd, owner);
        }

        private void scanRoutes(ParserRuleContext fd, NodeKey owner) {
            for (ParseTree node : descendants(fd)) {
                if (!(node instanceof GoParser.PrimaryExprContext pe)) continue;
                if (pe.getChildCount() < 2) continue;
                ParseTree last = pe.getChild(pe.getChildCount() - 1);
                if (!(last instanceof GoParser.ArgumentsContext args)) continue;
                ParseTree base = pe.getChild(pe.getChildCount() - 2);
                String verbToken = null;
                if (base instanceof TerminalNode tn && tn.getSymbol().getType() == GoParser.IDENTIFIER) {
                    verbToken = tn.getText();
                } else if (base instanceof GoParser.MethodExprContext me) {
                    verbToken = me.IDENTIFIER().getText();
                }
                if (verbToken == null) continue;
                Matcher vm = HTTP_VERB.matcher(verbToken);
                if (!vm.matches()) continue;

                GoParser.ExpressionListContext exprList = args.expressionList();
                if (exprList == null) continue;
                String firstArgText = exprList.expression(0) == null ? null : exprList.expression(0).getText();
                if (firstArgText == null) continue;
                String path = stripQuotes(firstArgText);
                if (path == null || !(path.startsWith("/") || path.startsWith("http"))) continue;

                String httpMethod = switch (verbToken.toLowerCase(Locale.ROOT)) {
                    case "handle", "handlefunc", "all", "any" -> "ANY";
                    default -> verbToken.toUpperCase(Locale.ROOT);
                };
                String endpointName = httpMethod + " " + path;
                NodeKey endpointKey = new NodeKey(ctx.projectId(), "ApiEndpoint", endpointName);
                Map<String, Object> props = new HashMap<>();
                props.put("name", endpointName);
                props.put("fqName", endpointName);
                props.put("httpMethod", httpMethod);
                props.put("path", path);
                props.put("framework", "go-http");
                props.put("startLine", lineOf(pe));
                sink.accept(new GraphEvent.NodeUpsert(endpointKey, props));
                sink.accept(new GraphEvent.EdgeUpsert(owner, "EXPOSES", endpointKey, Map.of()));
            }
        }

        private void scanCalls(ParserRuleContext fd, NodeKey owner) {
            for (ParseTree node : descendants(fd)) {
                if (!(node instanceof GoParser.PrimaryExprContext pe)) continue;
                if (pe.getChildCount() < 2) continue;
                ParseTree last = pe.getChild(pe.getChildCount() - 1);
                if (!(last instanceof GoParser.ArgumentsContext)) continue;
                ParseTree base = pe.getChild(0);
                String callee = bareCallee(base);
                if (callee == null) continue;
                NodeKey target = declarations.get(callee);
                if (target == null || !"Method".equals(target.label()) || target.equals(owner)) continue;
                Map<String, Object> props = new HashMap<>();
                props.put("confidence", 0.7);
                props.put("callSiteLine", lineOf(pe));
                sink.accept(new GraphEvent.EdgeUpsert(owner, "CALLS", target, props));
            }
        }


        private static String bareCallee(ParseTree base) {
            String text = base.getText();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_')) return null;
            }
            return text;
        }

        private static String unquote(String s) {
            if (s == null) return null;
            if (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                    || (s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`'))) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }

        private static String stripQuotes(String s) {
            String u = unquote(s);
            return u;
        }

        private static int lineOf(ParserRuleContext c) {
            Token t = c.getStart();
            return t != null ? t.getLine() : 0;
        }

        private static int endLineOf(ParserRuleContext c) {
            Token t = c.getStop();
            return t != null ? t.getLine() : 0;
        }

        private static java.util.List<ParseTree> children(ParserRuleContext c) {
            return c.children != null ? c.children : java.util.List.of();
        }

        private static Iterable<ParseTree> descendants(ParseTree root) {
            java.util.LinkedHashSet<ParseTree> out = new java.util.LinkedHashSet<>();
            walkDescendants(root, out);
            return out;
        }

        private static void walkDescendants(ParseTree node, Set<ParseTree> out) {
            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree c = node.getChild(i);
                out.add(c);
                walkDescendants(c, out);
            }
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            log.debug("Go ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
