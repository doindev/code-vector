package io.doindev.cvector.parser.c;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.c.internal.CLexer;
import io.doindev.cvector.parser.c.internal.CParser;
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

public class CParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(CParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("c", "h");

    @Override public String name() { return "c"; }
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
        fp.put("language", "c");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        CLexer lexer = new CLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CParser parser = new CParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        CParser.CompilationUnitContext tree;
        try { tree = parser.compilationUnit(); }
        catch (RuntimeException e) { log.warn("C parse failed for {}: {}", file, e.getMessage()); return; }

        walk(tree, ctx, fileKey, relPath, sink);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        if (node instanceof CParser.FunctionDefinitionContext fd) {
            String name = extractFunctionName(fd);
            if (name != null) {
                String fqName = relPath + "::" + name;
                NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", fqName);
                p.put("startLine", lineOf(fd));
                p.put("endLine", endLineOf(fd));
                p.put("fileId", fileKey.id());
                if ("main".equals(name)) p.put("isEntry", true);
                sink.accept(new GraphEvent.NodeUpsert(key, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            }
        }
        if (node instanceof CParser.StructOrUnionSpecifierContext s) {
            String name = findFirstIdentifierToken(s);
            if (name != null) {
                String kind = s.structOrUnion() != null && "union".equals(s.structOrUnion().getText())
                        ? "union" : "struct";
                String fqName = relPath + "::" + name;
                NodeKey key = new NodeKey(ctx.projectId(), kind.equals("union") ? "Union" : "Struct", fqName);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", fqName);
                p.put("kind", kind);
                p.put("startLine", lineOf(s));
                p.put("fileId", fileKey.id());
                sink.accept(new GraphEvent.NodeUpsert(key, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            }
        }
        if (node instanceof CParser.EnumSpecifierContext es) {
            String name = findFirstIdentifierToken(es);
            if (name != null) {
                String fqName = relPath + "::" + name;
                NodeKey key = new NodeKey(ctx.projectId(), "Enum", fqName);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", fqName);
                p.put("kind", "enum");
                p.put("startLine", lineOf(es));
                p.put("fileId", fileKey.id());
                sink.accept(new GraphEvent.NodeUpsert(key, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), ctx, fileKey, relPath, sink);
    }

    private static String extractFunctionName(CParser.FunctionDefinitionContext fd) {
        CParser.DeclaratorContext dec = fd.declarator();
        if (dec == null) return null;
        return findFirstIdentifierToken(dec);
    }

    private static String findFirstIdentifierToken(ParseTree node) {
        if (node instanceof TerminalNode tn) {
            if (tn.getSymbol().getType() == CLexer.Identifier) return tn.getText();
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String s = findFirstIdentifierToken(node.getChild(i));
            if (s != null) return s;
        }
        return null;
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }
    private static int endLineOf(ParserRuleContext c) { Token t = c.getStop(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("C syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
