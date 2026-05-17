package io.doindev.cvector.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Responds to Chrome DevTools' "Project Settings" / Workspaces 2.0 probe at
 * {@code /.well-known/appspecific/com.chrome.devtools.json}. Without a handler, Chrome
 * issues that GET on every page load and our access log spams 404s.
 *
 * <p>When the dashboard has an active project we return the canonical pairing payload —
 * Chrome DevTools then offers to map served URLs back to the project's {@code rootPath}
 * on disk, which is handy for anyone editing the Angular SPA sources live or stepping
 * through the served JS in DevTools' Sources panel.
 *
 * <p>When there's no active project (empty workspace), we return {@code 204 No Content}
 * so Chrome stops asking but the access log stays quiet.
 */
@RestController
@ConditionalOnWebApplication
public class ChromeDevtoolsController {

    /**
     * Stable UUID per workspace — Chrome remembers the mapping it negotiated against
     * this id. Generated once per JVM. If you wanted persistence across restarts the
     * id would live in settings.json; for the typical "dev session" use case a
     * per-process id is fine because DevTools re-prompts when the id changes.
     */
    private static final String WORKSPACE_UUID = UUID.randomUUID().toString();

    private final ActiveProject project;

    public ChromeDevtoolsController(ActiveProject activeProject) {
        this.project = activeProject;
    }

    @GetMapping(value = "/.well-known/appspecific/com.chrome.devtools.json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> devtoolsWorkspace() {
        if (project.rootPath() == null) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("root", project.rootPath());
        workspace.put("uuid", WORKSPACE_UUID);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", workspace);
        return ResponseEntity.ok(body);
    }
}
