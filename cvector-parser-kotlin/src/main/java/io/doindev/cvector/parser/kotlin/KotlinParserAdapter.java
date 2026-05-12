package io.doindev.cvector.parser.kotlin;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.kotlin.internal.KotlinLexer;
import io.doindev.cvector.parser.kotlin.internal.KotlinParser;
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

public class KotlinParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(KotlinParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("kt", "kts");

    @Override public String name() { return "kotlin"; }
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
        fp.put("language", "kotlin");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        KotlinLexer lexer = new KotlinLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        KotlinParser parser = new KotlinParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        KotlinParser.KotlinFileContext tree;
        try { tree = parser.kotlinFile(); }
        catch (RuntimeException e) { log.warn("Kotlin parse failed for {}: {}", file, e.getMessage()); return; }

        Set<String> seenImports = new HashSet<>();
        walk(tree, ctx, fileKey, relPath, seenImports, sink, null);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath,
                       Set<String> seenImports, Consumer<GraphEvent> sink, NodeKey enclosingClass) {
        if (node instanceof KotlinParser.ImportHeaderContext ih) {
            String target = ih.identifier().getText();
            if (seenImports.add(target)) {
                NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
                Map<String, Object> p = new HashMap<>();
                p.put("name", target);
                p.put("fqName", target);
                sink.accept(new GraphEvent.NodeUpsert(mod, p));
                sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
            }
        } else if (node instanceof KotlinParser.ClassDeclarationContext cd) {
            String name = cd.simpleIdentifier().getText();
            String fqName = relPath + "::" + name;
            String label = "interface".equalsIgnoreCase(cd.start.getText()) ? "Interface" : "Class";
            NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", label.toLowerCase());
            props.put("startLine", lineOf(cd));
            props.put("endLine", endLineOf(cd));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            for (int i = 0; i < cd.getChildCount(); i++) {
                walk(cd.getChild(i), ctx, fileKey, relPath, seenImports, sink, key);
            }
            return;
        } else if (node instanceof KotlinParser.FunctionDeclarationContext fd) {
            if (fd.identifier() != null) {
                String name = fd.identifier().getText();
                String fqName = enclosingClass != null ? enclosingClass.fqName() + "." + name : relPath + "::" + name;
                NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
                Map<String, Object> props = new HashMap<>();
                props.put("name", name);
                props.put("fqName", fqName);
                props.put("startLine", lineOf(fd));
                props.put("endLine", endLineOf(fd));
                props.put("fileId", fileKey.id());
                if (enclosingClass != null) props.put("classId", enclosingClass.id());
                sink.accept(new GraphEvent.NodeUpsert(key, props));
                sink.accept(new GraphEvent.EdgeUpsert(
                        enclosingClass != null ? enclosingClass : fileKey, "CONTAINS", key, Map.of()));
            }
        } else if (node instanceof KotlinParser.ObjectDeclarationContext od) {
            String name = od.simpleIdentifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Class", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", "object");
            props.put("startLine", lineOf(od));
            props.put("endLine", endLineOf(od));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), ctx, fileKey, relPath, seenImports, sink, enclosingClass);
        }
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }
    private static int endLineOf(ParserRuleContext c) { Token t = c.getStop(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("Kotlin syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
