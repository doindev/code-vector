package io.doindev.cvector.parser.cypher;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.cypher.internal.CypherLexer;
import io.doindev.cvector.parser.cypher.internal.CypherParser;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class CypherParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(CypherParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("cypher", "cql");

    @Override public String name() { return "cypher"; }
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
        fp.put("language", "cypher");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        CypherLexer lexer = new CypherLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CypherParser parser = new CypherParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        CypherParser.ScriptContext tree;
        try { tree = parser.script(); }
        catch (RuntimeException e) { log.warn("Cypher parse failed for {}: {}", file, e.getMessage()); return; }

        // Walk tree and collect node labels (preceded by ':') and relationship types.
        Set<String> labels = new HashSet<>();
        Set<String> relTypes = new HashSet<>();
        collectColonRefs(tree, labels, relTypes);

        for (String lbl : labels) {
            String fq = "label:" + lbl;
            NodeKey k = new NodeKey(ctx.projectId(), "CypherLabel", fq);
            Map<String, Object> p = new HashMap<>();
            p.put("name", lbl);
            p.put("fqName", fq);
            sink.accept(new GraphEvent.NodeUpsert(k, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "REFERENCES", k, Map.of()));
        }
        for (String rt : relTypes) {
            String fq = "rel:" + rt;
            NodeKey k = new NodeKey(ctx.projectId(), "CypherRelType", fq);
            Map<String, Object> p = new HashMap<>();
            p.put("name", rt);
            p.put("fqName", fq);
            sink.accept(new GraphEvent.NodeUpsert(k, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "REFERENCES", k, Map.of()));
        }
    }

    /** Walks all NodeLabel and RelationshipTypes contexts to capture :Label and :REL_TYPE identifiers. */
    private void collectColonRefs(ParseTree node, Set<String> labels, Set<String> relTypes) {
        String cls = node.getClass().getSimpleName();
        if (cls.contains("NodeLabel") || cls.contains("NodeLabels")) {
            String txt = node.getText();
            if (txt.startsWith(":")) txt = txt.substring(1);
            for (String t : txt.split(":")) {
                if (!t.isBlank()) labels.add(t.trim());
            }
            return;
        }
        if (cls.contains("RelType") || cls.contains("RelationshipType")) {
            String txt = node.getText();
            if (txt.startsWith(":")) txt = txt.substring(1);
            for (String t : txt.split("\\|")) {
                if (!t.isBlank()) relTypes.add(t.trim());
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectColonRefs(node.getChild(i), labels, relTypes);
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("Cypher syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
