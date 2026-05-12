package io.doindev.cvector.parser.rust;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.rust.internal.RustLexer;
import io.doindev.cvector.parser.rust.internal.RustParser;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class RustParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(RustParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("rs");

    @Override public String name() { return "rust"; }

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
        fileProps.put("language", "rust");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        RustLexer lexer = new RustLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RustParser parser = new RustParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        RustParser.CrateContext tree;
        try {
            tree = parser.crate();
        } catch (RuntimeException e) {
            log.warn("Rust parse failed for {}: {}", file, e.getMessage());
            return;
        }

        new Walker(ctx, fileKey, sink).walk(tree);
    }

    private static final class Walker {

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final Consumer<GraphEvent> sink;
        private final Map<String, NodeKey> declarations = new LinkedHashMap<>();
        private final Deque<String> modulePath = new ArrayDeque<>();
        private final Set<String> seenImports = new HashSet<>();

        Walker(ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.sink = sink;
        }

        void walk(RustParser.CrateContext crate) {
            for (RustParser.ItemContext item : crate.item()) handleItem(item, null);
        }

        private void handleItem(RustParser.ItemContext item, NodeKey enclosingType) {
            RustParser.VisItemContext vis = item.visItem();
            if (vis == null) return;
            if (vis.useDeclaration() != null) {
                emitUseTree(vis.useDeclaration().useTree(), "");
            } else if (vis.module() != null) {
                handleModule(vis.module());
            } else if (vis.function_() != null) {
                emitFunction(vis.function_(), enclosingType);
            } else if (vis.struct_() != null) {
                emitStruct(vis.struct_());
            } else if (vis.enumeration() != null) {
                emitEnum(vis.enumeration());
            } else if (vis.trait_() != null) {
                emitTrait(vis.trait_());
            } else if (vis.implementation() != null) {
                handleImpl(vis.implementation());
            }
        }

        private void handleModule(RustParser.ModuleContext m) {
            String name = m.identifier().getText();
            NodeKey modKey = new NodeKey(ctx.projectId(), "RustModule", qualified(name));
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", qualified(name));
            props.put("startLine", lineOf(m));
            props.put("endLine", endLineOf(m));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(modKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", modKey, Map.of()));

            modulePath.push(name);
            try {
                for (RustParser.ItemContext sub : m.item()) handleItem(sub, null);
            } finally {
                modulePath.pop();
            }
        }

        private void emitFunction(RustParser.Function_Context fn, NodeKey enclosingType) {
            String name = fn.identifier().getText();
            String fqName = enclosingType != null
                    ? enclosingType.fqName() + "::" + name
                    : qualified(name);
            NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(fn));
            props.put("endLine", endLineOf(fn));
            props.put("fileId", fileKey.id());
            if (fn.functionQualifiers() != null && fn.functionQualifiers().KW_ASYNC() != null) {
                props.put("isAsync", true);
            }
            if (name.startsWith("test_") || name.startsWith("test")) {
                if (looksLikeTest(fn)) props.put("isTest", true);
            }
            if ("main".equals(name)) props.put("isEntry", true);

            sink.accept(new GraphEvent.NodeUpsert(key, props));
            NodeKey owner = enclosingType != null ? enclosingType : fileKey;
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", key, Map.of()));
            declarations.putIfAbsent(name, key);
        }

        private static boolean looksLikeTest(RustParser.Function_Context fn) {
            ParserRuleContext outer = fn.getParent();
            // outer is visItem; outer's parent is item with outerAttribute*
            while (outer != null && !(outer instanceof RustParser.ItemContext)) outer = outer.getParent();
            if (!(outer instanceof RustParser.ItemContext item)) return false;
            for (RustParser.OuterAttributeContext attr : item.outerAttribute()) {
                if (attr.getText().contains("test")) return true;
            }
            return false;
        }

        private void emitStruct(RustParser.Struct_Context s) {
            String name;
            int startLine, endLine;
            if (s.structStruct() != null) {
                name = s.structStruct().identifier().getText();
                startLine = lineOf(s.structStruct());
                endLine = endLineOf(s.structStruct());
            } else if (s.tupleStruct() != null) {
                name = s.tupleStruct().identifier().getText();
                startLine = lineOf(s.tupleStruct());
                endLine = endLineOf(s.tupleStruct());
            } else {
                return;
            }
            emitTypeNode("Struct", name, "struct", startLine, endLine);
        }

        private void emitEnum(RustParser.EnumerationContext e) {
            String name = e.identifier().getText();
            emitTypeNode("Enum", name, "enum", lineOf(e), endLineOf(e));
        }

        private void emitTrait(RustParser.Trait_Context t) {
            String name = t.identifier().getText();
            NodeKey traitKey = emitTypeNode("Trait", name, "trait", lineOf(t), endLineOf(t));
            for (RustParser.AssociatedItemContext ai : t.associatedItem()) {
                if (ai.function_() != null) emitFunction(ai.function_(), traitKey);
            }
        }

        private NodeKey emitTypeNode(String label, String name, String kind, int startLine, int endLine) {
            String fqName = qualified(name);
            NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", kind);
            props.put("startLine", startLine);
            props.put("endLine", endLine);
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            declarations.putIfAbsent(name, key);
            return key;
        }

        private void handleImpl(RustParser.ImplementationContext impl) {
            if (impl.inherentImpl() != null) {
                RustParser.InherentImplContext ii = impl.inherentImpl();
                String targetName = stripGenerics(ii.type_().getText());
                NodeKey targetKey = declarations.getOrDefault(targetName,
                        new NodeKey(ctx.projectId(), "Struct", qualified(targetName)));
                for (RustParser.AssociatedItemContext ai : ii.associatedItem()) {
                    if (ai.function_() != null) emitFunction(ai.function_(), targetKey);
                }
            } else if (impl.traitImpl() != null) {
                RustParser.TraitImplContext ti = impl.traitImpl();
                String traitName = stripGenerics(ti.typePath().getText());
                String targetName = stripGenerics(ti.type_().getText());
                NodeKey targetKey = declarations.getOrDefault(targetName,
                        new NodeKey(ctx.projectId(), "Struct", qualified(targetName)));
                NodeKey traitKey = new NodeKey(ctx.projectId(), "Trait", traitName);
                Map<String, Object> stub = new HashMap<>();
                stub.put("name", traitName);
                stub.put("fqName", traitName);
                sink.accept(new GraphEvent.NodeUpsert(traitKey, stub));
                sink.accept(new GraphEvent.EdgeUpsert(targetKey, "IMPLEMENTS", traitKey, Map.of()));
                for (RustParser.AssociatedItemContext ai : ti.associatedItem()) {
                    if (ai.function_() != null) emitFunction(ai.function_(), targetKey);
                }
            }
        }

        private void emitUseTree(RustParser.UseTreeContext tree, String prefix) {
            if (tree == null) return;
            // useTree: (simplePath? PATHSEP)? (STAR | LCURLYBRACE ( useTree (COMMA useTree)* COMMA?)? RCURLYBRACE)
            //        | simplePath (KW_AS (identifier | UNDERSCORE))?
            RustParser.SimplePathContext simple = tree.simplePath();
            boolean hasBrace = tree.LCURLYBRACE() != null;
            boolean hasStar = tree.STAR() != null;

            if (!hasBrace && !hasStar) {
                // leaf: simplePath ( 'as' ident )?
                if (simple == null) return;
                String path = prefix.isEmpty() ? simple.getText() : prefix + "::" + simple.getText();
                emitImport(path);
                return;
            }

            String newPrefix = prefix;
            if (simple != null) {
                newPrefix = prefix.isEmpty() ? simple.getText() : prefix + "::" + simple.getText();
            }
            if (hasStar) {
                emitImport(newPrefix + "::*");
                return;
            }
            for (RustParser.UseTreeContext sub : tree.useTree()) {
                emitUseTree(sub, newPrefix);
            }
        }

        private void emitImport(String target) {
            if (target == null || target.isBlank() || !seenImports.add(target)) return;
            NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(mod, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
        }

        private String qualified(String name) {
            if (modulePath.isEmpty()) return name;
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = modulePath.descendingIterator();
            while (it.hasNext()) {
                sb.append(it.next()).append("::");
            }
            sb.append(name);
            return sb.toString();
        }

        private static String stripGenerics(String s) {
            if (s == null) return null;
            int lt = s.indexOf('<');
            return lt >= 0 ? s.substring(0, lt) : s;
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
            log.debug("Rust ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
