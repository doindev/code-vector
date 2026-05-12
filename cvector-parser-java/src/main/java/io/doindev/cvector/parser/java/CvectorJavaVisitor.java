package io.doindev.cvector.parser.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.ProjectContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

class CvectorJavaVisitor extends VoidVisitorAdapter<Void> {

    private static final Map<String, String> HTTP_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );
    private static final Set<String> REQUEST_MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate"
    );
    private static final Set<String> QUEUE_LISTENER_ANNOTATIONS = Set.of(
            "KafkaListener", "RabbitListener", "JmsListener", "SqsListener",
            "StreamListener", "ServiceActivator", "EventListener"
    );
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of(
            "Scheduled", "Schedules"
    );

    private final ProjectContext ctx;
    private final NodeKey fileKey;
    private final Consumer<GraphEvent> sink;
    private NodeKey currentClass;
    private NodeKey currentMethod;
    private String currentBasePath = "";
    private Map<String, String> classLocalMethods = Map.of();

    CvectorJavaVisitor(ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        this.ctx = ctx;
        this.fileKey = fileKey;
        this.sink = sink;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        String fqName = n.getFullyQualifiedName().orElse(n.getNameAsString());
        NodeKey key = emitClass(fqName, n.getNameAsString(), n.isInterface() ? "interface" : "class",
                n.getRange().map(r -> r.begin.line).orElse(0),
                n.getRange().map(r -> r.end.line).orElse(0), n.getAnnotations());
        for (var ext : n.getExtendedTypes()) {
            tryResolveClass(ext.getNameAsString()).ifPresent(t -> emitEdge(key, "EXTENDS", t, Map.of()));
        }
        for (var impl : n.getImplementedTypes()) {
            tryResolveClass(impl.getNameAsString()).ifPresent(t -> emitEdge(key, "IMPLEMENTS", t, Map.of()));
        }
        // Direct extends/implements above already produces the inheritance edges we use. The transitive
        // ancestors path requires JavaSymbolSolver and is by far the hottest source of resolve() exceptions
        // when supertypes live in JARs we can't load. Skipping it costs us only derived edges that Cypher
        // path queries can recover when needed.
        String prevBase = currentBasePath;
        String classPath = extractMappingPath(n.getAnnotations());
        if (classPath != null) currentBasePath = combinePath(prevBase, classPath);
        Map<String, String> savedLocals = classLocalMethods;
        classLocalMethods = collectMethodIndex(n, fqName);
        withinClass(key, () -> super.visit(n, arg));
        classLocalMethods = savedLocals;
        currentBasePath = prevBase;
    }

    @Override
    public void visit(RecordDeclaration n, Void arg) {
        String fqName = n.getFullyQualifiedName().orElse(n.getNameAsString());
        NodeKey key = emitClass(fqName, n.getNameAsString(), "record",
                n.getRange().map(r -> r.begin.line).orElse(0),
                n.getRange().map(r -> r.end.line).orElse(0), n.getAnnotations());
        Map<String, String> savedLocals = classLocalMethods;
        classLocalMethods = collectMethodIndex(n, fqName);
        withinClass(key, () -> super.visit(n, arg));
        classLocalMethods = savedLocals;
    }

    @Override
    public void visit(EnumDeclaration n, Void arg) {
        String fqName = n.getFullyQualifiedName().orElse(n.getNameAsString());
        NodeKey key = emitClass(fqName, n.getNameAsString(), "enum",
                n.getRange().map(r -> r.begin.line).orElse(0),
                n.getRange().map(r -> r.end.line).orElse(0), n.getAnnotations());
        Map<String, String> savedLocals = classLocalMethods;
        classLocalMethods = collectMethodIndex(n, fqName);
        withinClass(key, () -> super.visit(n, arg));
        classLocalMethods = savedLocals;
    }

    @Override
    public void visit(AnnotationDeclaration n, Void arg) {
        String fqName = n.getFullyQualifiedName().orElse(n.getNameAsString());
        NodeKey key = emitClass(fqName, n.getNameAsString(), "annotation",
                n.getRange().map(r -> r.begin.line).orElse(0),
                n.getRange().map(r -> r.end.line).orElse(0), n.getAnnotations());
        withinClass(key, () -> super.visit(n, arg));
    }

    private static Map<String, String> collectMethodIndex(
            com.github.javaparser.ast.body.TypeDeclaration<?> typeDecl, String typeFqName) {
        Map<String, String> out = new java.util.HashMap<>();
        for (MethodDeclaration m : typeDecl.getMethods()) {
            String key = m.getNameAsString() + ":" + m.getParameters().size();
            // Avoid resolve() in this hot helper; the fallback signature matches what
            // visit(MethodDeclaration) emits for the Method node.
            out.putIfAbsent(key, typeFqName + "." + m.getSignature().asString());
        }
        return out;
    }

    @Override
    public void visit(MethodDeclaration n, Void arg) {
        if (currentClass == null) { super.visit(n, arg); return; }
        // Skip resolve() — fallback signature uses simple types but is deterministic and matches what
        // collectMethodIndex / call placeholders produce.
        String signature = currentClass.fqName() + "." + n.getSignature().asString();
        NodeKey key = new NodeKey(ctx.projectId(), "Method", signature);
        Map<String, Object> props = new HashMap<>();
        props.put("name", n.getNameAsString());
        props.put("fqName", signature);
        props.put("signature", n.getSignature().asString());
        props.put("returnType", n.getTypeAsString());
        props.put("startLine", n.getRange().map(r -> r.begin.line).orElse(0));
        props.put("endLine", n.getRange().map(r -> r.end.line).orElse(0));
        props.put("isStatic", n.isStatic());
        props.put("visibility", n.getAccessSpecifier().asString());
        props.put("paramCount", n.getParameters().size());
        props.put("fileId", fileKey.id());
        props.put("classId", currentClass.id());
        if (hasAnyAnnotation(n.getAnnotations(), TEST_ANNOTATIONS)) props.put("isTest", true);
        if (hasAnyAnnotation(n.getAnnotations(), QUEUE_LISTENER_ANNOTATIONS)) props.put("isQueueListener", true);
        if (hasAnyAnnotation(n.getAnnotations(), SCHEDULED_ANNOTATIONS)) props.put("isScheduled", true);
        sink.accept(new GraphEvent.NodeUpsert(key, props));
        sink.accept(new GraphEvent.EdgeUpsert(currentClass, "CONTAINS", key, Map.of()));

        emitApiEndpointIfPresent(n, key);

        withinMethod(key, () -> super.visit(n, arg));
    }

    @Override
    public void visit(ConstructorDeclaration n, Void arg) {
        if (currentClass == null) { super.visit(n, arg); return; }
        String signature = currentClass.fqName() + "." + n.getSignature().asString();
        NodeKey key = new NodeKey(ctx.projectId(), "Method", signature);
        Map<String, Object> props = new HashMap<>();
        props.put("name", "<init>");
        props.put("fqName", signature);
        props.put("signature", n.getSignature().asString());
        props.put("returnType", currentClass.fqName());
        props.put("startLine", n.getRange().map(r -> r.begin.line).orElse(0));
        props.put("endLine", n.getRange().map(r -> r.end.line).orElse(0));
        props.put("isStatic", false);
        props.put("visibility", n.getAccessSpecifier().asString());
        props.put("isConstructor", true);
        props.put("paramCount", n.getParameters().size());
        props.put("fileId", fileKey.id());
        props.put("classId", currentClass.id());
        sink.accept(new GraphEvent.NodeUpsert(key, props));
        sink.accept(new GraphEvent.EdgeUpsert(currentClass, "CONTAINS", key, Map.of()));
        withinMethod(key, () -> super.visit(n, arg));
    }

    @Override
    public void visit(FieldDeclaration n, Void arg) {
        if (currentClass == null) { super.visit(n, arg); return; }
        for (VariableDeclarator v : n.getVariables()) {
            String fqName = currentClass.fqName() + "." + v.getNameAsString();
            NodeKey key = new NodeKey(ctx.projectId(), "Field", fqName);
            Map<String, Object> props = new HashMap<>();
            props.put("name", v.getNameAsString());
            props.put("fqName", fqName);
            props.put("type", n.getElementType().asString());
            props.put("startLine", n.getRange().map(r -> r.begin.line).orElse(0));
            props.put("isStatic", n.isStatic());
            props.put("visibility", n.getAccessSpecifier().asString());
            props.put("fileId", fileKey.id());
            props.put("classId", currentClass.id());
            sink.accept(new GraphEvent.NodeUpsert(key, props));
            sink.accept(new GraphEvent.EdgeUpsert(currentClass, "CONTAINS", key, Map.of()));
        }
        super.visit(n, arg);
    }

    @Override
    public void visit(MethodCallExpr n, Void arg) {
        super.visit(n, arg);
        if (currentMethod == null) return;
        // We skip JavaSymbolSolver.resolve() here — it's the hottest reflection path during scan, and
        // every failure throws a fully-traced RuntimeException. Same-class calls resolve cheaply via
        // classLocalMethods; cross-file calls become `unresolved.<name>:<arity>` placeholders which a
        // post-scan Cypher pass rewires to the real Method nodes by name+arity match.
        String name = n.getNameAsString();
        int arity = n.getArguments().size();
        double confidence;
        String calleeFq = null;
        if (n.getScope().isEmpty()) {
            String local = classLocalMethods.get(name + ":" + arity);
            if (local != null) {
                calleeFq = local;
                confidence = 0.8;
            } else {
                confidence = 0.5;
            }
        } else {
            confidence = 0.5;
        }
        if (calleeFq == null) {
            calleeFq = "unresolved." + name + ":" + arity;
        }
        NodeKey callee = new NodeKey(ctx.projectId(), "Method", calleeFq);
        emitNodeStub(callee, name, arity);
        Map<String, Object> props = new HashMap<>();
        props.put("confidence", confidence);
        props.put("callSiteLine", n.getRange().map(r -> r.begin.line).orElse(0));
        sink.accept(new GraphEvent.EdgeUpsert(currentMethod, "CALLS", callee, props));
    }

    @Override
    public void visit(MethodReferenceExpr n, Void arg) {
        super.visit(n, arg);
        if (currentMethod == null) return;
        String name = n.getIdentifier();
        double confidence = 0.5;
        String calleeFq = null;
        String scopeName;
        if (n.getScope() instanceof NameExpr ne) {
            scopeName = ne.getNameAsString();
        } else if (n.getScope() instanceof com.github.javaparser.ast.expr.TypeExpr te) {
            scopeName = te.getType().asString();
        } else {
            scopeName = null;
        }
        boolean isThis = n.getScope() instanceof ThisExpr || "this".equals(scopeName);
        boolean isCurrentClassName = currentClass != null && scopeName != null
                && scopeName.equals(simpleName(currentClass.fqName()));
        if (isThis || isCurrentClassName) {
            for (Map.Entry<String, String> e2 : classLocalMethods.entrySet()) {
                if (e2.getKey().startsWith(name + ":")) {
                    calleeFq = e2.getValue();
                    confidence = 0.7;
                    break;
                }
            }
        }
        if (calleeFq == null) {
            calleeFq = "unresolved." + name + ".ref";
        }
        NodeKey callee = new NodeKey(ctx.projectId(), "Method", calleeFq);
        emitNodeStub(callee, name);
        Map<String, Object> props = new HashMap<>();
        props.put("confidence", confidence);
        props.put("callSiteLine", n.getRange().map(r -> r.begin.line).orElse(0));
        props.put("viaMethodReference", true);
        sink.accept(new GraphEvent.EdgeUpsert(currentMethod, "CALLS", callee, props));
    }

    private static String simpleName(String fqName) {
        int dot = fqName.lastIndexOf('.');
        return dot < 0 ? fqName : fqName.substring(dot + 1);
    }

    @Override
    public void visit(ObjectCreationExpr n, Void arg) {
        super.visit(n, arg);
        if (currentMethod == null) return;
        // Post-pass will try to resolve <init>.<TypeName>:<arity> against project constructors.
        int arity = n.getArguments().size();
        String calleeFq = "unresolved.<init>." + n.getType().getNameAsString() + ":" + arity;
        double confidence = 0.5;
        NodeKey callee = new NodeKey(ctx.projectId(), "Method", calleeFq);
        emitNodeStub(callee, "<init>");
        Map<String, Object> props = new HashMap<>();
        props.put("confidence", confidence);
        props.put("callSiteLine", n.getRange().map(r -> r.begin.line).orElse(0));
        sink.accept(new GraphEvent.EdgeUpsert(currentMethod, "CALLS", callee, props));
    }

    private NodeKey emitClass(String fqName, String name, String kind, int startLine, int endLine,
                              NodeList<AnnotationExpr> annotations) {
        NodeKey key = new NodeKey(ctx.projectId(), "Class", fqName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", fqName);
        props.put("kind", kind);
        props.put("startLine", startLine);
        props.put("endLine", endLine);
        props.put("fileId", fileKey.id());
        if (hasAnyAnnotation(annotations, Set.of("RestController", "Controller"))) {
            props.put("isController", true);
        }
        if (hasAnyAnnotation(annotations, Set.of("Service"))) props.put("isService", true);
        if (hasAnyAnnotation(annotations, Set.of("Repository"))) props.put("isRepository", true);
        if (hasAnyAnnotation(annotations, Set.of("Component", "Configuration"))) props.put("isSpringBean", true);
        sink.accept(new GraphEvent.NodeUpsert(key, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", key, Map.of()));
        return key;
    }

    private void emitApiEndpointIfPresent(MethodDeclaration n, NodeKey methodKey) {
        if (currentClass == null) return;
        String httpMethod = detectHttpMethod(n.getAnnotations());
        if (httpMethod == null) return;
        String methodPath = extractMappingPath(n.getAnnotations());
        String fullPath = combinePath(currentBasePath, methodPath == null ? "" : methodPath);
        if (fullPath.isEmpty()) fullPath = "/";
        String endpointFq = httpMethod + " " + fullPath;
        NodeKey endpointKey = new NodeKey(ctx.projectId(), "ApiEndpoint", endpointFq);
        Map<String, Object> props = new HashMap<>();
        props.put("name", endpointFq);
        props.put("fqName", endpointFq);
        props.put("httpMethod", httpMethod);
        props.put("path", fullPath);
        props.put("fileId", fileKey.id());
        props.put("classId", currentClass.id());
        props.put("methodId", methodKey.id());
        sink.accept(new GraphEvent.NodeUpsert(endpointKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(currentClass, "EXPOSES", endpointKey, Map.of()));
        sink.accept(new GraphEvent.EdgeUpsert(endpointKey, "HANDLES", methodKey, Map.of()));
    }

    private static String detectHttpMethod(NodeList<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            String name = a.getNameAsString();
            if (HTTP_ANNOTATIONS.containsKey(name)) return HTTP_ANNOTATIONS.get(name);
            if ("RequestMapping".equals(name)) {
                if (a instanceof NormalAnnotationExpr na) {
                    for (MemberValuePair p : na.getPairs()) {
                        if ("method".equals(p.getNameAsString())) {
                            String v = p.getValue().toString();
                            return v.substring(v.lastIndexOf('.') + 1).toUpperCase();
                        }
                    }
                }
                return "ANY";
            }
        }
        return null;
    }

    private static String extractMappingPath(NodeList<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            String name = a.getNameAsString();
            if (!REQUEST_MAPPING_ANNOTATIONS.contains(name)) continue;
            if (a instanceof SingleMemberAnnotationExpr s) {
                return literalString(s.getMemberValue());
            }
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair p : na.getPairs()) {
                    String pn = p.getNameAsString();
                    if ("value".equals(pn) || "path".equals(pn)) {
                        return literalString(p.getValue());
                    }
                }
                return "";
            }
        }
        return null;
    }

    private static String literalString(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.asString();
        if (e instanceof ArrayInitializerExpr arr && !arr.getValues().isEmpty()) {
            return literalString(arr.getValues().get(0));
        }
        return e.toString().replace("\"", "");
    }

    private static String combinePath(String base, String suffix) {
        if (base == null || base.isEmpty()) return normalizePath(suffix);
        if (suffix == null || suffix.isEmpty()) return normalizePath(base);
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String s = suffix.startsWith("/") ? suffix : "/" + suffix;
        return normalizePath(b + s);
    }

    private static String normalizePath(String p) {
        if (p == null || p.isEmpty()) return "";
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    private static boolean hasAnyAnnotation(NodeList<AnnotationExpr> annotations, Set<String> names) {
        for (AnnotationExpr a : annotations) {
            if (names.contains(a.getNameAsString())) return true;
        }
        return false;
    }

    private java.util.Optional<NodeKey> tryResolveClass(String simpleOrFq) {
        NodeKey k = new NodeKey(ctx.projectId(), "Class", simpleOrFq);
        emitNodeStub(k, simpleOrFq);
        return java.util.Optional.of(k);
    }

    private void emitNodeStub(NodeKey key, String name) {
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", key.fqName());
        sink.accept(new GraphEvent.NodeUpsert(key, props));
    }

    private void emitNodeStub(NodeKey key, String name, int paramCount) {
        Map<String, Object> props = new HashMap<>();
        props.put("name", name);
        props.put("fqName", key.fqName());
        props.put("paramCount", paramCount);
        sink.accept(new GraphEvent.NodeUpsert(key, props));
    }

    private void emitEdge(NodeKey from, String type, NodeKey to, Map<String, Object> props) {
        sink.accept(new GraphEvent.EdgeUpsert(from, type, to, props));
    }

    private void withinClass(NodeKey key, Runnable body) {
        NodeKey prev = currentClass;
        currentClass = key;
        try { body.run(); } finally { currentClass = prev; }
    }

    private void withinMethod(NodeKey key, Runnable body) {
        NodeKey prev = currentMethod;
        currentMethod = key;
        try { body.run(); } finally { currentMethod = prev; }
    }
}
