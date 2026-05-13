package io.doindev.cvector.parser.ts;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.ts.internal.TypeScriptLexer;
import io.doindev.cvector.parser.ts.internal.TypeScriptParser;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeScriptParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("ts", "tsx", "js", "jsx", "mjs", "cjs");
    private static final Set<String> SKIP_NAMES = Set.of("vite.config.ts", "next.config.js");

    @Override public String name() { return "typescript"; }

    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public boolean accepts(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".d.ts")) return false;
        if (SKIP_NAMES.contains(name)) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            log.warn("could not read {}: {}", file, e.getMessage());
            return;
        }
        parseSource(source, file, null, ctx, sink, true);
    }

    /**
     * Parse a JS/TS source string. Used by the Vue SFC parser to pipe {@code <script>} block
     * contents through the same AST walker without first writing to disk.
     *
     * @param source           the source text
     * @param identityPath     path the resulting node identities derive from (the {@code File}
     *                         NodeKey and Class/Method fqNames are keyed off this). For Vue this
     *                         is the .vue file path so nodes attribute back to the SFC.
     * @param languageOverride optional explicit language name ({@code "typescript"}, {@code "tsx"},
     *                         {@code "jsx"}, {@code "javascript"}). When null, derived from
     *                         {@code identityPath}'s extension. Set this for Vue's
     *                         {@code <script lang="ts">} since the .vue extension hides the dialect.
     * @param emitFileNode     pass {@code false} when the caller has already emitted a File node
     *                         for {@code identityPath} (e.g. Vue emits {@code language=vue} first,
     *                         then delegates; the script delegate should not overwrite it).
     */
    public void parseSource(String source, Path identityPath, String languageOverride,
                            ProjectContext ctx, Consumer<GraphEvent> sink, boolean emitFileNode) {
        String relPath = ctx.rootPath().relativize(identityPath).toString().replace('\\', '/');
        String language = (languageOverride != null) ? languageOverride : detectLanguage(identityPath);

        // Cheap header sniff for machine-generated files. ANTLR / Babel / Protoc / Hasura / etc.
        // all emit a recognisable banner in the first few hundred characters. Skipping the deep
        // AST walk for these files is a major perf win (the explorer's CypherParser.ts is 18k
        // lines on its own) and removes grammar-internal nodes that crowd out real code in
        // community / call-graph reports. We still emit a minimal File node so the file count
        // is honest and the graph can be queried for "what code is generated, by what tool".
        if (looksGenerated(source)) {
            if (emitFileNode) {
                NodeKey fk = new NodeKey(ctx.projectId(), "File", relPath);
                Map<String, Object> fp = new HashMap<>();
                fp.put("path", relPath);
                fp.put("language", language + "-generated");
                fp.put("lineCount", source.lines().count());
                sink.accept(new GraphEvent.NodeUpsert(fk, fp));
            }
            log.debug("skipped generated source {}", identityPath);
            return;
        }

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        if (emitFileNode) {
            Map<String, Object> fileProps = new HashMap<>();
            fileProps.put("path", relPath);
            fileProps.put("language", language);
            fileProps.put("lineCount", source.lines().count());
            sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));
        }

        TypeScriptLexer lexer = new TypeScriptLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TypeScriptParser parser = new TypeScriptParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);

        // Note: SLL prediction with LL fallback was tried and measured 2x slower on this
        // grammar -- the TS grammar's frequent ambiguities cause most files to bail and
        // re-parse under LL, so the dual-pass is net negative. Stick with the default LL
        // adaptive prediction.
        TypeScriptParser.ProgramContext tree;
        try {
            tree = parser.program();
        } catch (RuntimeException e) {
            log.warn("TS parse failed for {}: {}", identityPath, e.getMessage());
            return;
        }

        Walker walker = new Walker(ctx, fileKey, relPath, sink);
        prescanRequireBindings(source, walker);
        walker.walk(tree);
    }

    /**
     * Sweep CommonJS {@code require()} call sites out of the raw source text and feed them into
     * the walker's import-binding map so cross-file resolution works for {@code const x = require(...)}
     * declarations the same way it does for ES {@code import} statements. Runs once before AST
     * walking; cost is two regex passes over the source.
     */
    private static void prescanRequireBindings(String source, Walker walker) {
        Matcher def = Walker.REQUIRE_DEFAULT.matcher(source);
        while (def.find()) {
            walker.importedBindings.putIfAbsent(def.group(1), def.group(2));
        }
        Matcher destr = Walker.REQUIRE_DESTRUCTURE.matcher(source);
        while (destr.find()) {
            String moduleSpec = destr.group(2);
            for (String part : destr.group(1).split(",")) {
                String item = part.trim();
                if (item.isEmpty()) continue;
                // Handle `name` or `name: alias`. Local name is the alias if present.
                int colon = item.indexOf(':');
                String local = colon >= 0 ? item.substring(colon + 1).trim() : item;
                if (!local.isEmpty()) walker.importedBindings.putIfAbsent(local, moduleSpec);
            }
        }
    }

    /**
     * Quick header sniff that classifies a source string as machine-generated. We look only at
     * the first ~512 characters -- enough to cover the leading banner comment that every code
     * generator I know of emits (ANTLR's {@code Generated from <grammar>.g4 by ANTLR}, Babel/
     * protoc/swagger's {@code @generated}, Apollo/GraphQL Code Generator's
     * {@code AUTO-GENERATED}, etc.). This is intentionally one-way: false positives are cheap
     * (one less file analysed, the user can rename the banner) and false negatives just mean
     * the file gets the normal deep parse.
     */
    private static boolean looksGenerated(String source) {
        int len = Math.min(source.length(), 512);
        String head = source.substring(0, len);
        return head.contains("Generated from")
                || head.contains("@generated")
                || head.contains("AUTO-GENERATED")
                || head.contains("Auto-generated")
                || head.contains("DO NOT EDIT")
                || head.contains("do not edit, manual changes will be overwritten");
    }

    private static String detectLanguage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tsx")) return "tsx";
        if (name.endsWith(".jsx")) return "jsx";
        if (name.endsWith(".ts")) return "typescript";
        if (name.endsWith(".mjs") || name.endsWith(".cjs")) return "javascript";
        return "javascript";
    }

    private static final class Walker {

        private static final Pattern EXPRESS_PATH = Pattern.compile("(?:'([^']+)'|\"([^\"]+)\"|`([^`]+)`)");

        // Receivers known to be HTTP CLIENT libraries -- calls on these go OUT to other services
        // and are NOT routes the current service exposes. Without this filter, the heuristic
        // `<ident>.<verb>(string, ...)` over-eagerly tags axios.post(url, body) as an Express
        // route. We match the bare names plus common variable suffixes (e.g. "userApi", "authClient").
        private static final Pattern CLIENT_RECEIVER = Pattern.compile(
                "^(?i)(axios|fetch|http|https|request|got|superagent|ky|nodeFetch|node_fetch)$"
                        + "|(?:Axios|Client|Service|Api|Http)$");

        // Sanity check on the first string argument. A real route path starts with '/' (Express)
        // or is a regex/wildcard like '*'. The previous version also accepted bare words like
        // `[\w:-]+`, which let `Promise.all([...]).then(... packageVersion.includes("dev") ...)`
        // false-positive as an `ANY dev` route (the first string literal in the AST was "dev",
        // verb `.all` matched the Express verb list, head `Promise` matched as an identifier).
        // Requiring a leading slash or wildcard kills that whole class of false positive while
        // keeping every realistic Express route -- bare-word routes like `app.get('login', ...)`
        // are non-standard and unlikely enough to lose.
        private static final Pattern ROUTE_PATH_SHAPE = Pattern.compile("^[/*][\\S]*$");

        // Outgoing-URL shape: absolute http(s) URL, leading-slash path, or a path with at least
        // one embedded slash like "api/cypher". Rejects whitespace and single-token strings that
        // are more likely header names ("Content-Type") or error messages.
        private static final Pattern HTTP_URL_SHAPE = Pattern.compile(
                "^https?://\\S+$|^/\\S*$|^\\S+/\\S+$");

        // Import-binding extraction. Three patterns handle the common ES module shapes:
        //   import { foo, bar as baz } from './x'
        //   import Default, { foo } from './x'
        //   import * as ns from './x'
        // Local names (`foo`, `baz`, `Default`, `ns`) are mapped to their module spec so that
        // a later call like `foo()` or `ns.helper()` can be resolved as cross-file.
        private static final Pattern IMPORT_NAMED = Pattern.compile(
                "import\\s+(?:type\\s+)?(?:[A-Za-z_$][\\w$]*\\s*,\\s*)?\\{([^}]*)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");
        private static final Pattern IMPORT_DEFAULT = Pattern.compile(
                "import\\s+(?:type\\s+)?([A-Za-z_$][\\w$]*)(?:\\s*,\\s*\\{[^}]*\\})?\\s+from\\s+['\"]([^'\"]+)['\"]");
        private static final Pattern IMPORT_NAMESPACE = Pattern.compile(
                "import\\s+(?:type\\s+)?\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");

        // CommonJS require() bindings, used by Node.js server-side code that hasn't moved to ESM.
        //   const Foo = require('./module')             -> Foo  -> './module'
        //   const { a, b: c } = require('./module')     -> a, c -> './module'
        // Matched with a multiline regex over the whole source rather than the AST since AST
        // walking the variable-declaration rule for every require() pattern would balloon the
        // case analysis. The text scan is a single regex pass over the source.
        private static final Pattern REQUIRE_DEFAULT = Pattern.compile(
                "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\(['\"]([^'\"]+)['\"]\\)");
        private static final Pattern REQUIRE_DESTRUCTURE = Pattern.compile(
                "(?:const|let|var)\\s+\\{([^}]+)\\}\\s*=\\s*require\\(['\"]([^'\"]+)['\"]\\)");

        // SQL detection. If a `<x>.<method>(stringArg)` call's first string starts with a SQL
        // verb, we treat the call as a database query and pull table names out of the SQL
        // regardless of the receiver name. Handles Node.js's sqlite3, kuzu, knex.raw, etc.,
        // which all accept SQL strings as their first argument.
        private static final Pattern SQL_QUERY_START = Pattern.compile(
                "(?is)^\\s*(SELECT|INSERT|UPDATE|DELETE|REPLACE|MERGE|WITH|CREATE|DROP|ALTER|TRUNCATE)\\b");
        private static final Pattern SQL_FROM_TABLE = Pattern.compile(
                "(?i)\\bFROM\\s+([\"`\\[]?[\\w.]+[\"`\\]]?)");
        private static final Pattern SQL_INTO_TABLE = Pattern.compile(
                "(?i)\\bINTO\\s+([\"`\\[]?[\\w.]+[\"`\\]]?)");
        private static final Pattern SQL_UPDATE_TABLE = Pattern.compile(
                "(?i)\\bUPDATE\\s+([\"`\\[]?[\\w.]+[\"`\\]]?)");
        private static final Pattern SQL_JOIN_TABLE = Pattern.compile(
                "(?i)\\bJOIN\\s+([\"`\\[]?[\\w.]+[\"`\\]]?)");
        private static final java.util.regex.Pattern SQL_WRITE_KEYWORD = Pattern.compile(
                "(?i)INSERT|UPDATE|DELETE|REPLACE|TRUNCATE|MERGE");

        private final ProjectContext ctx;
        private final NodeKey fileKey;
        private final String relPath;
        private final Consumer<GraphEvent> sink;
        private final Map<String, NodeKey> declarations = new LinkedHashMap<>();
        private final Set<String> seenImports = new HashSet<>();
        // Imported bindings: localName -> moduleSpec. Populated by emitImport from the import
        // statement text. When a call's receiver or callee name maps to an imported binding,
        // the call is cross-file and we emit a placeholder Method so the post-scan resolver
        // can rewire it (ScanCommand.resolveUnresolvedCalls / KuzuPostScan.resolveUnresolvedCalls).
        private final Map<String, String> importedBindings = new HashMap<>();
        // Lexical scope stack: keys pushed as we enter a Class / Method context and popped on
        // exit. Used to attribute SQL/HTTP-client calls to the enclosing scope (preferring
        // Method, then Class) instead of just the File.
        private final java.util.Deque<NodeKey> scopeStack = new java.util.ArrayDeque<>();

        Walker(ProjectContext ctx, NodeKey fileKey, String relPath, Consumer<GraphEvent> sink) {
            this.ctx = ctx;
            this.fileKey = fileKey;
            this.relPath = relPath;
            this.sink = sink;
        }

        void walk(ParseTree node) {
            NodeKey pushed = null;
            if (node instanceof TypeScriptParser.ClassDeclarationContext cd) {
                emitClass(cd);
                pushed = new NodeKey(ctx.projectId(), "Class", relPath + "::" + cd.identifier().getText());
                scopeStack.push(pushed);
            } else if (node instanceof TypeScriptParser.FunctionDeclarationContext fd) {
                emitFunction(fd);
                pushed = new NodeKey(ctx.projectId(), "Method", relPath + "::" + fd.identifier().getText());
                scopeStack.push(pushed);
            } else if (node instanceof TypeScriptParser.MethodDeclarationExpressionContext mde) {
                // Method body inside a class. emitClass already emitted the Method node; we push
                // its key here so SQL edges inside the body attribute back to the method.
                NodeKey enclosingClass = nearestScopeWithLabel("Class");
                if (enclosingClass != null) {
                    String mname = mde.propertyName().getText();
                    pushed = new NodeKey(ctx.projectId(), "Method", enclosingClass.fqName() + "." + mname);
                    scopeStack.push(pushed);
                }
            } else if (node instanceof TypeScriptParser.ConstructorDeclarationContext cdc) {
                // `class Foo { constructor(...) { ... } }`. The class iteration in emitClass
                // doesn't emit a Method for constructors (it only handles MethodDeclarationExpression),
                // so we emit + scope-push here. Using the conventional name "constructor" so the
                // edge graph cleanly identifies object-construction-time logic.
                NodeKey enclosingClass = nearestScopeWithLabel("Class");
                if (enclosingClass != null) {
                    String fqName = enclosingClass.fqName() + ".constructor";
                    NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);
                    if (!declarations.containsKey(fqName)) {
                        Map<String, Object> props = new HashMap<>();
                        props.put("name", "constructor");
                        props.put("fqName", fqName);
                        props.put("startLine", lineOf(cdc));
                        props.put("classId", enclosingClass.id());
                        props.put("isConstructor", true);
                        props.put("paramCount", (long) extractParamCount(cdc));
                        sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
                        sink.accept(new GraphEvent.EdgeUpsert(enclosingClass, "CONTAINS", methodKey, Map.of()));
                        declarations.put(fqName, methodKey);
                    }
                    pushed = methodKey;
                    scopeStack.push(pushed);
                }
            } else if (node instanceof TypeScriptParser.VariableDeclarationContext vd) {
                // const foo = () => {...}  or  const foo = function() {...}  or  const foo = async () => {...}
                // The grammar doesn't expose these as a distinct rule; we detect by looking at the
                // initializer's concatenated text. Cheap enough -- for variable declarations whose
                // RHS isn't an arrow/function the contains() check short-circuits fast.
                NodeKey mkey = tryEmitArrowOrFuncVar(vd);
                if (mkey != null) {
                    pushed = mkey;
                    scopeStack.push(pushed);
                }
            } else if (node instanceof TypeScriptParser.MethodPropertyContext mp) {
                // Object-literal method shorthand: `foo() { ... }`. This is how Vue Single-File
                // Components declare `methods: { handler() {...} }`, how pinia stores declare
                // actions, and how many config objects expose hooks. We emit each as a Method
                // and push it on the scope stack so SQL / HTTP / nested CALLS inside the body
                // attribute to this method rather than the bare File.
                TypeScriptParser.GeneratorMethodContext gm = mp.generatorMethod();
                if (gm != null && gm.propertyName() != null) {
                    String mname = gm.propertyName().getText();
                    String fqName = relPath + "::" + mname;
                    NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);
                    Map<String, Object> props = new HashMap<>();
                    props.put("name", mname);
                    props.put("fqName", fqName);
                    props.put("startLine", lineOf(mp));
                    props.put("fileId", fileKey.id());
                    props.put("paramCount", (long) extractParamCount(gm));
                    sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
                    sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", methodKey, Map.of()));
                    declarations.putIfAbsent(mname, methodKey);
                    declarations.put(fqName, methodKey);
                    pushed = methodKey;
                    scopeStack.push(pushed);
                }
            } else if (node instanceof TypeScriptParser.InterfaceDeclarationContext id) {
                emitInterface(id);
            } else if (node instanceof TypeScriptParser.ImportStatementContext is) {
                emitImport(is);
            } else if (node instanceof TypeScriptParser.ExpressionStatementContext es) {
                handleExpressionStatement(es);
            } else if (node instanceof TypeScriptParser.ArgumentsExpressionContext ae) {
                handleCallExpression(ae);
            } else if (node instanceof TypeScriptParser.TemplateStringLiteralContext tsl) {
                // Template literals (`...`) are tokenised atom-by-atom by the TS grammar so they
                // don't surface as a single TerminalNode. Read the rule's full text instead and
                // run the same SQL detection over it.
                String stripped = stripQuotes(tsl.getText());
                if (stripped != null && SQL_QUERY_START.matcher(stripped).find()) {
                    emitSqlTableEdges(stripped, lineOf(tsl));
                }
            } else if (node instanceof TerminalNode tn) {
                // Tree-wide SQL scan: any string literal that starts with a SQL verb is treated
                // as a query, regardless of where it appears in the AST. This catches the common
                // `const x = await db.get("SELECT ...")` pattern that ExpressionStatement-only
                // dispatch misses (a VariableStatement, not an ExpressionStatement). False
                // positives are bounded by SQL_QUERY_START requiring a leading SELECT/INSERT/etc.
                String t = tn.getText();
                if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'' || t.charAt(0) == '`')) {
                    String stripped = stripQuotes(t);
                    if (stripped != null && SQL_QUERY_START.matcher(stripped).find()) {
                        emitSqlTableEdges(stripped, tn.getSymbol().getLine());
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i));
            if (pushed != null) scopeStack.pop();
        }

        /** Topmost scope on the stack -- Method preferred over Class, falling back to the File. */
        private NodeKey currentScope() {
            for (NodeKey k : scopeStack) {
                if ("Method".equals(k.label())) return k;
            }
            for (NodeKey k : scopeStack) {
                if ("Class".equals(k.label())) return k;
            }
            return fileKey;
        }

        private NodeKey nearestScopeWithLabel(String label) {
            for (NodeKey k : scopeStack) {
                if (label.equals(k.label())) return k;
            }
            return null;
        }

        /**
         * Emit a CALLS edge for any {@code <singleExpression> arguments} call site visited
         * while a Method scope is on the stack. Resolution is intentionally coarse for a
         * scaffold: we take the rightmost dot-segment of the callee text as a simple name
         * and look it up in {@link #declarations} (populated by class/method/function
         * declarations earlier in the walk). This handles {@code this.foo()},
         * {@code SomeClass.foo()}, and bare {@code foo()} calls to a same-file function;
         * cross-file resolution and shadowing are out of scope. False resolutions are
         * possible when two declarations share a simple name (the first one wins); the
         * call-graph is approximate, not authoritative.
         */
        private void handleCallExpression(TypeScriptParser.ArgumentsExpressionContext ae) {
            NodeKey caller = nearestScopeWithLabel("Method");
            if (caller == null) return;

            String calleeText = ae.singleExpression().getText();
            int lastDot = calleeText.lastIndexOf('.');
            String calleeName = lastDot >= 0 ? calleeText.substring(lastDot + 1) : calleeText;
            int generic = calleeName.indexOf('<');
            if (generic >= 0) calleeName = calleeName.substring(0, generic);
            if (!calleeName.matches("[A-Za-z_$][\\w$]*")) return;

            // Count call arguments. Used both as part of the unresolved-placeholder fqName
            // (so the post-scan resolver matches against real Methods' paramCount) and saved
            // as an edge property for downstream inspection.
            int argCount = countCallArgs(ae);

            NodeKey callee = resolveCallee(calleeText, calleeName);

            if (callee == null) {
                // Unresolved locally: check if the call's IMMEDIATE receiver (or bare callee name)
                // is an imported binding. Emit a placeholder Method with the conventional
                // `unresolved.<name>:<arity>` fqName that the post-scan pass
                // (ScanCommand.resolveUnresolvedCalls / KuzuPostScan.resolveUnresolvedCalls)
                // already knows how to rewire to the real cross-file Method.
                //
                // The receiver MUST be a bare identifier directly equal to the imported binding.
                // For chained calls like {@code Axios.get('/x').catch(handler)} the receiver of
                // {@code .catch} is the Promise returned by {@code Axios.get(...)}, not the
                // {@code Axios} import itself -- we don't emit a placeholder there.
                String moduleSpec = null;
                if (lastDot >= 0) {
                    String immediateReceiver = calleeText.substring(0, lastDot);
                    if (immediateReceiver.matches("[A-Za-z_$][\\w$]*")) {
                        moduleSpec = importedBindings.get(immediateReceiver);
                    }
                } else {
                    // Bare call -- the callee name itself might be a named/default import.
                    moduleSpec = importedBindings.get(calleeName);
                }
                if (moduleSpec != null) {
                    String unresolvedFqName = "unresolved." + calleeName + ":" + argCount;
                    NodeKey placeholder = new NodeKey(ctx.projectId(), "Method", unresolvedFqName);
                    Map<String, Object> uprops = new HashMap<>();
                    uprops.put("name", calleeName);
                    uprops.put("fqName", unresolvedFqName);
                    uprops.put("paramCount", (long) argCount);
                    uprops.put("isExternal", true);
                    sink.accept(new GraphEvent.NodeUpsert(placeholder, uprops));
                    Map<String, Object> eprops = new HashMap<>();
                    eprops.put("callSiteLine", (long) lineOf(ae));
                    eprops.put("kind", "import");
                    eprops.put("via", moduleSpec);
                    sink.accept(new GraphEvent.EdgeUpsert(caller, "CALLS", placeholder, eprops));
                }
                return;
            }
            if (!"Method".equals(callee.label()) && !"Class".equals(callee.label())) return;
            if (caller.id().equals(callee.id())) return; // skip self-recursion edges for cleanliness

            Map<String, Object> props = new HashMap<>();
            props.put("callSiteLine", (long) lineOf(ae));
            sink.accept(new GraphEvent.EdgeUpsert(caller, "CALLS", callee, props));
        }

        /**
         * Local resolution chain for a call site:
         *   1. {@code this.foo} -> qualified lookup against the enclosing Class fqName.
         *   2. {@code KnownClass.foo} where {@code KnownClass} is a same-file Class declaration ->
         *      qualified lookup against {@code KnownClass.foo}.
         *   3. Bare {@code foo} -> simple-name lookup.
         * Returns null if none hit; the caller then tries import-based cross-file resolution.
         *
         * <p>Importantly, {@code obj.foo()} where {@code obj} is *not* a known same-file class
         * (a parameter, a local variable, an imported binding, …) deliberately does NOT fall
         * through to a simple-name lookup of {@code foo}. Doing so would mis-attribute calls
         * across receivers (e.g. {@code conn.query()} grabbing the local {@code Kuzu.query}
         * method). Better to leave it null and let import-based resolution take over.
         */
        private NodeKey resolveCallee(String calleeText, String calleeName) {
            if (calleeText.startsWith("this.")) {
                NodeKey cls = nearestScopeWithLabel("Class");
                if (cls != null) {
                    NodeKey hit = declarations.get(cls.fqName() + "." + calleeName);
                    if (hit != null) return hit;
                }
                return null;
            }
            if (calleeText.contains(".")) {
                int firstDot = calleeText.indexOf('.');
                String receiver = calleeText.substring(0, firstDot);
                NodeKey receiverDecl = declarations.get(receiver);
                if (receiverDecl != null && "Class".equals(receiverDecl.label())) {
                    NodeKey hit = declarations.get(receiverDecl.fqName() + "." + calleeName);
                    if (hit != null) return hit;
                }
                return null;
            }
            return declarations.get(calleeName);
        }

        private static int countCallArgs(TypeScriptParser.ArgumentsExpressionContext ae) {
            if (ae.arguments() == null) return 0;
            String args = ae.arguments().getText();
            // args text includes the surrounding parens; strip them before counting.
            if (args.startsWith("(") && args.endsWith(")")) {
                args = args.substring(1, args.length() - 1);
            }
            return countTopLevelArgs(args);
        }

        private void emitClass(TypeScriptParser.ClassDeclarationContext cd) {
            String name = cd.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey classKey = new NodeKey(ctx.projectId(), "Class", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", "class");
            props.put("startLine", lineOf(cd));
            props.put("endLine", endLineOf(cd));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(classKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", classKey, Map.of()));
            declarations.put(name, classKey);

            // Walk class body for methods.
            TypeScriptParser.ClassTailContext tail = cd.classTail();
            if (tail == null) return;
            for (TypeScriptParser.ClassElementContext el : tail.classElement()) {
                if (el.propertyMemberDeclaration() != null) {
                    TypeScriptParser.PropertyMemberDeclarationContext pmd = el.propertyMemberDeclaration();
                    if (pmd instanceof TypeScriptParser.MethodDeclarationExpressionContext mde) {
                        String mname = mde.propertyName().getText();
                        emitMethodNode(mname, classKey, mde);
                    }
                }
            }
        }

        private void emitMethodNode(String name, NodeKey classKey, ParserRuleContext src) {
            String fqName = classKey.fqName() + "." + name;
            NodeKey methodKey = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(src));
            props.put("classId", classKey.id());
            props.put("paramCount", (long) extractParamCount(src));
            sink.accept(new GraphEvent.NodeUpsert(methodKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(classKey, "CONTAINS", methodKey, Map.of()));
            // Index by both the qualified name and the simple name. The qualified entry lets
            // `this.foo()` (inside class Bar) resolve to Bar.foo deterministically; the simple
            // entry is the cheap fallback for bare calls and `OtherClass.foo()` lookups.
            declarations.put(fqName, methodKey);
            declarations.putIfAbsent(classKey.fqName() + "." + name, methodKey);
            declarations.putIfAbsent(name, methodKey);
        }

        private void emitFunction(TypeScriptParser.FunctionDeclarationContext fd) {
            String name = fd.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(fd));
            props.put("endLine", endLineOf(fd));
            props.put("fileId", fileKey.id());
            props.put("paramCount", (long) extractParamCount(fd));
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            declarations.put(fqName, key);
            declarations.putIfAbsent(name, key);
        }

        /**
         * Promote a variable-declaration shaped like {@code const foo = (...) => {...}} or
         * {@code const foo = function(...) {...}} into a tracked Method. Caller pushes the
         * returned key onto the scope stack so SQL/HTTP/CALLS edges inside the body attribute
         * to {@code foo} rather than the bare file.
         */
        private NodeKey tryEmitArrowOrFuncVar(TypeScriptParser.VariableDeclarationContext vd) {
            if (vd.identifierOrKeyWord() == null) return null;
            String name = vd.identifierOrKeyWord().getText();
            if (name == null || !name.matches("[A-Za-z_$][\\w$]*")) return null;

            String rhs = vd.getText();
            boolean isArrowOrFunc = rhs.contains("=>")
                    || rhs.contains("=function")
                    || rhs.contains("=asyncfunction")
                    || rhs.contains("=async(");
            if (!isArrowOrFunc) return null;

            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Method", fqName);
            // If a same-name top-level declaration already exists, don't re-emit; just return
            // its key so the scope is still tracked.
            if (declarations.containsKey(fqName)) return key;

            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("startLine", lineOf(vd));
            props.put("fileId", fileKey.id());
            props.put("paramCount", (long) extractParamCount(vd));
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
            declarations.put(fqName, key);
            declarations.putIfAbsent(name, key);
            return key;
        }

        /**
         * Count the formal parameters of a function/method declaration by scanning its rule
         * text for the first balanced {@code ( ... )} pair and splitting on top-level commas.
         * Generics / array literals / object literals nested inside default values are
         * tracked via paren/bracket/brace depth so commas inside them don't inflate the count.
         */
        private static int extractParamCount(ParserRuleContext rule) {
            String text = rule.getText();
            int open = text.indexOf('(');
            if (open < 0) return 0;
            int depth = 0, close = -1;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) { close = i; break; } }
            }
            if (close < 0) return 0;
            return countTopLevelArgs(text.substring(open + 1, close));
        }

        private static int countTopLevelArgs(String inside) {
            if (inside == null || inside.isBlank()) return 0;
            int depth = 0, count = 1;
            for (int i = 0; i < inside.length(); i++) {
                char c = inside.charAt(i);
                if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
                else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
                else if (c == ',' && depth == 0) count++;
            }
            return count;
        }

        private void emitInterface(TypeScriptParser.InterfaceDeclarationContext id) {
            String name = id.identifier().getText();
            String fqName = relPath + "::" + name;
            NodeKey key = new NodeKey(ctx.projectId(), "Interface", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", name);
            props.put("fqName", fqName);
            props.put("kind", "interface");
            props.put("startLine", lineOf(id));
            props.put("endLine", endLineOf(id));
            props.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        }

        private void emitImport(TypeScriptParser.ImportStatementContext is) {
            TypeScriptParser.ImportFromBlockContext block = is.importFromBlock();
            if (block == null) return;
            String target;
            if (block.StringLiteral() != null) {
                target = stripQuotes(block.StringLiteral().getText());
            } else if (block.importFrom() != null) {
                target = stripQuotes(block.importFrom().StringLiteral().getText());
            } else {
                return;
            }
            if (target == null || target.isBlank()) return;
            // Always record bindings even if the module was seen before (for re-imports of a
            // module under different local names -- rare but legal). Use the ORIGINAL source
            // substring (with whitespace) rather than ctx.getText(), which concatenates tokens
            // and would defeat the `\s+`-anchored import regexes.
            recordImportBindings(originalText(is), target);
            if (!seenImports.add(target)) return;
            NodeKey mod = new NodeKey(ctx.projectId(), "Module", target);
            Map<String, Object> props = new HashMap<>();
            props.put("name", target);
            props.put("fqName", target);
            sink.accept(new GraphEvent.NodeUpsert(mod, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "IMPORTS", mod, Map.of()));
        }

        /**
         * Pull local-binding names out of an import statement's text. Populates
         * {@link #importedBindings} as {@code localName -> moduleSpec}. Cheap regex pass --
         * the alternative (walking the AST's importDefault/importNamed/importNamespace rules
         * by class) trades verbosity for marginal accuracy on a static grammar.
         */
        private void recordImportBindings(String stmtText, String moduleSpec) {
            Matcher named = IMPORT_NAMED.matcher(stmtText);
            if (named.find()) {
                for (String part : named.group(1).split(",")) {
                    String item = part.trim();
                    if (item.isEmpty()) continue;
                    // Handle `foo as bar` -> local name is `bar`.
                    int asIdx = item.toLowerCase(Locale.ROOT).indexOf(" as ");
                    String local = asIdx >= 0 ? item.substring(asIdx + 4).trim() : item;
                    if (!local.isEmpty()) importedBindings.putIfAbsent(local, moduleSpec);
                }
            }
            Matcher def = IMPORT_DEFAULT.matcher(stmtText);
            if (def.find()) {
                importedBindings.putIfAbsent(def.group(1), moduleSpec);
            }
            Matcher ns = IMPORT_NAMESPACE.matcher(stmtText);
            if (ns.find()) {
                importedBindings.putIfAbsent(ns.group(1), moduleSpec);
            }
        }

        /**
         * Inspect a top-level {@code <ident>.<method>(stringArg, ...)} call and classify it as one of:
         * <ul>
         *   <li><b>SQL query</b> (first string arg starts with SELECT/INSERT/UPDATE/...)
         *       -> emit {@code Table} nodes + {@code READS_TABLE}/{@code WRITES_TABLE} edges.</li>
         *   <li><b>HTTP client call</b> (receiver matches axios/fetch/etc.)
         *       -> emit {@code HttpCall} node + {@code CALLS_HTTP} edge.</li>
         *   <li><b>Express route</b> (receiver is an arbitrary router, path looks REST-shaped)
         *       -> emit {@code ApiEndpoint} node + {@code EXPOSES} edge.</li>
         * </ul>
         * Dispatch is by SHAPE of the first string argument, not the receiver name, so the same
         * receiver variable can host different kinds of calls without ambiguity.
         */
        private void handleExpressionStatement(TypeScriptParser.ExpressionStatementContext es) {
            String text = es.getText();
            int dot = text.indexOf('.');
            if (dot <= 0) return;
            int paren = text.indexOf('(', dot);
            if (paren < 0) return;
            String head = text.substring(0, dot);
            if (!head.matches("[A-Za-z_$][\\w$]*")) return;
            String verb = text.substring(dot + 1, paren);
            int generic = verb.indexOf('<');
            if (generic >= 0) verb = verb.substring(0, generic);
            String verbLower = verb.toLowerCase(Locale.ROOT);

            String firstString = findFirstStringLiteralInChildren(es);
            if (firstString == null || firstString.isEmpty()) return;

            // SQL queries get picked up by the tree-wide string-literal scan in walk() rather
            // than the call-shape dispatch here, since most SQL calls are inside variable
            // declarations (const x = await db.get('SELECT ...')) and never reach this code.
            // A SQL string still falls through ROUTE_PATH_SHAPE below and is silently rejected.

            // The remaining branches all require an HTTP verb on the call.
            Set<String> httpVerbs = Set.of("get", "post", "put", "delete", "patch", "head", "options", "all");
            if (!httpVerbs.contains(verbLower)) return;
            String httpMethod = verbLower.equals("all") ? "ANY" : verbLower.toUpperCase(Locale.ROOT);

            // 2. HTTP client call -- receiver matches axios/fetch/*Client/etc.
            if (CLIENT_RECEIVER.matcher(head).find()) {
                if (HTTP_URL_SHAPE.matcher(firstString).matches()) {
                    String callKey = httpMethod + " " + firstString;
                    NodeKey callNode = new NodeKey(ctx.projectId(), "HttpCall", callKey);
                    Map<String, Object> callProps = new HashMap<>();
                    callProps.put("name", callKey);
                    callProps.put("fqName", callKey);
                    callProps.put("httpMethod", httpMethod);
                    callProps.put("path", firstString);
                    callProps.put("framework", head);
                    callProps.put("startLine", lineOf(es));
                    sink.accept(new GraphEvent.NodeUpsert(callNode, callProps));
                    sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CALLS_HTTP", callNode, Map.of()));
                }
                return;
            }

            // 3. Express route declaration.
            if (!ROUTE_PATH_SHAPE.matcher(firstString).matches()) return;
            String endpointName = httpMethod + " " + firstString;
            NodeKey endpointKey = new NodeKey(ctx.projectId(), "ApiEndpoint", endpointName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", endpointName);
            props.put("fqName", endpointName);
            props.put("httpMethod", httpMethod);
            props.put("path", firstString);
            props.put("framework", "express");
            props.put("startLine", lineOf(es));
            sink.accept(new GraphEvent.NodeUpsert(endpointKey, props));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, "EXPOSES", endpointKey, Map.of()));
        }

        /**
         * Extract table identifiers from a SQL string via regex (FROM/INTO/UPDATE/JOIN) and emit
         * Table nodes plus a READS_TABLE or WRITES_TABLE edge from the current File. Read/write
         * is decided by the first SQL keyword. The Table NodeKey uses the lowercased table name
         * so multiple casings of the same table collapse, matching the SQL parser's convention.
         */
        private void emitSqlTableEdges(String sql, int line) {
            Matcher firstKw = SQL_QUERY_START.matcher(sql);
            if (!firstKw.find()) return;
            boolean isWrite = SQL_WRITE_KEYWORD.matcher(firstKw.group(1)).matches();
            String edgeType = isWrite ? "WRITES_TABLE" : "READS_TABLE";

            Set<String> tables = new LinkedHashSet<>();
            scanTableNames(sql, SQL_FROM_TABLE, tables);
            scanTableNames(sql, SQL_INTO_TABLE, tables);
            scanTableNames(sql, SQL_UPDATE_TABLE, tables);
            scanTableNames(sql, SQL_JOIN_TABLE, tables);

            NodeKey source = currentScope();
            for (String tableName : tables) {
                String normalized = tableName.toLowerCase(Locale.ROOT);
                NodeKey tableKey = new NodeKey(ctx.projectId(), "Table", normalized);
                Map<String, Object> tprops = new HashMap<>();
                tprops.put("name", tableName);
                tprops.put("fqName", tableName);
                sink.accept(new GraphEvent.NodeUpsert(tableKey, tprops));
                Map<String, Object> edgeProps = new HashMap<>();
                edgeProps.put("callSiteLine", (long) line);
                sink.accept(new GraphEvent.EdgeUpsert(source, edgeType, tableKey, edgeProps));
            }
        }

        private static void scanTableNames(String sql, Pattern p, Set<String> out) {
            Matcher m = p.matcher(sql);
            while (m.find()) {
                // Strip optional quoting around identifiers ("foo", `foo`, [foo]).
                String name = m.group(1).replaceAll("[\"`\\[\\]]", "");
                // Take only the last segment of dotted names (schema.table -> table).
                int idx = name.lastIndexOf('.');
                if (idx >= 0) name = name.substring(idx + 1);
                if (!name.isEmpty()) out.add(name);
            }
        }

        private static String findFirstStringLiteralInChildren(ParseTree node) {
            if (node instanceof TerminalNode tn) {
                String t = tn.getText();
                if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'' || t.charAt(0) == '`')) {
                    return stripQuotes(t);
                }
                return null;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                String s = findFirstStringLiteralInChildren(node.getChild(i));
                if (s != null) return s;
            }
            return null;
        }

        /**
         * Original source substring for a parser rule, whitespace preserved. {@code ctx.getText()}
         * concatenates tokens directly, which is useless for any regex that anchors on whitespace
         * boundaries (our import-binding patterns, comma-separated parameter scans, etc.).
         */
        private static String originalText(ParserRuleContext ctx) {
            if (ctx.start == null || ctx.stop == null) return ctx.getText();
            org.antlr.v4.runtime.misc.Interval iv =
                    new org.antlr.v4.runtime.misc.Interval(ctx.start.getStartIndex(), ctx.stop.getStopIndex());
            return ctx.start.getInputStream().getText(iv);
        }

        private static String stripQuotes(String s) {
            if (s == null) return null;
            if (s.length() >= 2) {
                char f = s.charAt(0);
                char l = s.charAt(s.length() - 1);
                if ((f == '"' || f == '\'' || f == '`') && f == l) return s.substring(1, s.length() - 1);
            }
            return s;
        }

        private static int lineOf(ParserRuleContext c) {
            Token t = c.getStart();
            return t != null ? t.getLine() : 0;
        }

        private static int endLineOf(ParserRuleContext c) {
            Token t = c.getStop();
            return t != null ? t.getLine() : 0;
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            log.debug("TS ANTLR syntax error at {}:{} — {}", line, charPositionInLine, msg);
        }
    }
}
