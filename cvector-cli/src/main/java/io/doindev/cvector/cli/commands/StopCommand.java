package io.doindev.cvector.cli.commands;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Component
@Command(name = "stop",
        description = "Signal any running cvector processes (dashboard, watch, serve) to exit cleanly.",
        mixinStandardHelpOptions = true)
public class StopCommand implements Callable<Integer> {

    @Option(names = "--timeout",
            description = "Seconds to wait for graceful exit before forcing (default 10).")
    private int timeoutSec = 10;

    @Option(names = "--force",
            description = "Skip graceful termination -- kill immediately.")
    private boolean force;

    @Override
    public Integer call() {
        long ourPid = ProcessHandle.current().pid();
        List<ProcessHandle> targets = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ourPid)
                .filter(StopCommand::looksLikeCvector)
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            System.out.println("no cvector processes running");
            return 0;
        }

        int exitCode = 0;
        for (ProcessHandle p : targets) {
            long pid = p.pid();
            String cmd = p.info().commandLine().orElseGet(() -> p.info().command().orElse("?"));
            System.out.println("stopping pid=" + pid + " -- " + truncate(cmd, 120));

            if (force) {
                if (!p.destroyForcibly()) {
                    System.err.println("  failed to kill pid=" + pid);
                    exitCode = 1;
                    continue;
                }
            } else {
                p.destroy();
                try {
                    p.onExit().get(timeoutSec, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    System.out.println("  graceful exit timed out -- forcing");
                    p.destroyForcibly();
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println("  wait failed: " + e.getMessage());
                    exitCode = 1;
                    continue;
                }
            }

            try {
                p.onExit().get(5, TimeUnit.SECONDS);
                System.out.println("  stopped");
            } catch (Exception ignore) {
                System.err.println("  still alive after kill -- check permissions");
                exitCode = 1;
            }
        }
        return exitCode;
    }

    /**
     * A process is "cvector" if its launcher executable matches the bundled .exe / .app,
     * or its command line references the fat jar or AOT main class. The intent is to be
     * narrow enough that we never accidentally signal someone else's process.
     */
    private static boolean looksLikeCvector(ProcessHandle p) {
        ProcessHandle.Info info = p.info();
        String exe = info.command().orElse("").toLowerCase();
        String cmd = info.commandLine().orElse("").toLowerCase();
        return exe.endsWith("cvector.exe")
                || exe.endsWith("/cvector")
                || exe.endsWith("\\cvector")
                || cmd.contains("cvector.jar")
                || cmd.contains("io.doindev.cvector.cvectorapplication");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "...";
    }
}
