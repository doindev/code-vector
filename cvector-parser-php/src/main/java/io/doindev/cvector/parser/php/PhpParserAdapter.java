package io.doindev.cvector.parser.php;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.php.internal.PhpLexer;
import io.doindev.cvector.parser.php.internal.PhpParser;
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

public class PhpParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(PhpParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("php", "phtml");

    @Override public String name() { return "php"; }
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
        fp.put("language", "php");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        PhpLexer lexer = new PhpLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        PhpParser parser = new PhpParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        PhpParser.HtmlDocumentContext tree;
        try { tree = parser.htmlDocument(); }
        catch (RuntimeException e) { log.warn("PHP parse failed for {}: {}", file, e.getMessage()); return; }

        Set<String> imports = new HashSet<>();
        walk(tree, ctx, fileKey, relPath, imports, sink, null);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath, Set<String> imports,
                       Consumer<GraphEvent> sink, NodeKey enclosingClass) {
        if (node instanceof PhpParser.ClassDeclarationContext cd) {
            // identifier is part of classDeclaration; find it
            String name = findFirstIdentifier(cd);
            if (name != null) {
                String fqName = relPath + "::" + name;
                NodeKey key = new NodeKey(ctx.projectId(), "Class", fqName);
                Map<String, Object> p = new HashMap<>();
                p.put("name", name);
                p.put("fqName", fqName);
                p.put("kind", "class");
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
        }
        if (node instanceof PhpParser.FunctionDeclarationContext fd) {
            String name = fd.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("fqName", fqName);
            p.put("startLine", lineOf(fd));
            p.put("endLine", endLineOf(fd));
            p.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, p));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }
        if (node instanceof PhpParser.ClassStatementContext cs && enclosingClass != null) {
            // class method: look for Function_ keyword + identifier + (
            for (int i = 0; i < cs.getChildCount() - 2; i++) {
                ParseTree c = cs.getChild(i);
                if (c instanceof TerminalNode tn && tn.getSymbol().getType() == PhpLexer.Function_) {
                    int j = i + 1;
                    if (j < cs.getChildCount() && "&".equals(cs.getChild(j).getText())) j++;
                    if (j < cs.getChildCount() && cs.getChild(j) instanceof PhpParser.IdentifierContext id) {
                        String mname = id.getText();
                        String mfq = enclosingClass.fqName() + "." + mname;
                        NodeKey mkey = new NodeKey(ctx.projectId(), "Method", mfq);
                        Map<String, Object> p = new HashMap<>();
                        p.put("name", mname);
                        p.put("fqName", mfq);
                        p.put("startLine", lineOf(cs));
                        p.put("classId", enclosingClass.id());
                        sink.accept(new GraphEvent.NodeUpsert(mkey, p));
                        sink.accept(new GraphEvent.EdgeUpsert(enclosingClass, "CONTAINS", mkey, Map.of()));
                        break;
                    }
                }
            }
        }
        if (node instanceof PhpParser.UseDeclarationContext ud) {
            String txt = ud.useDeclarationContentList().getText();
            for (String part : txt.split(",")) {
                int asIdx = part.indexOf("as");
                String name = (asIdx > 0 ? part.substring(0, asIdx) : part).trim();
                if (!name.isEmpty() && imports.add(name)) {
                    NodeKey mod = new NodeKey(ctx.projectId(), "Module", name);
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", name);
                    p.put("fqName", name);
                    sink.accept(new GraphEvent.NodeUpsert(mod, p));
                    sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), ctx, fileKey, relPath, imports, sink, enclosingClass);
        }
    }

    private static String findFirstIdentifier(ParseTree node) {
        if (node instanceof PhpParser.IdentifierContext id) return id.getText();
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            if (c instanceof PhpParser.IdentifierContext id) return id.getText();
        }
        return null;
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }
    private static int endLineOf(ParserRuleContext c) { Token t = c.getStop(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("PHP syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
