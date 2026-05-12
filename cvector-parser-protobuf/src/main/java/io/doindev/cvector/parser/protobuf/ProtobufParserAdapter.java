package io.doindev.cvector.parser.protobuf;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.protobuf.internal.Protobuf3Lexer;
import io.doindev.cvector.parser.protobuf.internal.Protobuf3Parser;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class ProtobufParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(ProtobufParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("proto");

    @Override public String name() { return "protobuf"; }
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
        fp.put("language", "protobuf");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        Protobuf3Lexer lexer = new Protobuf3Lexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        Protobuf3Parser parser = new Protobuf3Parser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        Protobuf3Parser.ProtoContext tree;
        try { tree = parser.proto(); }
        catch (RuntimeException e) { log.warn("Protobuf parse failed for {}: {}", file, e.getMessage()); return; }

        Set<String> imports = new HashSet<>();
        walk(tree, ctx, fileKey, relPath, imports, sink, null);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath, Set<String> imports,
                       Consumer<GraphEvent> sink, NodeKey enclosing) {
        if (node instanceof Protobuf3Parser.MessageDefContext md) {
            String name = md.messageName().getText();
            String fq = enclosing != null ? enclosing.fqName() + "." + name : relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "ProtobufMessage", fq);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fq);
            p.put("startLine", lineOf(md));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(enclosing != null ? enclosing : fileKey, "CONTAINS", key, Map.of()));
            for (int i = 0; i < md.getChildCount(); i++) {
                walk(md.getChild(i), ctx, fileKey, relPath, imports, sink, key);
            }
            return;
        }
        if (node instanceof Protobuf3Parser.EnumDefContext ed) {
            String name = ed.enumName().getText();
            String fq = enclosing != null ? enclosing.fqName() + "." + name : relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "ProtobufEnum", fq);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fq);
            p.put("startLine", lineOf(ed));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(enclosing != null ? enclosing : fileKey, "CONTAINS", key, Map.of()));
        }
        if (node instanceof Protobuf3Parser.ServiceDefContext sd) {
            String name = sd.serviceName().getText();
            String fq = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "ProtobufService", fq);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fq);
            p.put("startLine", lineOf(sd));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            for (Protobuf3Parser.ServiceElementContext se : sd.serviceElement()) {
                if (se.rpc() != null) {
                    String rname = se.rpc().rpcName().getText();
                    String rfq = fq + "." + rname;
                    NodeKey rkey = new NodeKey(ctx.projectId(), "ProtobufRpc", rfq);
                    Map<String, Object> rp = new HashMap<>();
                    rp.put("name", rname);
                    rp.put("fqName", rfq);
                    rp.put("startLine", lineOf(se.rpc()));
                    sink.accept(new GraphEvent.NodeUpsert(rkey, rp));
                    sink.accept(new GraphEvent.EdgeUpsert(key, "CONTAINS", rkey, Map.of()));
                }
            }
        }
        if (node instanceof Protobuf3Parser.ImportStatementContext is) {
            String target = stripQuotes(is.strLit().getText());
            if (target != null && imports.add(target)) {
                NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
                Map<String, Object> p = new HashMap<>();
                p.put("name", target);
                p.put("fqName", target);
                sink.accept(new GraphEvent.NodeUpsert(mod, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), ctx, fileKey, relPath, imports, sink, enclosing);
        }
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char f = s.charAt(0), l = s.charAt(s.length() - 1);
        if ((f == '"' || f == '\'') && f == l) return s.substring(1, s.length() - 1);
        return s;
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("Protobuf syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
