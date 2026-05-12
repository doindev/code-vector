package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Component
@Command(name = "ci", description = "Orchestrate scan + rules + guard + audit. Exits non-zero on any failure.")
public class CiCommand implements Callable<Integer> {

    @Option(names = "--skip-scan", description = "Skip scan if Project.lastScanCommit matches current git HEAD.")
    private boolean skipScan;

    @Option(names = "--skip-audit", description = "Skip the dependency audit step.")
    private boolean skipAudit;

    @Option(names = "--fail-on-warn", description = "Treat WARN-severity rule violations as failing.")
    private boolean failOnWarn;

    private final CvectorRuntime runtime;
    private final ScanCommand scan;
    private final GuardCommand guard;
    private final AuditCommand audit;

    public CiCommand(CvectorRuntime runtime, ScanCommand scan, GuardCommand guard, AuditCommand audit) {
        this.runtime = runtime;
        this.scan = scan;
        this.guard = guard;
        this.audit = audit;
    }

    @Override
    public Integer call() throws Exception {
        int finalCode = 0;

        if (shouldSkipScan()) {
            System.out.println("ci: skip-scan — graph is up to date with HEAD");
        } else {
            System.out.println("ci: scan");
            int rc = scan.call();
            if (rc != 0) return rc;
        }

        System.out.println();
        System.out.println("ci: guard");
        int rc = guard.callForCi(failOnWarn);
        if (rc != 0) finalCode = rc;

        if (!skipAudit) {
            System.out.println();
            System.out.println("ci: audit");
            int auditRc = audit.call();
            if (auditRc != 0 && finalCode == 0) finalCode = auditRc;
        }

        System.out.println();
        System.out.println("ci: " + (finalCode == 0 ? "PASS" : "FAIL (exit " + finalCode + ")"));
        return finalCode;
    }

    private boolean shouldSkipScan() {
        if (!skipScan) return false;
        try {
            CvectorConfig cfg = runtime.loadConfig();
            CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
            Optional<String> head = GitHelper.head(runtime.resolveConfigRoot());
            if (head.isEmpty()) return false;
            try (GraphStore store = runtime.openGraphStore(cfg)) {
                Map<String, Object> meta = store.projectMeta(active.projectId());
                Object sha = meta.get("lastScanCommit");
                return sha != null && head.get().equals(sha.toString());
            }
        } catch (RuntimeException e) {
            return false;
        }
    }
}
