package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.scan.InProcessScanService;
import io.doindev.cvector.cli.scan.ScanRequest;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Component
@Command(name = "scan", description = "Scan a path and ingest into Neo4j.", mixinStandardHelpOptions = true)
public class ScanCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Directory to scan (default: current directory).")
    private Path path = Paths.get(".");

    // The local --project option was removed in 0.2.0 in favour of the root-level
    // `--project` flag declared on {@link io.doindev.cvector.cli.CvectorCommand} with
    // {@code scope = INHERIT}. That sets {@code cvector.project} as a system property,
    // which {@link CvectorRuntime#requireActiveProject} reads before falling back to
    // {@code activeProject} — same behaviour, with the bonus of working for every
    // command consistently. Existing {@code cvector scan --project foo} invocations
    // need to switch to {@code cvector --project foo scan}.

    @Option(names = "--no-clean", description = "Skip removing nodes that became stale (default: clean).")
    private boolean noClean;

    private final CvectorRuntime runtime;
    private final InProcessScanService scanService;
    private final List<Parser> parsers;

    public ScanCommand(CvectorRuntime runtime, InProcessScanService scanService, List<Parser> parsers) {
        this.runtime = runtime;
        this.scanService = scanService;
        this.parsers = parsers;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        ProjectContext ctx = runtime.projectContext(cfg);
        Path scanRoot = path.toAbsolutePath().normalize();

        System.out.println("scanning " + scanRoot + " into project '" + ctx.projectName() + "' (" + ctx.projectId() + ")");
        System.out.println("parsers: " + parsers.stream().map(Parser::name).toList());

        ScanRequest request = ScanRequest.builder()
                .config(cfg)
                .context(ctx)
                .scanRoot(scanRoot)
                .noClean(noClean)
                .progress(System.out::println)
                .build();
        scanService.scan(request);
        return 0;
    }
}
