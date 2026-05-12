package io.doindev.cvector.parser.graphql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.graphql.internal.GraphQLLexer;
import io.doindev.cvector.parser.graphql.internal.GraphQLParser;
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

public class GraphqlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(GraphqlParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("graphql", "gql");

    @Override public String name() { return "graphql"; }
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
        fp.put("language", "graphql");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        GraphQLLexer lexer = new GraphQLLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        GraphQLParser parser = new GraphQLParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        GraphQLParser.DocumentContext tree;
        try { tree = parser.document(); }
        catch (RuntimeException e) { log.warn("GraphQL parse failed for {}: {}", file, e.getMessage()); return; }
        walk(tree, ctx, fileKey, relPath, sink, null);
    }

    private void walk(ParseTree node, ProjectContext ctx, NodeKey fileKey, String relPath,
                       Consumer<GraphEvent> sink, NodeKey enclosingType) {
        if (node instanceof GraphQLParser.ObjectTypeDefinitionContext otd) {
            NodeKey key = emitTypeNode("GraphqlType", otd.name().getText(), "type", otd, ctx, fileKey, relPath, sink);
            walkFields(otd.fieldsDefinition(), key, ctx, fileKey, sink);
            return;
        }
        if (node instanceof GraphQLParser.InterfaceTypeDefinitionContext itd) {
            NodeKey key = emitTypeNode("GraphqlInterface", itd.name().getText(), "interface", itd, ctx, fileKey, relPath, sink);
            walkFields(itd.fieldsDefinition(), key, ctx, fileKey, sink);
            return;
        }
        if (node instanceof GraphQLParser.EnumTypeDefinitionContext etd) {
            emitTypeNode("GraphqlEnum", etd.name().getText(), "enum", etd, ctx, fileKey, relPath, sink);
            return;
        }
        if (node instanceof GraphQLParser.UnionTypeDefinitionContext utd) {
            emitTypeNode("GraphqlUnion", utd.name().getText(), "union", utd, ctx, fileKey, relPath, sink);
            return;
        }
        if (node instanceof GraphQLParser.ScalarTypeDefinitionContext std) {
            emitTypeNode("GraphqlScalar", std.name().getText(), "scalar", std, ctx, fileKey, relPath, sink);
            return;
        }
        if (node instanceof GraphQLParser.InputObjectTypeDefinitionContext iod) {
            emitTypeNode("GraphqlInput", iod.name().getText(), "input", iod, ctx, fileKey, relPath, sink);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), ctx, fileKey, relPath, sink, enclosingType);
        }
    }

    private NodeKey emitTypeNode(String label, String name, String kind, ParserRuleContext src,
                                  ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
        String fqName = relPath + "::" + name;
        NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", fqName);
        props.put("kind", kind);
        props.put("startLine", lineOf(src));
        props.put("fileId", fileKey.id());
        sink.accept(new GraphEvent.NodeUpsert(key, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        return key;
    }

    private void walkFields(GraphQLParser.FieldsDefinitionContext fields, NodeKey typeKey,
                             ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        if (fields == null) return;
        for (GraphQLParser.FieldDefinitionContext fd : fields.fieldDefinition()) {
            String name = fd.name().getText();
            String fq = typeKey.fqName() + "." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "GraphqlField", fq);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fq);
            props.put("type", fd.type_() != null ? fd.type_().getText() : null);
            props.put("startLine", lineOf(fd));
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(typeKey, "CONTAINS", key, Map.of()));
        }
    }

    private static int lineOf(ParserRuleContext c) { Token t = c.getStart(); return t != null ? t.getLine() : 0; }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("GraphQL syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
