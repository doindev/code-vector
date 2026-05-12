package io.doindev.cvector.parser.csharp;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.csharp.internal.CSharpLexer;
import io.doindev.cvector.parser.csharp.internal.CSharpParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CSharpParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(CSharpParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("cs");
    private static final Pattern FILE_SCOPED_NS = Pattern.compile(
            "(?m)^\\s*namespace\\s+([\\w.]+)\\s*;");
    private static final Pattern POSITIONAL_RECORD = Pattern.compile(
            "(?m)^(\\s*)(?:public\\s+|internal\\s+|sealed\\s+|abstract\\s+|partial\\s+)*"
                    + "record\\s+([A-Za-z_][\\w]*)\\s*(?:<[^>]+>)?\\s*\\(([^)]*)\\)\\s*;");

    @Override public String name() { return "csharp"; }

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
        Preprocessed pp = preprocess(source);
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        NodeKey fileKey = emitFileNode(ctx, relPath, source, sink);
        emitRecords(pp.records, ctx, fileKey, sink);

        CSharpParser.Compilation_unitContext tree = parseTree(pp.rewritten, file);
        if (tree != null) new Walker(ctx, fileKey, sink).walk(tree);
    }

    private record Preprocessed(String rewritten, java.util.List<RecordSite> records) {}

    /** Rewrites C# 10 file-scoped namespaces into block-scoped form and lifts positional records out of the
     *  source so the v7 ANTLR grammar can parse the remainder. */
    private static Preprocessed preprocess(String source) {
        String rewritten = source;
        Matcher fsn = FILE_SCOPED_NS.matcher(source);
        String fileScopedNs = null;
        if (fsn.find()) {
            fileScopedNs = fsn.group(1);
            rewritten = source.substring(0, fsn.start())
                    + "namespace " + fileScopedNs + " {\n"
                    + source.substring(fsn.end())
                    + "\n}\n";
        }
        java.util.List<RecordSite> records = new java.util.ArrayList<>();
        Matcher rm = POSITIONAL_RECORD.matcher(rewritten);
        StringBuilder out = new StringBuilder();
        int lastIdx = 0;
        while (rm.find()) {
            int line = countLinesUpTo(rewritten, rm.start()) + 1;
            records.add(new RecordSite(rm.group(2), fileScopedNs, line));
            out.append(rewritten, lastIdx, rm.start());
            for (int i = rm.start(); i < rm.end(); i++) {
                char c = rewritten.charAt(i);
                out.append(c == '\n' ? '\n' : ' ');
            }
            lastIdx = rm.end();
        }
        out.append(rewritten, lastIdx, rewritten.length());
        return new Preprocessed(out.toString(), records);
    }

    private static NodeKey emitFileNode(ProjectContext ctx, String relPath, String source, Consumer<GraphEvent> sink) {
        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "csharp");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));
        return fileKey;
    }

    private static void emitRecords(java.util.List<RecordSite> records, ProjectContext ctx, NodeKey fileKey,
                                     Consumer<GraphEvent> sink) {
        for (RecordSite r : records) {
            String fq = r.namespace != null ? r.namespace + "." + r.name : r.name;
            NodeKey key = new NodeKey(ctx.projectId(), "Record", fq);
            Map<String, Object> props = new HashMap<>();
            props.put("name", r.name);
            props.put("fqName", fq);
            props.put("kind", "record");
            props.put("startLine", r.line);
            props.put("fileId", fileKey.id());
            if (r.namespace != null) props.put("namespace", r.namespace);
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            NodeKey owner = r.namespace != null
                    ? new NodeKey(ctx.projectId(), "Namespace", r.namespace)
                    : fileKey;
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", key, Map.of()));
        }
    }

    private static CSharpParser.Compilation_unitContext parseTree(String src, Path file) {
        CSharpLexer lexer = new CSharpLexer(CharStreams.fromString(src));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CSharpParser parser = new CSharpParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);
        try {
            return parser.compilation_unit();
        } catch (RuntimeException e) {
            log.warn("C# parse failed for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private record RecordSite(String name, String namespace, int line) {}

    private static int countLinesUpTo(String text, int idx) {
        int n = 0;
        int upTo = Math.min(idx, text.length());
        for (int i = 0; i < upTo; i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private static final class Walker {

        private static final Set<String> HTTP_ATTRS = Set.of(
                "HttpGet", "HttpPost", "HttpPut", "HttpDelete", "HttpPatch",
                "HttpHead", "HttpOptions");

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final Consumer<GraphEvent> sink;
        private final Deque<String> nsStack = new ArrayDeque<>();
        private final Map<String, NodeKey> declarations = new LinkedHashMap<>();
        private final Set<String> seenImports = new HashSet<>();

        Walker(ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.sink = sink;
        }

        void walk(CSharpParser.Compilation_unitContext cu) {
            if (cu.using_directives() != null) {
                for (CSharpParser.Using_directiveContext ud : cu.using_directives().using_directive()) emitUsing(ud);
            }
            if (cu.namespace_member_declarations() != null) {
                for (CSharpParser.Namespace_member_declarationContext nmd :
                        cu.namespace_member_declarations().namespace_member_declaration()) {
                    handleNamespaceMember(nmd);
                }
            }
        }

        private void handleNamespaceMember(CSharpParser.Namespace_member_declarationContext nmd) {
            if (nmd.namespace_declaration() != null) {
                handleNamespace(nmd.namespace_declaration());
            } else if (nmd.type_declaration() != null) {
                handleTypeDecl(nmd.type_declaration(), null);
            }
        }

        private void handleNamespace(CSharpParser.Namespace_declarationContext nd) {
            String name = nd.qualified_identifier().getText();
            NodeKey nsKey = new NodeKey(ctx.projectId(), "Namespace", name);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", name);
            props.put("startLine", lineOf(nd));
            sink.accept(new GraphEvent.NodeUpsert(nsKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", nsKey, Map.of()));

            nsStack.push(name);
            try {
                CSharpParser.Namespace_bodyContext body = nd.namespace_body();
                for (CSharpParser.Using_directiveContext ud : body.using_directives() != null
                        ? body.using_directives().using_directive() : java.util.List.<CSharpParser.Using_directiveContext>of()) {
                    emitUsing(ud);
                }
                CSharpParser.Namespace_member_declarationsContext members = body.namespace_member_declarations();
                if (members != null) {
                    for (CSharpParser.Namespace_member_declarationContext nmd : members.namespace_member_declaration()) {
                        handleNamespaceMember(nmd);
                    }
                }
            } finally {
                nsStack.pop();
            }
        }

        private void emitUsing(CSharpParser.Using_directiveContext ud) {
            String target;
            if (ud instanceof CSharpParser.UsingAliasDirectiveContext alias) {
                target = alias.namespace_or_type_name().getText();
            } else if (ud instanceof CSharpParser.UsingNamespaceDirectiveContext nsu) {
                target = nsu.namespace_or_type_name().getText();
            } else if (ud instanceof CSharpParser.UsingStaticDirectiveContext stat) {
                target = stat.namespace_or_type_name().getText();
            } else {
                return;
            }
            if (target == null || target.isBlank() || !seenImports.add(target)) return;
            NodeKey mod = new NodeKey(ctx.projectId(), "Namespace", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(mod, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
        }

        private void handleTypeDecl(CSharpParser.Type_declarationContext td, NodeKey enclosingType) {
            String classRoute = extractRouteFromAttributes(td.attributes());
            if (td.class_definition() != null) {
                handleClass(td.class_definition(), td.attributes(), classRoute);
            } else if (td.struct_definition() != null) {
                handleStruct(td.struct_definition());
            } else if (td.interface_definition() != null) {
                handleInterface(td.interface_definition());
            } else if (td.enum_definition() != null) {
                handleEnum(td.enum_definition());
            }
            // delegate_definition omitted — not a top-level container for our model.
        }

        private void handleClass(CSharpParser.Class_definitionContext cd, CSharpParser.AttributesContext attrs,
                                  String classRoute) {
            String name = cd.identifier().getText();
            String fqName = qualified(name);
            NodeKey key = emitTypeNode("Class", name, fqName, "class", cd, ctxNs());
            emitClassBaseEdges(cd.class_base(), key);
            // Determine: looks like an ASP.NET controller? (has [Route] attribute or name ends with Controller)
            String effectiveBase = classRoute != null ? classRoute : (name.endsWith("Controller") ? "" : null);
            walkClassBody(cd.class_body(), key, effectiveBase);
        }

        private void handleStruct(CSharpParser.Struct_definitionContext sd) {
            String name = sd.identifier().getText();
            emitTypeNode("Struct", name, qualified(name), "struct", sd, ctxNs());
            // Struct body iteration omitted for brevity (no routes inside structs typically).
        }

        private void handleInterface(CSharpParser.Interface_definitionContext id) {
            String name = id.identifier().getText();
            emitTypeNode("Interface", name, qualified(name), "interface", id, ctxNs());
        }

        private void handleEnum(CSharpParser.Enum_definitionContext ed) {
            String name = ed.identifier().getText();
            emitTypeNode("Enum", name, qualified(name), "enum", ed, ctxNs());
        }

        // C# 9+ `record Person(...)` is parsed by grammars-v4 v7 grammar as an identifier `record` + class. We
        // detect that via a small fallback: if a class_definition's name token is `record`, treat the body
        // identifier as the record name.
        private NodeKey emitTypeNode(String label, String name, String fqName, String kind, ParserRuleContext src,
                                      String namespaceName) {
            // The grammars-v4 v7 grammar predates C# 9 records; treat positional records by scanning for
            // `record` keyword in compilation_unit text — but for simplicity we emit Class with kind=record
            // upstream. Here we just trust the label passed in.
            NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", kind);
            props.put("startLine", lineOf(src));
            props.put("endLine", endLineOf(src));
            props.put("fileId", fileKey.id());
            if (namespaceName != null) props.put("namespace", namespaceName);
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            NodeKey owner = enclosingScope();
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", key, Map.of()));
            declarations.putIfAbsent(name, key);
            return key;
        }

        private NodeKey enclosingScope() {
            if (nsStack.isEmpty()) return fileKey;
            return new NodeKey(ctx.projectId(), "Namespace", nsStack.peek());
        }

        private String ctxNs() { return nsStack.isEmpty() ? null : nsStack.peek(); }

        private void emitClassBaseEdges(CSharpParser.Class_baseContext base, NodeKey typeKey) {
            if (base == null) return;
            // class_base: ':' class_type (',' namespace_or_type_name)*
            if (base.class_type() != null) {
                emitInheritEdge(base.class_type().getText(), typeKey, "EXTENDS", "Class");
            }
            for (CSharpParser.Namespace_or_type_nameContext n : base.namespace_or_type_name()) {
                String name = n.getText();
                String simple = simpleName(name);
                String edgeLabel = looksLikeInterface(simple) ? "Interface" : "Class";
                String edgeType = looksLikeInterface(simple) ? "IMPLEMENTS" : "EXTENDS";
                emitInheritEdge(simple, typeKey, edgeType, edgeLabel);
            }
        }

        private void emitInheritEdge(String name, NodeKey from, String edgeType, String label) {
            if (name == null) return;
            String simple = simpleName(name);
            int generic = simple.indexOf('<');
            if (generic >= 0) simple = simple.substring(0, generic);
            if (simple.isEmpty()) return;
            NodeKey target = new NodeKey(ctx.projectId(), label, simple);
            Map<String, Object> stub = new HashMap<>();
            stub.put("name", simple);
            stub.put("fqName", simple);
            sink.accept(new GraphEvent.NodeUpsert(target, stub));
            sink.accept(new GraphEvent.EdgeUpsert(from, edgeType, target, Map.of()));
        }

        private static String simpleName(String dotted) {
            int dot = dotted.lastIndexOf('.');
            return dot >= 0 ? dotted.substring(dot + 1) : dotted;
        }

        private static boolean looksLikeInterface(String name) {
            return name.length() > 1 && name.charAt(0) == 'I' && Character.isUpperCase(name.charAt(1));
        }

        private void walkClassBody(CSharpParser.Class_bodyContext body, NodeKey typeKey, String baseRoute) {
            if (body == null || body.class_member_declarations() == null) return;
            for (CSharpParser.Class_member_declarationContext m : body.class_member_declarations().class_member_declaration()) {
                handleClassMember(m, typeKey, baseRoute);
            }
        }

        private void handleClassMember(CSharpParser.Class_member_declarationContext m, NodeKey typeKey, String baseRoute) {
            CSharpParser.Common_member_declarationContext common = m.common_member_declaration();
            if (common == null) return;
            String methodName = null;
            ParserRuleContext methodCtx = null;

            if (common.method_declaration() != null) {
                methodName = methodNameOf(common.method_declaration());
                methodCtx = common.method_declaration();
            } else if (common.typed_member_declaration() != null
                    && common.typed_member_declaration().method_declaration() != null) {
                methodName = methodNameOf(common.typed_member_declaration().method_declaration());
                methodCtx = common.typed_member_declaration().method_declaration();
            } else if (common.constructor_declaration() != null) {
                methodName = common.constructor_declaration().identifier().getText();
                methodCtx = common.constructor_declaration();
            } else if (common.class_definition() != null) {
                handleClass(common.class_definition(), null, null);
                return;
            } else if (common.struct_definition() != null) {
                handleStruct(common.struct_definition());
                return;
            } else if (common.interface_definition() != null) {
                handleInterface(common.interface_definition());
                return;
            } else if (common.enum_definition() != null) {
                handleEnum(common.enum_definition());
                return;
            }

            if (methodName == null || methodCtx == null) return;
            String fqName = typeKey.fqName() + "." + methodName;
            NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", methodName);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(methodCtx));
            props.put("endLine", endLineOf(methodCtx));
            props.put("fileId", fileKey.id());
            props.put("classId", typeKey.id());
            sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(typeKey, "CONTAINS", methodKey, Map.of()));
            declarations.putIfAbsent(methodName, methodKey);

            emitRoutesFromAttributes(m.attributes(), typeKey, methodKey, baseRoute, lineOf(methodCtx));
        }

        private static String methodNameOf(CSharpParser.Method_declarationContext md) {
            return md.method_member_name().getText();
        }

        private String extractRouteFromAttributes(CSharpParser.AttributesContext attrs) {
            if (attrs == null) return null;
            for (CSharpParser.Attribute_sectionContext sec : attrs.attribute_section()) {
                for (CSharpParser.AttributeContext at : sec.attribute_list().attribute()) {
                    String name = at.namespace_or_type_name().getText();
                    if (!"Route".equals(name)) continue;
                    String pathArg = firstStringLiteralArg(at);
                    if (pathArg != null) return pathArg;
                }
            }
            return null;
        }

        private void emitRoutesFromAttributes(CSharpParser.AttributesContext attrs, NodeKey typeKey,
                                               NodeKey methodKey, String baseRoute, int line) {
            if (attrs == null) return;
            for (CSharpParser.Attribute_sectionContext sec : attrs.attribute_section()) {
                for (CSharpParser.AttributeContext at : sec.attribute_list().attribute()) {
                    String name = at.namespace_or_type_name().getText();
                    if (!HTTP_ATTRS.contains(name)) continue;
                    String httpMethod = name.substring(4).toUpperCase(Locale.ROOT);
                    String routePart = firstStringLiteralArg(at);
                    String path = combinePath(baseRoute, routePart);
                    String endpointName = httpMethod + " " + path;
                    NodeKey endpointKey = new NodeKey(ctx.projectId(), "ApiEndpoint", endpointName);
                    Map<String, Object> props = new HashMap<>();
                    props.put("name", endpointName);
                    props.put("fqName", endpointName);
                    props.put("httpMethod", httpMethod);
                    props.put("path", path);
                    props.put("framework", "aspnet");
                    props.put("startLine", line);
                    sink.accept(new GraphEvent.NodeUpsert(endpointKey, props));
                    sink.accept(new GraphEvent.EdgeUpsert(typeKey, "EXPOSES", endpointKey, Map.of()));
                    sink.accept(new GraphEvent.EdgeUpsert(endpointKey, "HANDLES", methodKey, Map.of()));
                }
            }
        }

        private static String firstStringLiteralArg(CSharpParser.AttributeContext at) {
            for (CSharpParser.Attribute_argumentContext arg : at.attribute_argument()) {
                Token start = arg.getStart();
                if (start == null) continue;
                // Walk the argument's tokens; the first STRING token contains the path.
                String literal = findFirstStringLiteral(arg);
                if (literal != null) return literal;
            }
            return null;
        }

        private static String findFirstStringLiteral(ParseTree node) {
            if (node instanceof org.antlr.v4.runtime.tree.TerminalNode tn) {
                int t = tn.getSymbol().getType();
                if (t == CSharpLexer.REGULAR_STRING || t == CSharpLexer.VERBATIUM_STRING
                        || t == CSharpLexer.INTERPOLATED_REGULAR_STRING_START
                        || t == CSharpLexer.INTERPOLATED_VERBATIUM_STRING_START) {
                    return stripQuotes(tn.getText());
                }
                return null;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                String s = findFirstStringLiteral(node.getChild(i));
                if (s != null) return s;
            }
            return null;
        }

        private static String stripQuotes(String s) {
            if (s == null) return null;
            if (s.startsWith("@\"") && s.endsWith("\"")) return s.substring(2, s.length() - 1);
            if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
            return s;
        }

        private static String combinePath(String base, String route) {
            if (route == null || route.isBlank()) return ensureLeadingSlash(base == null ? "" : base);
            if (route.startsWith("/")) return route;
            String b = base == null ? "" : base.replace("[controller]", "");
            if (b.isEmpty()) return ensureLeadingSlash(route);
            if (b.endsWith("/")) return ensureLeadingSlash(b + route);
            return ensureLeadingSlash(b + "/" + route);
        }

        private static String ensureLeadingSlash(String p) {
            if (p == null || p.isEmpty()) return "/";
            return p.startsWith("/") ? p : "/" + p;
        }

        private String qualified(String name) {
            return nsStack.isEmpty() ? name : nsStack.peek() + "." + name;
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
            log.debug("C# ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
