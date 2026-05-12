package io.doindev.cvector.parser.sparql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.sparql.internal.SparqlLexer;
import io.doindev.cvector.parser.sparql.internal.SparqlParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class SparqlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(SparqlParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("sparql", "rq");

    @Override public String name() { return "sparql"; }
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
        fp.put("language", "sparql");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        SparqlLexer lexer = new SparqlLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        SparqlParser parser = new SparqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        SparqlParser.QueryContext tree;
        try { tree = parser.query(); }
        catch (RuntimeException e) { log.warn("SPARQL parse failed for {}: {}", file, e.getMessage()); return; }

        String queryKind = "select";
        if (tree.constructQuery() != null) queryKind = "construct";
        else if (tree.describeQuery() != null) queryKind = "describe";
        else if (tree.askQuery() != null) queryKind = "ask";

        String fq = relPath + "::" + queryKind;
        NodeKey q = new NodeKey(ctx.projectId(), "SparqlQuery", fq);
        Map<String, Object> qp = new HashMap<>();
        qp.put("name", queryKind);
        qp.put("fqName", fq);
        qp.put("kind", queryKind);
        qp.put("fileId", fileKey.id());
        sink.accept(new GraphEvent.NodeUpsert(q, qp));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", q, Map.of()));
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("SPARQL syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
