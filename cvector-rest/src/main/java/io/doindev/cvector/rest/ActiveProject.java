package io.doindev.cvector.rest;

/**
 * Mutable holder for the workspace's currently-active project. Was a record before — kept the
 * same accessor surface ({@code projectId()}, {@code name()}, {@code rootPath()}) so the
 * ~20 controllers that inject it didn't have to change. The mutability is the foundation of
 * the runtime project-switch path: {@link WorkspaceSwitcher} updates these fields atomically,
 * concurrent readers see one consistent snapshot per call via {@code volatile}.
 *
 * <p>Why not a record + new bean instance per switch: every Spring bean that captured the
 * old ActiveProject by constructor injection would still hold the stale reference. Mutating
 * a singleton's volatile fields is the lowest-risk way to surface the change everywhere
 * without re-wiring the context.
 */
public final class ActiveProject {

    private volatile String projectId;
    private volatile String name;
    private volatile String rootPath;

    public ActiveProject(String projectId, String name, String rootPath) {
        this.projectId = projectId;
        this.name = name;
        this.rootPath = rootPath;
    }

    public String projectId() { return projectId; }
    public String name() { return name; }
    public String rootPath() { return rootPath; }

    /**
     * Atomically swap the three fields. Not synchronised — the fields are volatile so each
     * is independently safe for concurrent reads; the gap between the three writes is small
     * enough that a request landing mid-swap would see at worst a stale name with a new
     * projectId. The controllers tolerate that — they always re-read each call.
     */
    void replaceFields(String newProjectId, String newName, String newRootPath) {
        this.projectId = newProjectId;
        this.name = newName;
        this.rootPath = newRootPath;
    }
}
