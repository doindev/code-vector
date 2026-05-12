package io.doindev.cvector.parser.bicep;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.bicep.internal.BicepLexer;
import io.doindev.cvector.parser.bicep.internal.BicepParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class BicepParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(BicepParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("bicep");

    @Override public String name() { return "bicep"; }
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
        fp.put("language", "bicep");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        BicepLexer lexer = new BicepLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        BicepParser parser = new BicepParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        BicepParser.ProgramContext tree;
        try { tree = parser.program(); }
        catch (RuntimeException e) { log.warn("Bicep parse failed for {}: {}", file, e.getMessage()); return; }

        for (BicepParser.StatementContext s : tree.statement()) {
            handleStatement(s, ctx, fileKey, relPath, sink);
        }
    }

    private void handleStatement(BicepParser.StatementContext s, ProjectContext ctx, NodeKey fileKey,
                                  String relPath, Consumer<GraphEvent> sink) {
        if (s.resourceDecl() != null) {
            BicepParser.ResourceDeclContext r = s.resourceDecl();
            String name = r.name.getText();
            String type = stripInterpString(r.type.getText());
            String fqName = "resource." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Resource", fqName);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fqName);
            p.put("resourceType", type);
            p.put("provider", "azure");
            p.put("startLine", lineOf(r));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        } else if (s.moduleDecl() != null) {
            BicepParser.ModuleDeclContext m = s.moduleDecl();
            String name = m.name.getText();
            String src = stripInterpString(m.type.getText());
            String fqName = "module." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "BicepModule", fqName);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fqName);
            p.put("source", src);
            p.put("startLine", lineOf(m));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        } else if (s.parameterDecl() != null) {
            BicepParser.ParameterDeclContext pd = s.parameterDecl();
            String name = pd.name.getText();
            emit("BicepParameter", "param." + name, name, lineOf(pd), ctx, fileKey, sink);
        } else if (s.outputDecl() != null) {
            BicepParser.OutputDeclContext od = s.outputDecl();
            String name = od.name.getText();
            emit("BicepOutput", "output." + name, name, lineOf(od), ctx, fileKey, sink);
        } else if (s.variableDecl() != null) {
            BicepParser.VariableDeclContext vd = s.variableDecl();
            String name = vd.name.getText();
            emit("BicepVariable", "var." + name, name, lineOf(vd), ctx, fileKey, sink);
        }
    }

    private void emit(String label, String fqName, String name, int line, ProjectContext ctx, NodeKey fileKey,
                       Consumer<GraphEvent> sink) {
        NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("fqName", fqName);
        p.put("startLine", line);
        p.put("fileId", fileKey.id());
        sink.accept(new GraphEvent.NodeUpsert(key, p));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
    }

    private static String stripInterpString(String s) {
        if (s == null || s.length() < 2) return s;
        if (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') return s.substring(1, s.length() - 1);
        return s;
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("Bicep syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
