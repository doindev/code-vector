package io.doindev.cvector.cli.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GitHelper {

    private GitHelper() {}

    public static boolean isGitRepo(Path root) {
        return Files.isDirectory(root.resolve(".git")) || run(root, "rev-parse", "--is-inside-work-tree").isPresent();
    }

    public static Optional<String> head(Path root) {
        return run(root, "rev-parse", "HEAD");
    }

    public static Optional<String> resolveSha(Path root, String revish) {
        return run(root, "rev-parse", "--verify", revish + "^{commit}");
    }

    public static boolean worktreeAdd(Path root, Path target, String sha) {
        return run(root, "worktree", "add", "--detach", target.toString(), sha).isPresent()
                || java.nio.file.Files.isDirectory(target);
    }

    public static void worktreeRemove(Path root, Path target) {
        run(root, "worktree", "remove", "--force", target.toString());
        run(root, "worktree", "prune");
    }

    public static List<Path> changedSince(Path root, String sinceSha) {
        Set<String> paths = new LinkedHashSet<>();
        run(root, "diff", "--name-only", sinceSha, "HEAD").ifPresent(s -> addLines(s, paths));
        run(root, "status", "--porcelain").ifPresent(s -> {
            for (String line : s.split("\n")) {
                String trimmed = line.length() > 3 ? line.substring(3).trim() : "";
                if (!trimmed.isBlank()) paths.add(trimmed);
            }
        });
        List<Path> out = new ArrayList<>(paths.size());
        for (String p : paths) out.add(root.resolve(p));
        return out;
    }

    public static List<Path> deletedSince(Path root, String sinceSha) {
        Set<String> paths = new LinkedHashSet<>();
        run(root, "diff", "--name-only", "--diff-filter=D", sinceSha, "HEAD").ifPresent(s -> addLines(s, paths));
        List<Path> out = new ArrayList<>(paths.size());
        for (String p : paths) out.add(root.resolve(p));
        return out;
    }

    private static void addLines(String s, Set<String> sink) {
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isBlank()) sink.add(t);
        }
    }

    private static Optional<String> run(Path root, String... gitArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(root.toString());
        for (String a : gitArgs) cmd.add(a);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) return Optional.empty();
            return Optional.of(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
