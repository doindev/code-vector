package io.doindev.cvector.core.config;

/**
 * Fired by code that has just persisted a change to {@code settings.json}'s
 * {@code activeProject} (or to the {@code projects} map in a way that affects which
 * project is currently active — e.g. removing the active project, after which the writer
 * picked a replacement). The payload is purely informational; listeners are expected to
 * read {@code settings.json} themselves to determine the new state and reconcile any
 * in-memory caches.
 *
 * <p>Lives in {@code cvector-core} (rather than {@code cvector-rest} where the
 * {@code SettingsChangedEvent} sibling sits) because both {@code cvector-mcp} and
 * {@code cvector-rest} need to reference it — MCP tools publish it after
 * {@code cv_remove_project} / {@code cv_set_default_project} / etc., and the dashboard's
 * {@code WorkspaceSwitcher} listens to swap the in-memory {@code ActiveProject} bean and
 * trigger cache flushes. Without this cross-module event the dashboard's
 * {@code /api/health} / {@code /api/projects} continue serving stale data tied to a
 * projectId that no longer exists.
 *
 * @param newActiveName       the name of the project that's now active (may be {@code null}
 *                            if the workspace was emptied and there's no replacement).
 * @param newActiveProjectId  the projectId of that project, or {@code null} when empty.
 * @param previousActiveName  the prior active project's name, for logging / breadcrumbs.
 * @param reason              brief tag describing why this fired — e.g.
 *                            {@code "cv_remove_project"}, {@code "cv_set_default_project"},
 *                            {@code "cli-project-switch"}. Useful for downstream logs.
 */
public record ActiveProjectChangedEvent(String newActiveName,
                                        String newActiveProjectId,
                                        String previousActiveName,
                                        String reason) {
}
