package io.doindev.cvector.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only filesystem browse endpoint that powers the directory picker in the dashboard
 * UI. The dashboard runs on localhost and the user has already chosen to trust it, so
 * we expose the full filesystem rather than confining the user to a sandbox — they need
 * to be able to reach arbitrary project directories, including across drives on Windows.
 *
 * <p>Returns directories only (regular files don't make sense as monitor / scan targets)
 * and never recurses; the UI walks one level at a time. Paths come back normalized to
 * absolute form so the UI can write the result straight into the monitor / schedule form.
 */
@RestController
@RequestMapping("/api/dashboard/fs")
public class FsController {

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "path", required = false) String pathStr) {
        Path target = resolveStart(pathStr);
        if (!Files.exists(target)) {
            return ResponseEntity.badRequest().body(Map.of("error", "path does not exist", "path", target.toString()));
        }
        if (!Files.isDirectory(target)) {
            return ResponseEntity.badRequest().body(Map.of("error", "not a directory", "path", target.toString()));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                // Skip non-directories — picker is for choosing directories. Permission errors
                // on individual children would otherwise abort the whole listing.
                try {
                    if (!Files.isDirectory(child)) continue;
                } catch (SecurityException se) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                String name = child.getFileName() == null ? child.toString() : child.getFileName().toString();
                entry.put("name", name);
                entry.put("path", child.toAbsolutePath().normalize().toString());
                entry.put("hidden", isHidden(child));
                items.add(entry);
            }
        } catch (IOException | SecurityException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "cannot read directory",
                "path", target.toString(),
                "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
        }

        items.sort(Comparator.comparing((Map<String, Object> m) -> ((String) m.get("name")).toLowerCase()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", target.toAbsolutePath().normalize().toString());
        Path parent = target.getParent();
        out.put("parent", parent == null ? null : parent.toAbsolutePath().normalize().toString());
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    /**
     * Returns mount roots (Windows: each drive; POSIX: just "/"). The UI uses this to render
     * a "Drives" pseudo-level above the filesystem so the user can switch between e.g. C: and D:.
     */
    @GetMapping("/roots")
    public Map<String, Object> roots() {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", root.toString());
            entry.put("path", root.toAbsolutePath().normalize().toString());
            roots.add(entry);
        }
        return Map.of("home", System.getProperty("user.home"), "roots", roots);
    }

    private static Path resolveStart(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return Paths.get(System.getProperty("user.home"));
        }
        return Paths.get(pathStr);
    }

    private static boolean isHidden(Path p) {
        try {
            return Files.isHidden(p);
        } catch (IOException | SecurityException e) {
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            return name.startsWith(".");
        }
    }
}
