package io.doindev.cvector.parser.solidity;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.solidity.internal.SolidityLexer;
import io.doindev.cvector.parser.solidity.internal.SolidityParser;
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

public class SolidityParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(SolidityParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("sol");

    @Override public String name() { return "solidity"; }
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
        fp.put("language", "solidity");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        SolidityLexer lexer = new SolidityLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        SolidityParser parser = new SolidityParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        SolidityParser.SourceUnitContext tree;
        try { tree = parser.sourceUnit(); }
        catch (RuntimeException e) { log.warn("Solidity parse failed for {}: {}", file, e.getMessage()); return; }

        Set<String> imports = new HashSet<>();
        walk(tree, ctx, fileKey, relPath, imports, sink, null);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath,
                       Set<String> imports, Consumer<GraphEvent> sink, NodeKey enclosingContract) {
        if (node instanceof SolidityParser.ContractDefinitionContext cd) {
            String name = cd.name.getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Contract", fqName);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fqName);
            p.put("kind", "contract");
            p.put("startLine", lineOf(cd));
            p.put("endLine", endLineOf(cd));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            for (int i = 0; i < cd.getChildCount(); i++) {
                walk(cd.getChild(i), ctx, fileKey, relPath, imports, sink, key);
            }
            return;
        }
        if (node instanceof SolidityParser.InterfaceDefinitionContext id) {
            String name = id.name.getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Interface", fqName);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fqName);
            p.put("kind", "interface");
            p.put("startLine", lineOf(id));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }
        if (node instanceof SolidityParser.FunctionDefinitionContext fd) {
            String name = fd.getChild(1) != null ? fd.getChild(1).getText() : "<anon>";
            NodeKey owner = enclosingContract != null ? enclosingContract : fileKey;
            String fqName = (enclosingContract != null ? enclosingContract.fqName() : relPath + "::") + "." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fqName);
            p.put("startLine", lineOf(fd));
            p.put("endLine", endLineOf(fd));
            p.put("fileId", fileKey.id());
            if (enclosingContract != null) p.put("contractId", enclosingContract.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(owner, "CONTAINS", key, Map.of()));
        }
        if (node instanceof SolidityParser.ImportDirectiveContext im) {
            String target = im.path() != null ? stripQuotes(im.path().getText()) : null;
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
            walk(node.getChild(i), ctx, fileKey, relPath, imports, sink, enclosingContract);
        }
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char f = s.charAt(0), l = s.charAt(s.length() - 1);
        if ((f == '"' || f == '\'') && f == l) return s.substring(1, s.length() - 1);
        return s;
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }
    private static int endLineOf(ParserRuleContext c) { Token t = c.getStop(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("Solidity syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
