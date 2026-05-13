package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "doctor", description = "Run full diagnostics on the cvector setup.", mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public DoctorCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        boolean ok = true;
        CvectorConfigService svc = runtime.configService();
        Path cwd = runtime.workingDir();
        Path configRoot = svc.findConfigRoot(cwd);
        if (configRoot == null) {
            fail("config missing: no .cvector/settings.json (or legacy project.json) found from " + cwd);
            return 1;
        }
        pass("config found: " + svc.configPath(configRoot));

        CvectorConfig cfg;
        try {
            cfg = svc.load(configRoot);
        } catch (Exception e) {
            fail("config load failed: " + e.getMessage());
            return 1;
        }
        pass("config parses ok");

        if (cfg.activeProject() == null) {
            fail("no activeProject set in config");
            ok = false;
        } else if (!cfg.projects().containsKey(cfg.activeProject())) {
            fail("activeProject '" + cfg.activeProject() + "' not in config.projects");
            ok = false;
        } else {
            pass("active project: " + cfg.activeProject());
        }

        try (GraphStore store = runtime.openGraphStore(cfg)) {
            if (!store.ping()) {
                fail("graph backend unreachable at " + store.displayUri());
                ok = false;
            } else {
                pass("graph backend reachable at " + store.displayUri());
                if (!store.schemaReady()) {
                    System.out.println("[..] bootstrapping schema");
                    store.bootstrapSchema();
                }
                if (store.schemaReady()) {
                    pass("schema present");
                } else {
                    fail("schema bootstrap failed");
                    ok = false;
                }
            }
        } catch (Exception e) {
            fail("graph backend error: " + e.getMessage());
            ok = false;
        }

        return ok ? 0 : 1;
    }

    private void pass(String msg) { System.out.println("[ok] " + msg); }
    private void fail(String msg) { System.err.println("[!!] " + msg); }
}
