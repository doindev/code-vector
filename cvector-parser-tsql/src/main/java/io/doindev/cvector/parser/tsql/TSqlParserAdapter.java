package io.doindev.cvector.parser.tsql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.tsql.internal.TSqlLexer;
import io.doindev.cvector.parser.tsql.internal.TSqlParser;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class TSqlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(TSqlParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("tsql");

    @Override public String name() { return "tsql"; }
    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".tsql") || name.endsWith(".tsql.sql");
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try { source = Files.readString(file); }
        catch (IOException e) { log.warn("could not read {}: {}", file, e.getMessage()); return; }
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fp = new HashMap<>();
        fp.put("path", relPath);
        fp.put("language", "tsql");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        TSqlLexer lexer = new TSqlLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        TSqlParser parser = new TSqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        TSqlParser.Tsql_fileContext tree;
        try { tree = parser.tsql_file(); }
        catch (RuntimeException e) { log.warn("T-SQL parse failed for {}: {}", file, e.getMessage()); return; }
        walk(tree, ctx, fileKey, relPath, sink);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        if (node instanceof TSqlParser.Create_tableContext ct) {
            String name = ct.table_name() != null ? ct.table_name().getText() : null;
            if (name != null) {
                NodeKey key = new NodeKey(ctx.projectId(), "Table", name);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", name);
                p.put("startLine", lineOf(ct));
                p.put("fileId", fileKey.id());
                sink.accept(new GraphEvent.NodeUpsert(key, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), ctx, fileKey, relPath, sink);
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("T-SQL syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
