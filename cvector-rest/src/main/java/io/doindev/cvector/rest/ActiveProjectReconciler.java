package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.ActiveProjectChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link ActiveProjectChangedEvent} fired from outside the REST module (most
 * notably by {@code cv_remove_project} and {@code cv_set_default_project} in the MCP tool
 * surface). The event signals "{@code settings.json}'s {@code activeProject} just
 * changed" — but the dashboard's in-memory {@link ActiveProject} bean has no idea, so
 * every {@code /api/health} / {@code /api/projects} / per-project endpoint keeps serving
 * stale data tied to a projectId that may no longer exist.
 *
 * <p>The fix is to plumb the change through {@link WorkspaceSwitcher#switchTo}, which is
 * exactly what the REST-side {@code /api/projects/switch} endpoint already uses — same
 * bean, same flow (update {@code ActiveProject}, swap the {@link io.doindev.cvector.core.store.GraphStore}
 * handle if backend / shared-DB layout demands it, fire {@link GraphMutatedEvent} so both
 * cache layers flush). The listener centralises the cross-module hand-off so MCP tools
 * don't need to know anything about the dashboard's caches.
 *
 * <p>Web-only: the listener is no-op when cvector is running stdio-only
 * ({@code cvector serve}) — the dashboard's caches don't exist there, so there's nothing
 * to reconcile.
 */
@Component
@ConditionalOnWebApplication
public class ActiveProjectReconciler {

    private static final Logger log = LoggerFactory.getLogger(ActiveProjectReconciler.class);

    private final WorkspaceSwitcher switcher;
    private final ActiveProject activeProject;

    public ActiveProjectReconciler(WorkspaceSwitcher switcher, ActiveProject activeProject) {
        this.switcher = switcher;
        this.activeProject = activeProject;
    }

    @EventListener
    public void onActiveProjectChanged(ActiveProjectChangedEvent event) {
        String newName = event.newActiveName();
        if (newName == null || newName.isBlank()) {
            // Workspace emptied — the deleting tool already removed the registration and
            // there's no replacement. Reset the in-memory bean to its empty-workspace
            // placeholder so downstream endpoints surface the right "no project" envelope
            // instead of clutching at a projectId that no longer exists.
            log.info("active project removed by {} (workspace now empty); clearing in-memory bean",
                    event.reason());
            activeProject.replaceFields(null, null, null);
            return;
        }
        // Skip the noop case where the bean already matches what settings.json now says.
        // WorkspaceSwitcher does its own noop guard, but this avoids the redundant
        // settings.json re-save when the event was purely informational.
        if (newName.equals(activeProject.name())
                && (event.newActiveProjectId() == null
                        || event.newActiveProjectId().equals(activeProject.projectId()))) {
            return;
        }
        log.info("reconciling in-memory active project after {}: {} → {}",
                event.reason(), event.previousActiveName(), newName);
        try {
            switcher.switchTo(newName);
        } catch (RuntimeException e) {
            // Best-effort: the persisted settings.json is already correct, so a failed
            // in-memory swap is a recoverable inconvenience (next process restart sets it
            // right). Log loudly so the operator can act on it.
            log.warn("ActiveProjectReconciler: failed to switch to '{}': {}", newName, e.getMessage());
        }
    }
}
