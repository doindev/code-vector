package io.doindev.cvector.parser.plsql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.plsql.internal.PlSqlLexer;
import io.doindev.cvector.parser.plsql.internal.PlSqlParser;
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

public class PlSqlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(PlSqlParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("pls", "plsql", "pks", "pkb");

    @Override public String name() { return "plsql"; }
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
        fp.put("language", "plsql");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(source.toUpperCase()));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        PlSqlParser parser = new PlSqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        PlSqlParser.Sql_scriptContext tree;
        try { tree = parser.sql_script(); }
        catch (RuntimeException e) { log.warn("PL/SQL parse failed for {}: {}", file, e.getMessage()); return; }
        walk(tree, ctx, fileKey, relPath, sink);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        if (node instanceof PlSqlParser.Create_tableContext ct) {
            String name = textOrNull(ct.tableview_name());
            if (name != null) emit("Table", name, ct, ctx, fileKey, sink);
        }
        if (node instanceof PlSqlParser.Create_function_bodyContext cf) {
            String name = textOrNull(cf.function_name());
            if (name != null) emit("Method", "fn:" + name, cf, ctx, fileKey, sink, "function");
        }
        if (node instanceof PlSqlParser.Create_procedure_bodyContext cp) {
            String name = textOrNull(cp.procedure_name());
            if (name != null) emit("Method", "proc:" + name, cp, ctx, fileKey, sink, "procedure");
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), ctx, fileKey, relPath, sink);
    }

    private void emit(String label, String fqName, ParserRuleContext src, ProjectContext ctx,
                       NodeKey fileKey, Consumer<GraphEvent> sink) {
        emit(label, fqName, src, ctx, fileKey, sink, null);
    }

    private void emit(String label, String fqName, ParserRuleContext src, ProjectContext ctx,
                       NodeKey fileKey, Consumer<GraphEvent> sink, String kind) {
        NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
        Map<String, Object> p = new HashMap<>();
        p.put("name", fqName.contains(":") ? fqName.substring(fqName.indexOf(':') + 1) : fqName);
        p.put("fqName", fqName);
        if (kind != null) p.put("kind", kind);
        p.put("startLine", lineOf(src));
        p.put("fileId", fileKey.id());
        sink.accept(new GraphEvent.NodeUpsert(key, p));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
    }

    private static String textOrNull(ParseTree t) { return t == null ? null : t.getText(); }
    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("PL/SQL syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
