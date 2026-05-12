package io.doindev.cvector.parser.cpp;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.cpp.internal.CPP14Lexer;
import io.doindev.cvector.parser.cpp.internal.CPP14Parser;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class CppParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(CppParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("cpp", "cc", "cxx", "hpp", "hh", "hxx");

    @Override public String name() { return "cpp"; }
    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try { source = Files.readString(file); }
        catch (IOException e) { log.warn("could not read {}: {}", file, e.getMessage()); return; }
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fp = new HashMap<>();
        fp.put("path", relPath);
        fp.put("language", "cpp");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        CPP14Lexer lexer = new CPP14Lexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CPP14Parser parser = new CPP14Parser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        CPP14Parser.TranslationUnitContext tree;
        try { tree = parser.translationUnit(); }
        catch (RuntimeException e) { log.warn("C++ parse failed for {}: {}", file, e.getMessage()); return; }

        walk(tree, ctx, fileKey, relPath, sink, null);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink,
                       NodeKey enclosingClass) {
        if (node instanceof CPP14Parser.NamespaceDefinitionContext nd) {
            // Just descend; we don't yet emit Namespace nodes for C++.
        }
        if (node instanceof CPP14Parser.ClassSpecifierContext cs) {
            String name = findFirstIdentifierToken(cs.classHead());
            if (name != null) {
                String fqName = relPath + "::" + name;
                String kind = cs.classHead().classKey() != null ? cs.classHead().classKey().getText() : "class";
                NodeKey key = new NodeKey(ctx.projectId(), kind.equals("struct") ? "Struct" : "Class", fqName);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", fqName);
                p.put("kind", kind);
                p.put("startLine", lineOf(cs));
                p.put("endLine", endLineOf(cs));
                p.put("fileId", fileKey.id());
                sink.accept(new GraphEvent.NodeUpsert(key, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
                for (int i = 0; i < cs.getChildCount(); i++) {
                    walk(cs.getChild(i), ctx, fileKey, relPath, sink, key);
                }
                return;
            }
        }
        if (node instanceof CPP14Parser.FunctionDefinitionContext fd) {
            String name = extractFunctionName(fd);
            if (name != null) {
                NodeKey owner = enclosingClass != null ? enclosingClass : fileKey;
                String fqName = enclosingClass != null
                        ? enclosingClass.fqName() + "." + name
                        : relPath + "::" + name;
                NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", fqName);
                p.put("startLine", lineOf(fd));
                p.put("endLine", endLineOf(fd));
                p.put("fileId", fileKey.id());
                if (enclosingClass != null) p.put("classId", enclosingClass.id());
                if ("main".equals(name)) p.put("isEntry", true);
                sink.accept(new GraphEvent.NodeUpsert(key, p));
                sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", key, Map.of()));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), ctx, fileKey, relPath, sink, enclosingClass);
        }
    }

    private static String extractFunctionName(CPP14Parser.FunctionDefinitionContext fd) {
        CPP14Parser.DeclaratorContext dec = fd.declarator();
        if (dec == null) return null;
        // Walk the declarator, but skip into parametersAndQualifiers (which contains param identifiers).
        return findFirstIdentifierExcluding(dec, CPP14Parser.ParametersAndQualifiersContext.class);
    }

    private static String findFirstIdentifierExcluding(ParseTree node, Class<? extends ParseTree> excluded) {
        if (excluded.isInstance(node)) return null;
        if (node instanceof TerminalNode tn) {
            if (tn.getSymbol().getType() == CPP14Lexer.Identifier) return tn.getText();
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String s = findFirstIdentifierExcluding(node.getChild(i), excluded);
            if (s != null) return s;
        }
        return null;
    }

    private static String findFirstIdentifierToken(ParseTree node) {
        if (node instanceof TerminalNode tn) {
            if (tn.getSymbol().getType() == CPP14Lexer.Identifier) return tn.getText();
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String s = findFirstIdentifierToken(node.getChild(i));
            if (s != null) return s;
        }
        return null;
    }

    private static String findLastIdentifierToken(ParseTree node) {
        String last = null;
        if (node instanceof TerminalNode tn) {
            if (tn.getSymbol().getType() == CPP14Lexer.Identifier) return tn.getText();
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String s = findLastIdentifierToken(node.getChild(i));
            if (s != null) last = s;
        }
        return last;
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }
    private static int endLineOf(ParserRuleContext c) { Token t = c.getStop(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("C++ syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
