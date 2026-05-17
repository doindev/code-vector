package io.doindev.cvector.cli.scan;

import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Inputs to {@link InProcessScanService#scan}. Built by callers (CLI {@code ScanCommand} and
 * the MCP {@code cv_scan_project} tool) so the scan loop never has to reason about its caller.
 *
 * <p>The interesting field is {@link #reusedStore()}: when the calling JVM already holds a
 * {@link GraphStore} bound to the same underlying database (typical for the MCP-on-dashboard
 * co-host where the SSE process opened embedded Kuzu at boot), the scan reuses its
 * {@link io.doindev.cvector.embedded.EmbeddedKuzu} / {@link io.doindev.cvector.neo4j.Neo4jClient}
 * handle instead of opening a duplicate one. On embedded Kuzu that is the only way the scan
 * can succeed at all — Kuzu file-locks the DB directory, and a second open from the same JVM
 * (or any other process) crashes immediately with "Could not set lock on file".
 *
 * <p>When {@code reusedStore} is {@code null} (the CLI path) the service opens fresh handles
 * via {@code try-with-resources} and closes them when the scan finishes.
 */
public record ScanRequest(
        CvectorConfig config,
        ProjectContext context,
        Path scanRoot,
        boolean noClean,
        GraphStore reusedStore,
        Consumer<String> progress) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CvectorConfig config;
        private ProjectContext context;
        private Path scanRoot;
        private boolean noClean;
        private GraphStore reusedStore;
        private Consumer<String> progress;

        public Builder config(CvectorConfig v) { this.config = v; return this; }
        public Builder context(ProjectContext v) { this.context = v; return this; }
        public Builder scanRoot(Path v) { this.scanRoot = v; return this; }
        public Builder noClean(boolean v) { this.noClean = v; return this; }
        public Builder reusedStore(GraphStore v) { this.reusedStore = v; return this; }
        public Builder progress(Consumer<String> v) { this.progress = v; return this; }

        public ScanRequest build() {
            if (config == null) throw new IllegalArgumentException("config is required");
            if (context == null) throw new IllegalArgumentException("context is required");
            if (scanRoot == null) throw new IllegalArgumentException("scanRoot is required");
            return new ScanRequest(config, context, scanRoot, noClean, reusedStore, progress);
        }
    }
}
