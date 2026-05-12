package io.doindev.cvector.parser.config;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PomParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(PomParserAdapter.class);
    private static final int MAX_PARENT_WALK = 10;
    private static final int MAX_PROPERTY_INTERPOLATION = 10;
    private static final int MAX_BOM_DEPTH = 6;

    /** BOM (bill-of-materials) lookup cache shared across all pom.xml parses in a scan. Concurrent because the parser walk runs in parallel. */
    private final Map<String, Map<String, String>> bomCache = new ConcurrentHashMap<>();

    @Override
    public String name() { return "maven-pom"; }

    @Override
    public Set<String> supportedExtensions() { return Set.of("xml"); }

    @Override
    public boolean accepts(Path file) {
        return "pom.xml".equals(file.getFileName().toString());
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "maven-pom");
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        Model model;
        try (InputStream in = Files.newInputStream(file)) {
            model = new MavenXpp3Reader().read(in);
        } catch (IOException | RuntimeException | org.codehaus.plexus.util.xml.pull.XmlPullParserException e) {
            log.warn("failed to parse {}: {}", file, e.getMessage());
            return;
        }

        Map<String, String> resolvedProps = collectProperties(file, model);
        Map<String, String> managedVersions = collectAllManagedVersions(file, model, resolvedProps);

        NodeKey projectKey = new NodeKey(ctx.projectId(), "Project", ctx.projectId());
        Map<String, Object> projProps = new HashMap<>();
        projProps.put("name", ctx.projectName());
        projProps.put("fqName", ctx.projectId());
        projProps.put("rootPath", ctx.rootPath().toString());
        sink.accept(new GraphEvent.NodeUpsert(projectKey, projProps));

        if (model.getDependencies() != null) {
            for (Dependency d : model.getDependencies()) {
                emitDependency(d, ctx, projectKey, fileKey, sink, "compile", resolvedProps, managedVersions);
            }
        }
        if (model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null) {
            for (Dependency d : model.getDependencyManagement().getDependencies()) {
                emitDependency(d, ctx, projectKey, fileKey, sink, "managed", resolvedProps, managedVersions);
            }
        }
    }

    private Map<String, String> collectProperties(Path pomFile, Model model) {
        Map<String, String> props = new HashMap<>();
        Path current = pomFile;
        Model currentModel = model;
        int depth = 0;

        while (currentModel != null && depth < MAX_PARENT_WALK) {
            if (currentModel.getProperties() != null) {
                for (Map.Entry<Object, Object> e : currentModel.getProperties().entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String value = String.valueOf(e.getValue());
                    props.putIfAbsent(key, value);
                }
            }
            String selfVersion = currentModel.getVersion();
            if (selfVersion == null && currentModel.getParent() != null) {
                selfVersion = currentModel.getParent().getVersion();
            }
            if (selfVersion != null) {
                props.putIfAbsent("project.version", selfVersion);
                props.putIfAbsent("pom.version", selfVersion);
            }
            String selfGroup = currentModel.getGroupId();
            if (selfGroup == null && currentModel.getParent() != null) {
                selfGroup = currentModel.getParent().getGroupId();
            }
            if (selfGroup != null) {
                props.putIfAbsent("project.groupId", selfGroup);
            }
            if (currentModel.getArtifactId() != null) {
                props.putIfAbsent("project.artifactId", currentModel.getArtifactId());
            }

            if (currentModel.getParent() == null) break;
            String relPath = currentModel.getParent().getRelativePath();
            if (relPath == null || relPath.isBlank()) relPath = "../pom.xml";
            Path parentPath = current.getParent() != null
                    ? current.getParent().resolve(relPath).normalize()
                    : Path.of(relPath).normalize();
            if (Files.isDirectory(parentPath)) parentPath = parentPath.resolve("pom.xml");
            if (!Files.exists(parentPath)) break;
            try (InputStream in = Files.newInputStream(parentPath)) {
                currentModel = new MavenXpp3Reader().read(in);
                current = parentPath;
            } catch (Exception ex) {
                log.debug("could not read parent {}: {}", parentPath, ex.getMessage());
                break;
            }
            depth++;
        }
        return props;
    }

    /**
     * Walk the parent chain starting at {@code pomFile} and accumulate managed versions from each
     * pom's &lt;dependencyManagement&gt; (with BOM imports recursively expanded).
     * Child-level entries take precedence over parent-level (putIfAbsent semantics).
     */
    private Map<String, String> collectAllManagedVersions(Path pomFile, Model model, Map<String, String> props) {
        Map<String, String> result = new LinkedHashMap<>();
        Path current = pomFile;
        Model currentModel = model;
        int depth = 0;
        while (currentModel != null && depth < MAX_PARENT_WALK) {
            Map<String, String> levelManaged = collectManagedVersions(currentModel, props, 0);
            for (Map.Entry<String, String> e : levelManaged.entrySet()) {
                result.putIfAbsent(e.getKey(), e.getValue());
            }
            if (currentModel.getParent() == null) break;
            String relPath = currentModel.getParent().getRelativePath();
            if (relPath == null || relPath.isBlank()) relPath = "../pom.xml";
            Path parentPath = current.getParent() != null
                    ? current.getParent().resolve(relPath).normalize()
                    : Path.of(relPath).normalize();
            if (Files.isDirectory(parentPath)) parentPath = parentPath.resolve("pom.xml");
            if (!Files.exists(parentPath)) break;
            try (InputStream in = Files.newInputStream(parentPath)) {
                currentModel = new MavenXpp3Reader().read(in);
                current = parentPath;
            } catch (Exception ex) {
                log.debug("could not read parent {}: {}", parentPath, ex.getMessage());
                break;
            }
            depth++;
        }
        return result;
    }

    /**
     * Collect managed versions (coord → version) from this pom's &lt;dependencyManagement&gt;,
     * recursively resolving BOM imports against the local Maven repo.
     */
    private Map<String, String> collectManagedVersions(Model model, Map<String, String> props, int depth) {
        Map<String, String> result = new LinkedHashMap<>();
        if (depth >= MAX_BOM_DEPTH) return result;
        if (model.getDependencyManagement() == null) return result;
        var managed = model.getDependencyManagement().getDependencies();
        if (managed == null) return result;

        for (Dependency d : managed) {
            String groupId = resolveValue(d.getGroupId(), props);
            String artifactId = resolveValue(d.getArtifactId(), props);
            String version = resolveValue(d.getVersion(), props);
            String type = resolveValue(d.getType(), props);
            String scope = resolveValue(d.getScope(), props);

            if ("pom".equals(type) && "import".equals(scope)) {
                Map<String, String> bomVersions = resolveBomVersions(groupId, artifactId, version, depth + 1);
                for (Map.Entry<String, String> e : bomVersions.entrySet()) {
                    result.putIfAbsent(e.getKey(), e.getValue());
                }
            } else if (groupId != null && artifactId != null && version != null && !version.isBlank()) {
                result.putIfAbsent(groupId + ":" + artifactId, version);
            }
        }
        return result;
    }

    private Map<String, String> resolveBomVersions(String groupId, String artifactId, String version, int depth) {
        if (groupId == null || artifactId == null || version == null || version.isBlank()) return Map.of();
        String cacheKey = groupId + ":" + artifactId + ":" + version;
        Map<String, String> cached = bomCache.get(cacheKey);
        if (cached != null) return cached;
        bomCache.put(cacheKey, Map.of()); // breaks potential cycles

        Path bomPath = localMavenRepo()
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
        if (!Files.exists(bomPath)) {
            log.debug("BOM not found in local repo: {}", bomPath);
            return Map.of();
        }
        Model bomModel;
        try (InputStream in = Files.newInputStream(bomPath)) {
            bomModel = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            log.debug("failed to read BOM {}: {}", bomPath, e.getMessage());
            return Map.of();
        }
        Map<String, String> bomProps = collectProperties(bomPath, bomModel);
        Map<String, String> bomVersions = collectManagedVersions(bomModel, bomProps, depth);
        bomCache.put(cacheKey, bomVersions);
        return bomVersions;
    }

    private static Path localMavenRepo() {
        String env = System.getenv("MAVEN_REPO_LOCAL");
        if (env != null && !env.isBlank()) return Paths.get(env);
        return Paths.get(System.getProperty("user.home"), ".m2", "repository");
    }

    private static String resolveValue(String value, Map<String, String> props) {
        if (value == null || !value.contains("${")) return value;
        String result = value;
        for (int i = 0; i < MAX_PROPERTY_INTERPOLATION; i++) {
            int start = result.indexOf("${");
            if (start < 0) break;
            int end = result.indexOf('}', start);
            if (end < 0) break;
            String key = result.substring(start + 2, end);
            String replacement = props.get(key);
            if (replacement == null) break;
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private void emitDependency(Dependency d, ProjectContext ctx, NodeKey projectKey, NodeKey fileKey,
                                Consumer<GraphEvent> sink, String defaultScope,
                                Map<String, String> props, Map<String, String> managedVersions) {
        String groupId = resolveValue(d.getGroupId(), props);
        String artifactId = resolveValue(d.getArtifactId(), props);
        String version = resolveValue(d.getVersion(), props);
        String scope = resolveValue(d.getScope(), props);

        boolean versionFromManaged = false;
        if ((version == null || version.isBlank()) && groupId != null && artifactId != null) {
            String managed = managedVersions.get(groupId + ":" + artifactId);
            if (managed != null) {
                version = managed;
                versionFromManaged = true;
            }
        }

        String coord = String.valueOf(groupId) + ":" + artifactId;
        NodeKey depKey = new NodeKey(ctx.projectId(), "MavenDependency", coord);
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", artifactId);
        properties.put("fqName", coord);
        properties.put("groupId", groupId);
        properties.put("artifactId", artifactId);
        properties.put("version", version == null ? "" : version);
        properties.put("scope", scope == null ? defaultScope : scope);
        properties.put("fileId", fileKey.id());
        if (versionFromManaged) properties.put("versionSource", "managed");
        sink.accept(new GraphEvent.NodeUpsert(depKey, properties));
        sink.accept(new GraphEvent.EdgeUpsert(projectKey, "DEPENDS_ON", depKey, Map.of("source", defaultScope)));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "DECLARES", depKey, Map.of()));
    }
}
