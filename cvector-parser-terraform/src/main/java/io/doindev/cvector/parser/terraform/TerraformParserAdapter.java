package io.doindev.cvector.parser.terraform;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.terraform.internal.TerraformLexer;
import io.doindev.cvector.parser.terraform.internal.TerraformParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class TerraformParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(TerraformParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("tf", "tfvars", "hcl");

    @Override public String name() { return "terraform"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            log.warn("could not read {}: {}", file, e.getMessage());
            return;
        }
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "terraform");
        fileProps.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        TerraformLexer lexer = new TerraformLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TerraformParser parser = new TerraformParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        TerraformParser.File_Context tree;
        try {
            tree = parser.file_();
        } catch (RuntimeException e) {
            log.warn("ANTLR parse failed for {}: {}", file, e.getMessage());
            return;
        }

        Walker walker = new Walker(ctx, fileKey, sink);
        walker.collectDeclarations(tree);
        walker.emitReferences(tree);
    }

    private static final class Walker {

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final Consumer<GraphEvent> sink;
        private final Map<String, NodeKey> resourceFqs = new LinkedHashMap<>();
        private final Map<String, NodeKey> dataFqs = new LinkedHashMap<>();
        private final Map<String, NodeKey> moduleFqs = new LinkedHashMap<>();

        Walker(ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.sink = sink;
        }

        void collectDeclarations(TerraformParser.File_Context root) {
            for (ParseTree child : root.children) {
                if (child instanceof TerraformParser.ResourceContext r) emitResource(r);
                else if (child instanceof TerraformParser.DataContext d) emitData(d);
                else if (child instanceof TerraformParser.ModuleContext m) emitModule(m);
                else if (child instanceof TerraformParser.VariableContext v) emitSimple(v.name().STRING().getText(),
                        "TerraformVariable", "var.", lineOf(v));
                else if (child instanceof TerraformParser.OutputContext o) emitSimple(o.name().STRING().getText(),
                        "TerraformOutput", "output.", lineOf(o));
                else if (child instanceof TerraformParser.ProviderContext p) emitProvider(p);
                else if (child instanceof TerraformParser.TerraformContext) { /* terraform { ... } block */ }
                else if (child instanceof TerraformParser.LocalContext) { /* locals { ... } */ }
            }
        }

        private void emitResource(TerraformParser.ResourceContext r) {
            String type = strip(r.resourcetype().STRING().getText());
            String name = strip(r.name().STRING().getText());
            String fqName = type + "." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Resource", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("resourceType", type);
            props.put("provider", providerOf(type));
            props.put("startLine", lineOf(r));
            props.put("endLine", endLineOf(r));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            resourceFqs.put(fqName, key);
        }

        private void emitData(TerraformParser.DataContext d) {
            String type = strip(d.resourcetype().STRING().getText());
            String name = strip(d.name().STRING().getText());
            String fqName = "data." + type + "." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "DataSource", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("resourceType", type);
            props.put("provider", providerOf(type));
            props.put("startLine", lineOf(d));
            props.put("endLine", endLineOf(d));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            dataFqs.put(fqName, key);
        }

        private void emitModule(TerraformParser.ModuleContext m) {
            String name = strip(m.name().STRING().getText());
            String fqName = "module." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "TerraformModule", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(m));
            props.put("endLine", endLineOf(m));
            props.put("fileId", fileKey.id());
            String src = findArgumentValue(m.blockbody(), "source");
            if (src != null) props.put("source", strip(src));
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            moduleFqs.put(fqName, key);
        }

        private void emitSimple(String rawName, String label, String prefix, int line) {
            String name = strip(rawName);
            String fqName = prefix + name;
            NodeKey key = new NodeKey(ctx.projectId(), label, fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", line);
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }

        private void emitProvider(TerraformParser.ProviderContext p) {
            String name = strip(p.resourcetype().STRING().getText());
            String fqName = "provider." + name;
            NodeKey key = new NodeKey(ctx.projectId(), "TerraformProvider", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(p));
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }

        void emitReferences(TerraformParser.File_Context root) {
            Set<String> dedup = new HashSet<>();
            for (ParseTree child : root.children) {
                if (child instanceof TerraformParser.ResourceContext r) {
                    NodeKey owner = resourceFqs.get(strip(r.resourcetype().STRING().getText()) + "." + strip(r.name().STRING().getText()));
                    walkBlock(r.blockbody(), owner, dedup);
                } else if (child instanceof TerraformParser.DataContext d) {
                    NodeKey owner = dataFqs.get("data." + strip(d.resourcetype().STRING().getText()) + "." + strip(d.name().STRING().getText()));
                    walkBlock(d.blockbody(), owner, dedup);
                } else if (child instanceof TerraformParser.ModuleContext m) {
                    NodeKey owner = moduleFqs.get("module." + strip(m.name().STRING().getText()));
                    walkBlock(m.blockbody(), owner, dedup);
                } else if (child instanceof TerraformParser.OutputContext o) {
                    walkBlock(o.blockbody(), fileKey, dedup);
                } else if (child instanceof TerraformParser.LocalContext l) {
                    walkBlock(l.blockbody(), fileKey, dedup);
                }
            }
        }

        private void walkBlock(TerraformParser.BlockbodyContext body, NodeKey owner, Set<String> dedup) {
            if (body == null || owner == null) return;
            for (ParseTree child : descendants(body)) {
                if (child instanceof TerraformParser.IdentifierContext id) {
                    handleIdentifier(id, owner, dedup);
                } else if (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn) {
                    Token tok = tn.getSymbol();
                    if (tok.getType() == TerraformParser.STRING
                            || tok.getType() == TerraformParser.MULTILINESTRING) {
                        scanStringInterpolations(tok.getText(), owner, dedup);
                    }
                }
            }
        }

        private static final java.util.regex.Pattern INTERPOLATION =
                java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");

        // Matches: `var.x`, `data.aws_x.y`, `module.x`, `aws_x.y(.attr...)`
        private static final java.util.regex.Pattern REF_TOKEN = java.util.regex.Pattern.compile(
                "(?<![\\w.])"
                        + "(?:(data|var|module|local)\\.)?"
                        + "([A-Za-z_][\\w-]*)"
                        + "(?:\\.([A-Za-z_][\\w-]*))?"
                        + "(?:\\.[A-Za-z_][\\w-]*)*");

        private void scanStringInterpolations(String literal, NodeKey owner, Set<String> dedup) {
            if (literal == null) return;
            java.util.regex.Matcher m = INTERPOLATION.matcher(literal);
            while (m.find()) {
                String inner = m.group(1);
                java.util.regex.Matcher r = REF_TOKEN.matcher(inner);
                while (r.find()) {
                    String prefix = r.group(1);
                    String head = r.group(2);
                    String tail = r.group(3);
                    if (prefix == null) {
                        // Expect resource ref TYPE.NAME — head looks like a TF resource type only if it
                        // contains an underscore (e.g. aws_s3_bucket) and we declared it.
                        if (head == null || tail == null || !head.contains("_")) continue;
                        String fqName = head + "." + tail;
                        if (resourceFqs.containsKey(fqName)) {
                            emitUses(owner, resourceFqs.get(fqName), fqName, dedup);
                        }
                    } else if ("data".equals(prefix)) {
                        if (head == null || tail == null) continue;
                        String fqName = "data." + head + "." + tail;
                        emitUses(owner, new NodeKey(ctx.projectId(), "DataSource", fqName), fqName, dedup);
                    } else if ("var".equals(prefix)) {
                        if (head == null) continue;
                        String fqName = "var." + head;
                        emitUses(owner, new NodeKey(ctx.projectId(), "TerraformVariable", fqName), fqName, dedup);
                    } else if ("module".equals(prefix)) {
                        if (head == null) continue;
                        String fqName = "module." + head;
                        emitUses(owner, new NodeKey(ctx.projectId(), "TerraformModule", fqName), fqName, dedup);
                    }
                }
            }
        }

        private void handleIdentifier(TerraformParser.IdentifierContext id, NodeKey owner, Set<String> dedup) {
            String head = headPrefix(id);
            TerraformParser.IdentifierchainContext chain = id.identifierchain();
            if (chain == null) return;
            List<String> parts = chainHeads(chain);
            if (parts.isEmpty()) return;

            if ("data".equals(head)) {
                if (parts.size() < 2) return;
                String fqName = "data." + parts.get(0) + "." + parts.get(1);
                emitUses(owner, new NodeKey(ctx.projectId(), "DataSource", fqName), fqName, dedup);
            } else if ("var".equals(head)) {
                String fqName = "var." + parts.get(0);
                emitUses(owner, new NodeKey(ctx.projectId(), "TerraformVariable", fqName), fqName, dedup);
            } else if ("module".equals(head)) {
                String fqName = "module." + parts.get(0);
                emitUses(owner, new NodeKey(ctx.projectId(), "TerraformModule", fqName), fqName, dedup);
            } else if ("local".equals(head)) {
                // locals are file-scoped; skip emitting an edge target unless we model locals.
            } else if (parts.size() >= 2) {
                // bare resource ref: TYPE.NAME — only if we declared it.
                String fqName = parts.get(0) + "." + parts.get(1);
                if (resourceFqs.containsKey(fqName)) {
                    emitUses(owner, resourceFqs.get(fqName), fqName, dedup);
                }
            }
        }

        private static String headPrefix(TerraformParser.IdentifierContext id) {
            // The grammar allows: (('local' | 'data' | 'var' | 'module') DOT)? identifierchain
            // We can inspect the first child token text.
            if (id.children == null || id.children.isEmpty()) return null;
            String first = id.children.get(0).getText();
            return switch (first) {
                case "data", "var", "module", "local" -> first;
                default -> null;
            };
        }

        private static List<String> chainHeads(TerraformParser.IdentifierchainContext chain) {
            List<String> out = new java.util.ArrayList<>();
            collectChainHeads(chain, out);
            return out;
        }

        private static void collectChainHeads(TerraformParser.IdentifierchainContext chain, List<String> out) {
            if (chain == null) return;
            // identifierchain: (IDENTIFIER | IN | VARIABLE | PROVIDER) index? (DOT identifierchain)*
            //                | STAR (DOT identifierchain)*
            //                | inline_index (DOT identifierchain)*
            for (ParseTree c : chain.children) {
                if (c instanceof org.antlr.v4.runtime.tree.TerminalNode tn) {
                    int tt = tn.getSymbol().getType();
                    if (tt == TerraformParser.IDENTIFIER
                            || tt == TerraformParser.IN
                            || tt == TerraformParser.VARIABLE
                            || tt == TerraformParser.PROVIDER) {
                        out.add(tn.getText());
                    }
                } else if (c instanceof TerraformParser.IdentifierchainContext sub) {
                    collectChainHeads(sub, out);
                }
            }
        }

        private static String findArgumentValue(TerraformParser.BlockbodyContext body, String key) {
            if (body == null) return null;
            for (TerraformParser.ArgumentContext arg : body.argument()) {
                if (arg.identifier() == null) continue;
                if (key.equals(arg.identifier().getText())) {
                    return arg.expression().getText();
                }
            }
            return null;
        }

        private void emitUses(NodeKey owner, NodeKey target, String fqName, Set<String> dedup) {
            if (owner == null || owner.equals(target)) return;
            String key = owner.id() + "->" + target.label() + ":" + fqName;
            if (!dedup.add(key)) return;
            Map<String, Object> stub = new HashMap<>();
            stub.put("name", target.fqName().substring(target.fqName().lastIndexOf('.') + 1));
            stub.put("fqName", target.fqName());
            sink.accept(new GraphEvent.NodeUpsert(target, stub));
            sink.accept(new GraphEvent.EdgeUpsert(owner, "USES", target, Map.of()));
        }

        private static String providerOf(String resourceType) {
            int us = resourceType.indexOf('_');
            return us > 0 ? resourceType.substring(0, us) : resourceType;
        }

        private static String strip(String quoted) {
            if (quoted == null) return null;
            if (quoted.length() >= 2 && quoted.charAt(0) == '"' && quoted.charAt(quoted.length() - 1) == '"') {
                return quoted.substring(1, quoted.length() - 1);
            }
            return quoted;
        }

        private static int lineOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
            return ctx.getStart() != null ? ctx.getStart().getLine() : 0;
        }

        private static int endLineOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
            return ctx.getStop() != null ? ctx.getStop().getLine() : 0;
        }

        private static Iterable<ParseTree> descendants(ParseTree root) {
            LinkedHashSet<ParseTree> out = new LinkedHashSet<>();
            walkDescendants(root, out);
            return out;
        }

        private static void walkDescendants(ParseTree node, Set<ParseTree> out) {
            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree c = node.getChild(i);
                out.add(c);
                walkDescendants(c, out);
            }
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            log.debug("ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
