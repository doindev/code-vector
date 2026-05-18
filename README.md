# cvector

Code knowledge graph with a polyglot scanner, a Picocli CLI, a Spring Boot REST API + dashboard, and an MCP server for AI assistants. Point it at a codebase and it ingests files into a graph of `Class → Method → CALLS → Method`, REST endpoints, SQL tables, config keys, container images, IaC resources, GraphQL types, and more — then exposes that graph to humans (CLI + dashboard) and to AI assistants (MCP tools, resources, prompts).

**Backends.** cvector ships with an **embedded [KuzuDB](https://kuzudb.com) store** as the default — in-process, MIT-licensed, no separate database to run. The graph data lives under `~/.cvector/kuzu-data/<projectId>/`. Optionally you can point cvector at **Neo4j** instead — either an existing remote instance over Bolt, or one cvector spins up for you via Docker Compose. See [Backends & settings.json](#backends--settingsjson) for the full configuration matrix.

---

## Table of contents

1. [Requirements](#requirements)
2. [Quick start](#quick-start)
3. [Backends & settings.json](#backends--settingsjson)
4. [CLI commands](#cli-commands)
5. [REST API](#rest-api)
6. [MCP server](#mcp-server)
   - [MCP transports](#mcp-transports)
   - [Tools](#mcp-tools)
   - [Long-running tools: async + cv_job_status](#long-running-tools-async--cv_job_status)
   - [Resources](#mcp-resources)
   - [Prompts](#mcp-prompts)
   - [Client setup](#mcp-client-setup)
7. [Embedded KuzuDB (default backend)](#embedded-kuzudb-default-backend)
8. [Supported languages](#supported-languages)
9. [Rules & quality gates](#rules--quality-gates)
10. [Roles & access control](#roles--access-control)
11. [Module layout](#module-layout)
12. [Performance notes](#performance-notes)

---

## Requirements

| Component | Minimum | Notes |
|---|---|---|
| **Java** | 17 (compile target) | Runs on JDK 21+ to enable virtual threads on the dashboard. (Virtual threads are intentionally **disabled** on the `mcp` profile — see [Spring profiles](#spring-profiles).) |
| **Maven** | 3.9+ | The build uses ANTLR4 maven plugin for grammar generation. |
| **OS** | Windows / Linux / macOS | Build is OS-agnostic. |
| **Neo4j** | *(optional)* 5.x | Only required if you opt out of the embedded KuzuDB default — see [Backends](#backends--settingsjson). The Kuzu native library is bundled in the fat-jar for all three platforms. |
| **Docker** | *(optional)* | Only needed for the `docker` backend mode where cvector manages a Neo4j container for you. |

### Stack (current, all on the `kuzu` branch)

| Component | Version | Status |
|---|---|---|
| Spring Boot | **4.1.0-RC1** | Pre-release. Spring Framework 7. |
| Spring AI | **2.0.0-M6** | Pre-release milestone — adds the MCP Streamable HTTP transport. |
| MCP SDK | **2.0.0-M2** | Pinned via `io.modelcontextprotocol.sdk:mcp-bom` in the root pom. |
| KuzuDB | 0.11.3 | Native lib bundled in the fat-jar; auto-extracted on first load. |
| Angular | 17 | Optional dashboard SPA (build with `-Pdashboard-ui`). |

### Optional: starting a local Neo4j manually

Only needed if you want `backend: "remote"` against your own Neo4j instance. Skip this section entirely if you're using the embedded default (most users).

```bash
docker run --name neo4j_admin \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/your-password \
  -d neo4j:5
```

For the `docker` backend mode (where cvector spins the container up for you), see [Backend: Docker-managed Neo4j](#backend-docker-managed-neo4j) below — no manual `docker run` needed.

---

## Quick start

### Option A — Embedded (default; no extra infrastructure)

This is the recommended starting point. The graph lives in an in-process KuzuDB at `~/.cvector/kuzu-data/<projectId>/` — nothing else to install, nothing to spin up.

```bash
# 1. Build the fat jar (and the optional dashboard UI)
mvn -DskipTests install                                # CLI + REST only
mvn -pl cvector-app -am -Pdashboard-ui -DskipTests package   # + Angular dashboard

# 2. Initialise cvector in the codebase you want to scan
cd /path/to/your/project
java -jar /path/to/cvector/cvector-app/target/cvector.jar init --project my-project

# `init` writes .cvector/settings.json with backend = "embedded". No edits needed.

# 3. Scan
java -jar /path/to/cvector.jar scan

# 4. Try it out
java -jar /path/to/cvector.jar status
java -jar /path/to/cvector.jar onboard
java -jar /path/to/cvector.jar rules

# 5. Open the dashboard (auto-launches a browser tab)
java -jar /path/to/cvector.jar dashboard -o
```

The fat jar at `cvector-app/target/cvector.jar` is the single executable for **CLI**, **dashboard**, and **MCP server** modes — the first argument selects the mode.

### Option B — Neo4j via Docker compose (instead of embedded)

Use this if you want a Neo4j instance you can query in the Neo4j Browser, GDS / Bloom, or share across multiple cvector projects.

```bash
git clone <this repo> cvector && cd cvector

# Start Neo4j + build cvector image (first time only)
docker compose up -d neo4j

# One-shot scan of the current directory
docker compose run --rm cvector init --project my-project
# Edit .cvector/settings.json: set "backend": "remote" and fill in neo4j.password.
docker compose run --rm cvector scan

# Long-lived REST dashboard on http://localhost:2969
docker compose up -d dashboard

# Or run the MCP server (stdio) — point your MCP client at this command
docker compose run --rm -T cvector serve
```

The Neo4j password defaults to `cvector_admin_pw`; override with `NEO4J_AUTH=neo4j/<your-pw>` in the environment. The `cvector` service mounts the host repo as `/workspace`, so scans see your real files. See [Backend: Remote Neo4j](#backend-remote-neo4j-existing-instance) for the matching `settings.json` shape.

### Option C — Standalone Windows distributable (no JDK required)

```bash
# Build a self-contained directory with cvector.exe + bundled JRE
mvn -Pdist -DskipTests install

# Same, but bundle the Angular dashboard into the .exe as well
mvn -pl cvector-app -am -Pdashboard-ui,dist -DskipTests install
```

Output lands at `cvector-app/target/dist/cvector/`:

```
cvector/
├── cvector.exe       # entry point — runs without JAVA_HOME
├── app/cvector.jar   # the fat jar
└── runtime/          # trimmed JRE (java.base, java.management, java.naming, …)
```

Zip the `cvector/` directory and hand it to anyone on Windows — they unzip, run `cvector.exe`, no JDK install needed. Every subcommand supports `--help` / `-h` (`cvector scan --help`, `cvector embedded query --help`, …).

The plugin is `org.panteleyev:jpackage-maven-plugin`, bound to the `verify` phase under the `dist` profile so a normal `mvn package` skips it. Builds for the host OS only — to produce a Linux or macOS distributable, run the same `mvn -Pdist` on that host.

#### Windows: `Unable to delete cvector.exe` on rebuild

On Windows, a second `mvn -Pdist install` against an existing `target/dist/` can fail with `Unable to delete ...\dist\cvector\cvector.exe`. The cause is Windows Defender (or another AV) holding a real-time-scan handle on the freshly-built `cvector.exe` from the prior run — `<delete>` retries don't help because each retry re-touches the file and re-triggers the scan.

Three ways out (top to bottom: most permanent → most ad-hoc):

1. **Add `cvector-app/target/` to Defender's folder exclusions** *(recommended for active dev)*. Settings → Windows Security → Virus & threat protection → Manage settings → Exclusions → Add an exclusion → Folder → pick `<repo>\cvector-app\target`. One-time setup; subsequent rebuilds run unimpeded.
2. **Full clean every time.** `mvn clean -Pdist,dashboard-ui -DskipTests install`. Adds ~30 s to recompile the reactor but works without any Defender tweaks.
3. **Manual nuke between builds.** `rm -rf cvector-app/target/dist && mvn -Pdist,dashboard-ui -DskipTests install`. Skips the recompile cost; just deletes the conflicting dist tree (Defender releases the handle once the rebuild isn't active).

---

## Backends & settings.json

cvector keeps all per-workspace state in a single file: `.cvector/settings.json` (created by `cvector init`, discovered by walking up from the current working directory). The file has seven top-level fields, only two of which are required after `init` (`activeProject`, `projects`); every other section falls back to a sensible default if absent.

> **Global fallback + auto-bootstrap.** If the walk-up finds no project-local `.cvector/`, cvector also checks **`~/.cvector/settings.json`** (your user home). That lets `cvector status`, `cvector dashboard`, etc. work from anywhere — even `C:\` or `/tmp`. And if no config exists in the home directory either, cvector **auto-creates one** on first run: `~/.cvector/` is created if absent, and a default `settings.json` is written with `backend: "embedded"` and a `default` project rooted at `$HOME`. A one-line notice is printed on stderr so you know a file was placed in your home directory on your behalf. Run any subsequent `cvector project create --root /path/to/codebase` (or edit the file) to point at real projects. Project-local config still wins when it exists; the home config and the auto-bootstrap are consulted only when nothing's found by the walk-up.

> **Legacy filename.** Older installs used `.cvector/project.json`. The loader still reads that name as a fallback so existing workspaces keep working, but every write goes to `settings.json`. If you have both, `settings.json` wins.

> **Not tracked in git.** `.cvector/settings.json` carries a machine-specific absolute `rootPath` and (for `remote`/`docker` backends) Neo4j credentials, so the repo's `.gitignore` excludes it. Each developer runs `cvector init` locally to generate their own. `.cvector/rules.yml` and `.cvector/docker-compose.yml` *are* tracked because they're team-shared policy / infrastructure config.

### Picking a backend

The `backend` field decides where the graph is stored. Three modes:

| Mode | When to use | Setup cost |
|---|---|---|
| **`embedded`** *(default)* | Default — single-user, fast cold start, no other process to run. | None. The Kuzu native library is bundled in the fat-jar; database lives at `~/.cvector/kuzu-data/<projectId>/`. |
| **`remote`** | You already have a Neo4j instance, or you want to use Neo4j Browser / GDS / Bloom on the same graph. | Provide a running Neo4j 5.x. Set `neo4j.uri / user / password`. |
| **`docker`** | You want Neo4j but don't want to manage it yourself. cvector writes a `docker-compose.yml` and runs `docker compose up -d` for you. | Docker installed. cvector handles the rest. |

You can switch any time without changing your data model — the parsers emit the same `GraphEvent` shapes regardless of backend. Switching from one backend to another does **not** migrate the existing graph data; you re-run `cvector scan` to populate the new backend.

### Full schema

```json
{
  "activeProject": "my-project",
  "projects": {
    "my-project": {
      "projectId": "0d357370-80be-423e-9f79-c7ffc496eed3",
      "name": "my-project",
      "rootPath": "C:\\path\\to\\project"
    }
  },
  "backend": "embedded",
  "neo4j": {
    "uri": "bolt://localhost:7687",
    "user": "neo4j",
    "password": "neo4j"
  },
  "rest": {
    "port": 2969,
    "host": "127.0.0.1",
    "server": {
      "ssl": { "enabled": false },
      "compression": { "min-response-size": 1024 },
      "tomcat": { "max-threads": 200 }
    }
  },
  "mcp": {
    "url": "http://127.0.0.1:2969/mcp",
    "transport": "streamable"
  },
  "docker": {
    "image": "neo4j",
    "containerName": "cvector-neo4j",
    "neo4jVersion": "5",
    "boltPort": 7687,
    "httpPort": 7474
  },
  "kuzu": {
    "bufferSizeMb": 2048
  }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `activeProject` | string | *(set by `init`)* | Name of the project that commands operate on by default. Use `cvector project switch <name>` to change. |
| `projects` | object | `{}` | Map of project name → `ProjectEntry`. Each entry has `projectId` (UUID, generated once and stable for the life of the project), `name` (human label, equal to the map key), and `rootPath` (absolute path to the codebase root). |
| `backend` | string | `"embedded"` | One of `"embedded"` / `"remote"` / `"docker"`. Unknown values fall back to `"embedded"`. |
| `neo4j.uri` | string | `"bolt://localhost:7687"` | Neo4j Bolt URL. Used only when `backend ∈ {remote, docker}`. |
| `neo4j.user` | string | `"neo4j"` | Bolt username. |
| `neo4j.password` | string | `"neo4j"` | Bolt password. **REST `GET /api/settings` masks this as `"***"`; sending `"***"` back via `PUT /api/settings` is treated as "keep existing".** |
| `rest.port` | integer | `2969` | Port the dashboard / REST API binds to. |
| `rest.host` | string | `"127.0.0.1"` | Bind address. `127.0.0.1` keeps the dashboard loopback-only; use `0.0.0.0` to expose on the network. Easier: `cvector host 0.0.0.0`. |
| `rest.server` | object | `null` | Free-form Spring Boot `server.*` overrides — any property the Boot binder accepts (e.g. `server.ssl.*`, `server.compression.*`, `server.servlet.session.*`, `server.tomcat.*`). Nested objects are flattened to dotted keys; lists are comma-joined. On boot, each entry is promoted to a JVM system property — Spring Boot precedence slot 6 — so settings.json values **override matching OS environment variables** (slot 7). Explicit `-Dserver.foo=…` on the cvector command line still wins (already-set system properties aren't overwritten). |
| `mcp.url` | string | `"http://127.0.0.1:2969/mcp"` | Informational URL clients can use to reach the MCP server. The server itself binds at `rest.host:rest.port`. The path component drives the live endpoint — set it to `http://.../sse` if you switch `transport` to `sse`. |
| `mcp.transport` | string | `"streamable"` | One of `"streamable"` *(default, MCP 2025-03-26)* / `"sse"` *(legacy, MCP 2024-11-05)* / `"stdio"`. Legacy `"http"` is accepted and canonicalised to `"streamable"` on read/write. See [MCP transports](#mcp-transports) for the protocol-level differences. |
| `docker.image` | string | `"neo4j"` | Docker image name (without tag). |
| `docker.containerName` | string | `"cvector-neo4j"` | Compose service / container name. |
| `docker.neo4jVersion` | string | `"5"` | Image tag — used as `{image}:{neo4jVersion}`. |
| `docker.boltPort` | integer | `7687` | Host port the container's Bolt listener is mapped to. |
| `docker.httpPort` | integer | `7474` | Host port for Neo4j HTTP / Browser. |
| `kuzu.bufferSizeMb` | integer | *(auto-sized)* | Kuzu's buffer pool size in MB. Optional — when absent, cvector auto-sizes to 25% of system RAM (clamped to 512 MB – 4 GB). Set this when a large project's bulk-load COPY hits `Buffer manager exception: Unable to allocate memory!` or you want a deterministic ceiling per machine. Kuzu's pool is fixed at database-open time; changing this value requires restarting cvector. |
| `rules` | object | `null` | **Workspace-wide** architecture-rules policy — see [Rules policy](#rules-policy). Applies to every project in `projects`. |
| `projects.<name>.rules` | object | `null` | **Per-project** rules override — same shape as the workspace `rules` field, layered on top of it so a single project can raise thresholds or disable rules without touching the workspace policy. |

Every section is optional — omit it and the defaults above apply. Adding a field never breaks older binaries (Jackson ignores unknown fields at load time).

### Backend: Embedded Kuzu *(default)*

Minimal `settings.json` for the default backend:

```json
{
  "activeProject": "my-project",
  "projects": {
    "my-project": {
      "projectId": "0d357370-80be-423e-9f79-c7ffc496eed3",
      "name": "my-project",
      "rootPath": "/absolute/path/to/project"
    }
  },
  "backend": "embedded"
}
```

That's the entire file. No Neo4j credentials needed; no network ports to open. `cvector init` writes this for you. The `rest`, `mcp`, `neo4j`, and `docker` sections can be omitted entirely — defaults kick in.

CLI shortcut to flip the active backend:

```bash
cvector db --embedded
```

### Backend: Remote Neo4j (existing instance)

You already run Neo4j somewhere — locally, on a VM, in a managed service. Tell cvector how to reach it:

```json
{
  "activeProject": "my-project",
  "projects": {
    "my-project": {
      "projectId": "...",
      "name": "my-project",
      "rootPath": "/absolute/path/to/project"
    }
  },
  "backend": "remote",
  "neo4j": {
    "uri": "bolt://neo4j.internal:7687",
    "user": "neo4j",
    "password": "your-actual-password"
  }
}
```

CLI shortcut:

```bash
cvector db --remote                     # switch backend mode
# then edit settings.json to set neo4j.uri / user / password (or set them via REST PUT /api/settings)
```

Connection failures surface from the Bolt driver downstream — the dashboard's `/api/health` returns the connection error in the `status` field.

### Backend: Docker-managed Neo4j

cvector writes `.cvector/docker-compose.yml` from the `docker` section and runs `docker compose up -d` for you. You don't manage the container — flip the backend and run any scan command.

```json
{
  "activeProject": "my-project",
  "projects": {
    "my-project": {
      "projectId": "...",
      "name": "my-project",
      "rootPath": "/absolute/path/to/project"
    }
  },
  "backend": "docker",
  "neo4j": {
    "uri": "bolt://localhost:7687",
    "user": "neo4j",
    "password": "cvector_admin_pw"
  },
  "docker": {
    "image": "neo4j",
    "containerName": "cvector-neo4j",
    "neo4jVersion": "5",
    "boltPort": 7687,
    "httpPort": 7474
  }
}
```

`DockerNeo4jBackend.ensureRunning` waits up to 60 s for the bolt port to open. If Docker is missing or compose fails, cvector continues — the next graph read will surface the connection error from the Bolt driver. The compose file is best-effort: it's regenerated from the config on every `ensureRunning` call only if missing, so manual edits to `docker-compose.yml` persist.

CLI shortcut:

```bash
cvector db --docker
```

### Switching backends and inspecting state

| Command | Effect |
|---|---|
| `cvector db --embedded` | Set `backend: "embedded"` and save. |
| `cvector db --remote` | Set `backend: "remote"` and save. |
| `cvector db --docker` | Set `backend: "docker"` and save. |
| `cvector host <addr>` | Update `rest.host` (e.g. `0.0.0.0` to expose the dashboard on the LAN). |
| `cvector mcp` | Show current MCP config. |
| `cvector mcp update --transport <streamable\|sse\|stdio> <URL>` | Update `mcp.transport` and `mcp.url`. (`http` is accepted as a legacy alias for `streamable`.) Restart the dashboard / serve process to pick up the new transport. |
| `cvector --backend <mode> <subcommand>` | One-shot backend override that does **not** persist to `settings.json`. |
| `--embedded` *(global flag)* | Legacy back-compat; equivalent to `--backend embedded` at the root level. Subcommands no longer inherit it (it would collide with `cvector db --embedded`). |
| Env `CVECTOR_EMBEDDED=true` | Same as the `--embedded` flag for shell scripts / Docker. |

### Network exposure

The dashboard / REST API binds to `rest.host:rest.port` — defaulting to **loopback only** (`127.0.0.1:2969`). Remote hosts on the network cannot reach the embedded server at the default address. To expose it:

```bash
cvector host 0.0.0.0                                    # persists in settings.json
# or one-shot:
cvector dashboard --server.address=0.0.0.0
```

Pair off-loopback exposure with a reverse proxy and auth. The `cvector serve` MCP transport uses stdio, so it never opens a network socket regardless of `rest.host`.

### Settings via REST

The dashboard exposes the same file as JSON:

- `GET /api/settings` — full config with `neo4j.password` masked as `"***"`.
- `PUT /api/settings` — partial JSON patch; merged into the existing config. Sending `"password": "***"` is a no-op (preserves the stored value), so you can round-trip a `GET` response through a `PUT` without losing the credential.

Every save publishes a `SettingsChangedEvent` so views that depend on the config refresh without a reload.

### Rules policy

cvector's architecture-rules engine pulls its configuration from **four layered sources**, merged in this order (later sources override earlier ones):

1. **Built-in defaults** — hard-coded thresholds (`godFileMethods: 30`, `godClassMethods: 20`, `longMethodLines: 80`, `deepInheritance: 5`) plus a couple of universal `excludePaths` (`/internal/`, `/target/`, `/generated-sources/`).
2. **`.cvector/rules.yml`** — legacy file-based config. Still loaded automatically when present so existing projects keep working. See [.cvector/rules.yml](#cvectorrulesyml) below.
3. **Workspace `rules` section in `settings.json`** — applied to every project in the workspace.
4. **Per-project `rules` inside `projects.<name>`** — overrides for one specific project, layered on top of the workspace policy.

Per-field merge semantics:

| Field | Merge |
|---|---|
| `thresholds` | Map-merge by key. A later layer's value replaces an earlier value for the same key; keys the later layer doesn't mention keep the earlier value. |
| `disable` | Union — once a rule is disabled at any layer it stays disabled. You can't re-enable from a lower layer. |
| `excludePaths` | Union with dedup. Ordering reflects layer order (defaults first, per-project last). |
| `custom` | By-name override — a later layer's custom rule with the same `name` replaces the earlier one wholesale (Cypher + severity + description). |

#### Example: workspace policy applied to every project

```json
{
  "activeProject": "my-project",
  "projects": {
    "my-project": { "projectId": "...", "name": "my-project", "rootPath": "..." }
  },
  "backend": "embedded",
  "rules": {
    "thresholds": {
      "godFileMethods": 50,
      "longMethodLines": 120
    },
    "disable": ["deep-inheritance"],
    "excludePaths": [
      "/legacy/",
      "/vendor/"
    ],
    "custom": [
      {
        "name": "no-system-out-in-services",
        "description": "Service classes should not print to System.out.",
        "severity": "WARN",
        "cypher": "MATCH (c:Class {projectId: $pid, isService: true})-[:CONTAINS]->(m:Method)-[:CALLS]->(callee:Method) WHERE callee.fqName CONTAINS 'PrintStream.println' RETURN c.fqName AS subject, callee.fqName AS message",
        "cypherKuzu": "MATCH (c:Node)-[:CONTAINS]->(m:Node)-[:CALLS]->(callee:Node) WHERE c.label = 'Class' AND m.label = 'Method' AND callee.label = 'Method' AND c.isService = true AND callee.fqName CONTAINS 'PrintStream.println' RETURN c.fqName AS subject, callee.fqName AS message"
      }
    ]
  }
}
```

#### Example: per-project override on top of the workspace

```json
{
  "activeProject": "frontend-app",
  "projects": {
    "backend-api": {
      "projectId": "...",
      "name": "backend-api",
      "rootPath": "..."
    },
    "frontend-app": {
      "projectId": "...",
      "name": "frontend-app",
      "rootPath": "...",
      "rules": {
        "thresholds": {
          "longMethodLines": 200
        },
        "disable": ["dead-code"]
      }
    }
  },
  "backend": "embedded",
  "rules": {
    "thresholds": {
      "longMethodLines": 120
    }
  }
}
```

Effective `longMethodLines` per project:
- `backend-api` → **120** (workspace value).
- `frontend-app` → **200** (per-project override on top of workspace).

`dead-code` is disabled **only** for `frontend-app`. Run `cvector project switch backend-api && cvector rules` and dead-code violations still appear.

#### Per-field reference

| Field | Type | Description |
|---|---|---|
| `thresholds.godFileMethods` | integer | Files with more methods than this trigger the `god-file` rule (severity ERROR). |
| `thresholds.godClassMethods` | integer | Classes with more methods than this trigger the `god-class` rule (severity ERROR). |
| `thresholds.longMethodLines` | integer | Methods whose body exceeds this many lines trigger `long-method` (severity WARN). |
| `thresholds.deepInheritance` | integer | Classes with an extends-chain deeper than this trigger `deep-inheritance` (severity WARN). |
| `disable` | string[] | Rule names to skip entirely. Built-in names: `god-file`, `god-class`, `long-method`, `deep-inheritance`, `dead-code`. Custom rules are skippable by their `name` too. |
| `excludePaths` | string[] | File-path substrings (or `**` glob fragments) that exclude matching nodes from rule evaluation. Useful for vendored code, generated sources, build output. |
| `custom[].name` | string | Unique name for a Cypher rule. Used in `disable` and to dedupe across layers. |
| `custom[].description` | string | Human-readable summary shown in the report. |
| `custom[].severity` | string | `ERROR` / `WARN` / `INFO`. Defaults to `WARN` if absent or invalid. |
| `custom[].cypher` | string | Cypher returning one row per violation with columns `subject` (the offender) and `message`. Available parameter: `$pid` (active project id). |
| `custom[].cypherKuzu` | string | *(optional)* Kuzu-dialect body. Kuzu uses a polymorphic `Node` table and a subset of Cypher; supply this when the rule's `cypher` body uses Neo4j-only features. The resolver picks `cypherKuzu` automatically when the active backend is Kuzu. |

#### Where rules can come from

| Use case | Where to put rules |
|---|---|
| Single project, shared with the team | Workspace `rules` in `settings.json`. Commit `.cvector/settings.json`. |
| Same workspace, two projects with different tolerances | Workspace `rules` (baseline) + per-project `rules` for the relaxed one. |
| Mix of standards across many workspaces | Per-workspace `rules` in each `settings.json`. |
| Existing project on rules.yml | Keep using `.cvector/rules.yml`. Optionally layer settings.json on top (e.g. per-project disable a rule without touching the shared YAML). |

### Tuning Kuzu memory

The embedded Kuzu backend keeps a **buffer pool** in RAM that caches pages and absorbs the bulk-load `COPY Node FROM ... (PARALLEL=FALSE)` step `cvector scan` runs on a fresh database. When this pool is too small for the project's size, the scan fails with:

```
java.lang.RuntimeException: Kuzu write failed: Buffer manager exception:
  Unable to allocate memory! The buffer pool is full and no memory could be freed!
```

Kuzu's pool is **fixed at database-open time** — it can't grow at runtime. To raise it, set a larger size up front and restart cvector. Four ways to do that, in precedence order (highest wins):

1. **`kuzu.bufferSizeMb` in `settings.json`** *(recommended for persistence)*:
   ```json
   {
     "backend": "embedded",
     "kuzu": { "bufferSizeMb": 2048 }
   }
   ```
2. **`-Dcvector.kuzu.bufferSizeMb=N`** system property — one-shot CLI override:
   ```bash
   cvector.exe -J-Dcvector.kuzu.bufferSizeMb=2048 scan
   ```
3. **`CVECTOR_KUZU_BUFFER_MB=N`** env var — convenient for shell scripts and Docker entrypoints.
4. **Auto-sized default** — when none of the above is set, cvector uses **25% of system RAM**, clamped to **`[512 MB, 4 GB]`**. A line on stderr at startup tells you what got picked: `Kuzu buffer pool size: 2048 MB (auto-sized from 8192 MB total RAM)`.

Most users on modern machines (8 GB+ RAM) won't need to set anything — the auto-sized default handles projects up to several thousand source files. Reach for an explicit value when a very large monorepo blows past the 4 GB cap, or when you want a deterministic ceiling on a CI runner.

### Diagnostic error page

When something goes wrong in the dashboard (404 to a typo'd URL, an uncaught exception inside a controller, the SPA not on classpath because the build wasn't done with `-Pdashboard-ui`, etc.), cvector replaces Spring Boot's bland Whitelabel Error Page with a self-contained HTML page that shows:

- Status code + reason.
- Request URI that triggered the error.
- Exception class + message (when present).
- Collapsible **stack trace**.
- Context-aware hint — e.g. a 404 under `/dashboard/` suggests rebuilding with `-Pdashboard-ui`; a 5xx suggests checking the terminal log.
- Links to known-good endpoints (`/api/health`, `/api/stats`, `/api/projects`, `/dashboard/`).

The same info is **also printed to stderr** as a multi-line block, so the terminal that started `cvector dashboard` shows the failure live — no log file to dig through. JSON clients (`Accept: application/json`) get a structured `{ timestamp, status, error, path, exception, message, stackTrace }` payload instead.

The controller has two `@RequestMapping("/error")` handlers: one with `produces = text/html` for browsers, and a second with **no `produces` filter** as a fallback for everything else (curl's `*/*`, MCP clients, scripts with no `Accept` header). Without the fallback, requests whose Accept header didn't include `text/html` slipped through to Spring's default 404-for-`/error` and the operator was left with no diagnostic — the fallback closes that hole.

This surface is implemented in `cvector-rest/CvectorErrorController` so it loads whenever the REST API is up, regardless of the `dashboard-ui` profile.

### `.cvector/rules.yml`

Optional **legacy** file. Generated by `cvector rules --init`. Sets thresholds, disables built-in rules, and defines custom Cypher rules. Same shape as the `rules` section in `settings.json` but in YAML. Still loaded automatically when present — sits between the built-in defaults and the workspace `rules` section in the layered merge.

```yaml
thresholds:
  godFileMethods: 40
  godClassMethods: 40
  longMethodLines: 80
  deepInheritance: 5

disable:
#  - long-method
#  - dead-code

excludePaths:
  - /internal/         # vendored ANTLR grammar code
  - /target/
  - /generated-sources/

custom:
  - name: no-system-out-in-services
    description: Service classes should not print to System.out.
    severity: WARN
    cypher: |
      MATCH (c:Class {projectId: $pid, isService: true})-[:CONTAINS]->(m:Method)-[:CALLS]->(callee:Method)
      WHERE callee.fqName CONTAINS 'PrintStream.println'
      RETURN c.fqName AS subject, callee.fqName AS message
    # Optional Kuzu-specific body. If absent, the cypher above is used for both backends.
    # cypherKuzu: |
    #   MATCH (c:Node)-[:CONTAINS]->(m:Node)-[:CALLS]->(callee:Node)
    #   WHERE c.label = 'Class' AND m.label = 'Method' AND callee.label = 'Method'
    #     AND c.isService = true AND callee.fqName CONTAINS 'PrintStream.println'
    #   RETURN c.fqName AS subject, callee.fqName AS message
```

### Spring profiles

| Profile | When | What changes |
|---|---|---|
| (default) | CLI commands | Single-shot execution, no web/MCP. |
| `mcp` | `cvector serve` *(profile only)* and `cvector dashboard` *(profile co-active alongside the web stack)* | MCP capabilities enabled. **Virtual threads are intentionally disabled here** (`spring.threads.virtual.enabled=false` in `application-mcp.properties`) — Spring AI's `McpServerSession.handle(...).block()` pins the carrier thread on large SSE writes, starving sibling tasks. Stay on platform threads. |
| (web mode) | `cvector dashboard` | Embedded servlet container on `rest.host:rest.port` (default `127.0.0.1:2969`). Virtual threads on. `mcp` profile is co-active so the chosen MCP HTTP transport runs in the same Tomcat. |

### Environment variables

| Variable | Effect |
|---|---|
| `cvector_role` / `CVECTOR_ROLE` | Restrict tools/REST paths exposed: `dev` (default), `architect`, `security`, `pm`. See [Roles](#roles--access-control). |
| `CVECTOR_EMBEDDED` | Same as `--embedded` flag — forces the embedded backend regardless of `settings.json`. Useful in shell scripts and Docker entrypoints. |

---

## CLI commands

Invoke as `java -jar cvector.jar <command> [args]`.

### Project lifecycle

| Command | Purpose | Key options |
|---|---|---|
| `init` | Create `.cvector/settings.json` in the current directory (defaults to `backend: "embedded"`). | `--project <name>` (defaults to dir name), `--force` |
| `project create <name>` | Add another project to the workspace. | `--root <path>`, `--switch` |
| `project list` | List all projects. | — |
| `project info` | Show active project's config. | — |
| `project switch <name>` | Change the active project. | — |
| `doctor` | Run setup diagnostics (config, Neo4j connectivity, schema). | — |

### Scan & ingest

| Command | Purpose | Key options |
|---|---|---|
| `scan [path]` | Full scan of `path` (default `.`). | `--project <name>`, `--no-clean` |
| `scan:incremental [root]` | Re-parse only files changed since last scan (git-diff-driven). | `--from <sha>` |
| `watch [dir]` | Live file-watch + re-parse, or `--cron` for scheduled re-scan. | `--debounce <ms>`, `--cron <expr>` |

### Graph queries

| Command | Purpose | Key options |
|---|---|---|
| `status` | Node + edge counts. | — |
| `search <query>` | Substring/wildcard search across node names. | `--limit <n>`, `--label <Class\|Method\|...>` |
| `explain <symbol>` | Full context: declaration, callers, callees, file. | — |
| `impact <symbol>` | Downstream impact graph for a symbol. | `--depth <n>` (default 3) |
| `query <cypher>` | Run an ad-hoc Cypher query (use `$pid` for active project). | `--json` |
| `recent` | List recently-ingested nodes. | `--since <24h\|7d\|...>`, `--limit <n>` |

### Analysis

| Command | Purpose | Key options |
|---|---|---|
| `onboard` | Codebase briefing: language mix, hubs, endpoints, dependencies. | — |
| `flows` | Trace flows from REST handlers, main methods, tests. | `--kind <rest\|main\|test\|all>`, `--max-depth <n>`, `--limit <n>` |
| `communities` | Detect call-graph clusters (leiden/louvain/connected-components). | `--algorithm`, `--min-size`, `--limit` |
| `service-links` | HTTP clients, message brokers, exposed endpoints, DB tables touched. | — |
| `diff <sha1> <sha2>` | Drift between two git commits. | `--repo`, `--keep`, `--include-calls` |
| `changelog` | Generate a changelog from recent graph changes. | `--since`, `--markdown` |
| `wiki -o <dir>` | Markdown documentation generated from the graph. | `--out / -o <dir>` (required) |

### Quality gates

| Command | Purpose | Key options |
|---|---|---|
| `rules` | Evaluate architecture rules; print violations. | `--init` (template), `--json` |
| `guard` | CI mode — exits non-zero on ERROR violations. | `--ci`, `--json`, `--fail-on-warn`, `--install-hook`, `--uninstall-hook`, threshold overrides |
| `audit` | Cross-reference Maven dependencies with OSV vulnerability DB. | `--ci`, `--timeout <s>` |
| `ci` | Orchestrate scan + rules + guard + audit. | `--skip-scan`, `--skip-audit`, `--fail-on-warn` |
| `badge` | Generate shields.io health badges. | `--out`, `--inject`, `--skip-audit` |

### Servers

| Command | Purpose |
|---|---|
| `dashboard` | Start the REST API + Angular SPA on port 2969. **Also activates the `mcp` Spring profile**, so a stdio MCP server runs in the same JVM on `System.in`/`System.out` alongside HTTP. Don't type into the launching terminal — anything you type goes to the MCP JSON-RPC reader. Use Ctrl-C to stop. |
| `serve` | Start the MCP server over stdio with **no** dashboard / REST listener. For when an MCP client (Claude Desktop, Cursor, Windsurf, etc.) launches cvector as a subprocess and you don't want the extra HTTP listener. |

Both modes share a single `GraphStore` bean — when `dashboard` co-hosts MCP, the MCP beans reuse the REST module's open Kuzu/Neo4j connection via `@ConditionalOnMissingBean(GraphStore.class)`, so the embedded DB is only opened once (avoids the Kuzu file-lock collision that a duplicate open would trigger).

### Embedded KuzuDB

| Command | Purpose |
|---|---|
| `embedded init` | Create the embedded database directory and bootstrap its schema. |
| `embedded info` | Show the embedded database path, size, and declared tables. |
| `embedded query <cypher>` | Run an ad-hoc Cypher query against the embedded database. |
| `embedded wipe` | Delete the embedded database directory for the active project. |

### Client config helpers

| Command | Registers cvector as an MCP server in… |
|---|---|
| `setup-claude` | Claude Desktop |
| `setup-cursor` | Cursor (`.cursor/mcp.json` in the project) |
| `setup-windsurf` | Windsurf |
| `setup-antigravity` | Google Antigravity |

---

## REST API

Started by `cvector dashboard`. Default port **2969**.

**Bound to loopback only by default.** The embedded web server only accepts connections from `127.0.0.1` / `::1` — requests from other hosts on the network are refused at the socket layer. To expose the dashboard externally (e.g. for a team dashboard on a trusted LAN), start it with `--server.address=0.0.0.0`:

```bash
cvector dashboard --server.address=0.0.0.0
```

The bind address is plain Spring Boot config (`server.address` in `application.properties`), so anything Spring accepts works (`192.168.1.10`, a specific NIC IP, etc.). Pair with a reverse proxy and auth if you go off-loopback.

| Method | Path | Description | Query params |
|---|---|---|---|
| `GET` | `/api/health` | Health check (Neo4j connectivity, project metadata). | — |
| `GET` | `/api/stats` | Node + edge counts. | — |
| `GET` | `/api/projects` | Active project context. | — |
| `GET` | `/api/search` | Substring search across node names. | `q` (required) |
| `GET` | `/api/explain` | Full context for a symbol. | `symbol` (required) |
| `GET` | `/api/impact` | Downstream impact for a symbol. | `symbol` (required), `depth` (default 3) |
| `GET` | `/api/test-impact` | Test methods that transitively reach a symbol. | `symbol` (required), `depth` (default 5) |

All responses are JSON. Role-based access via `cvector_role` env var (see [Roles](#roles--access-control)).

---

## Multi-project workflows

Every cvector workspace can hold many projects (`projects.<name>` map in `settings.json`). As of 0.2.0, both the CLI and the MCP tool surface treat the workspace as a multi-project catalog:

- **Shared Kuzu DB by default.** All non-isolated projects live in one `~/.cvector/kuzu-data/graph.kuzu/` partitioned by `projectId`. Zero-cost project switching, native cross-project Cypher, single backup directory. Per-project escape hatch: set `projects.<name>.isolated: true` (or `cvector project create --isolated`) for the legacy per-project-directory layout — useful when concurrent CI scans need file-lock isolation.
- **Default project resolution.** Both CLI commands and MCP tools accept an optional project argument (name, UUID, or rootPath). When omitted, they fall back to `activeProject` in `settings.json`. Set the default with `cvector project switch <name>` or the `cv_set_default_project` MCP tool.
- **Sub-directory overlap is rejected.** `cvector project create` and `cv_add_project` both refuse a rootPath that equals, lives inside, or contains an existing project's rootPath — onboarding overlapping ranges produces ambiguous ownership for every file in the overlap.
- **Cross-project queries.** Tools that support it (`cv_search`, `cv_stats`, `cv_health`, `cv_changes`, `cv_communities`, `cv_service_links`) accept the literal `"*"` for `project` to scope across every registered project. Each result row is tagged with its `project` block so the agent can disambiguate.
- **Project context in every response.** Every MCP tool response that targets a specific project carries a top-level `project: { projectId, name, rootPath, isolated }` block. Confirms which project the result came from — defends against silent default-routing surprises.

### Onboarding a new project (CLI)

```bash
cd /path/to/your/codebase
cvector init --project my-app                    # bootstraps .cvector/settings.json
cvector scan                                     # populates the graph

# Add a second project to the same workspace
cvector project create backend-api --root /work/backend --switch
cvector scan                                     # against the new active project

# Or use a one-shot override without flipping the default
cvector --project my-app status                  # query a different project for one call
```

### Onboarding via the MCP server (AI agent)

```jsonc
// 1. Discover what's there
{"name": "cv_list_projects"}

// 2. New codebase — register + scan + brief in one call
{"name": "cv_onboard_project",
 "arguments": {"name": "auth-svc", "rootPath": "/work/auth-svc"}}

// 3. Targeted query against the new project (project arg accepts name/UUID/path)
{"name": "cv_search",
 "arguments": {"project": "auth-svc", "query": "TokenStore"}}

// 4. Cross-project query
{"name": "cv_search",
 "arguments": {"project": "*", "query": "org.slf4j"}}

// 5. Change the workspace default so subsequent calls can omit `project`
{"name": "cv_set_default_project",
 "arguments": {"project": "auth-svc"}}
```

### Project lifecycle MCP tools

| Tool | Purpose |
|---|---|
| `cv_list_projects` | Enumerate every project with name, projectId, rootPath, isolation flag, scan freshness, node/edge counts. The agent's primary discovery tool. |
| `cv_find_project` | Look up a project by name, UUID, or directory path (path matching tolerates descendants of a registered rootPath). |
| `cv_add_project` | Register a new project entry (no scan). Rejects overlapping rootPaths. |
| `cv_scan_project` | (Re-)populate the graph for an existing project. Synchronous by default; pass `async: true` for codebases that may exceed the client's HTTP timeout. Runs in-process against the dashboard's live Kuzu/Neo4j handle so it avoids the file-lock collisions a subprocess scan would hit. |
| `cv_onboard_project` | Register + scan + brief in one call — convenience wrapper. Supports `async: true`. |
| `cv_remove_project` | Delete a project's graph data AND remove the workspace entry. Requires `confirm: true`. Supports `async: true`. Returns `nodesDeleted` (actual count) so you can verify against the dry-run promise. |
| `cv_purge_project` | Delete graph data only; keep the registration. Recovery primitive for rebuilding a corrupted graph from scratch — pair with `cv_scan_project`. Supports `async: true`. |
| `cv_purge_orphans` | Find projectIds present in the shared Kuzu DB but no longer registered in `settings.json` (e.g. left over from a prior delete or a rename) and delete them. Supports `async: true`. |
| `cv_set_default_project` | Update the workspace's active project so subsequent tool calls can omit `project`. |
| `cv_job_status` / `cv_jobs_list` | Poll an async job by id, or enumerate every job currently in the registry. See [Long-running tools: async + cv_job_status](#long-running-tools-async--cv_job_status). |

### Multi-workspace orphans on a shared Kuzu DB

The shared Kuzu DB at `~/.cvector/kuzu-data/graph.kuzu/` sits **below** the workspace boundary: each `.cvector/settings.json` is its own workspace registry, but all non-isolated workspaces use the same on-disk Kuzu directory partitioned by `projectId`. The consequence is that data from one workspace appears as "orphans" from another workspace's point of view.

Concrete example: workspace **A** (rooted at `/work/auth-svc`) registers project `auth-svc` with `projectId=A123` and scans it. Workspace **B** (rooted at `/work/billing`) registers `billing` with `projectId=B456`. Both processes use the same `~/.cvector/kuzu-data/graph.kuzu/` because neither is isolated. From B's `cv_list_projects` perspective the `auth-svc` data is unowned — A123 isn't in B's `settings.json` — so `cv_purge_orphans` from B would offer to delete it. That's correct behaviour: every workspace queries through its own registry view, and the shared DB doesn't know about workspace boundaries.

Three ways to handle this:

| Want | Do |
|---|---|
| Each project isolated; no cross-workspace visibility at all | Add `"isolated": true` to the project entry, or pass `--isolated` to `cvector project create`. Project gets its own `~/.cvector/kuzu-data/<projectId>/` directory with its own file lock. |
| One workspace, many projects | Register them all in a single `.cvector/settings.json` (e.g. a top-level repo's `.cvector/` directory) and use `cvector project create` to add each one. All projects share the same DB and registry. |
| Multiple independent workspaces, same shared DB | Live with the orphan-from-other-workspace artifact. Run `cv_purge_orphans` from any workspace only when you genuinely want to forget another workspace's data; treat it as "delete projects this workspace doesn't recognise" rather than "garbage collect". |

`cvector scan` and `cv_scan_project` both gate on the active project being in the current workspace's registry (via `requireActiveProject`) — so a workspace can never accidentally write data under a projectId it doesn't own. The orphan artifact only arises from running multiple workspaces against the same shared DB, which is supported but worth being aware of.

### Switching backends keeps projects intact

The `projects` map in `settings.json` is backend-agnostic. Flipping `backend` between `embedded`, `remote`, and `docker` doesn't drop any project entries — the data just lives in a different store. After switching, re-run `cvector scan` against each project you want to repopulate.

### Upgrading from 0.1.x

The MCP tool API is breaking-changed in 0.2.0: every read tool now requires (or has an optional default fallback for) a `project` argument. AI agents wired against the old `cvector serve` will auto-rediscover the new tool descriptions on reconnect and adapt. Settings.json is forward-compatible — existing files keep working; the new `kuzu.sharedDb` and per-project `isolated` fields are both `null` by default with sensible interpretations.

After upgrading, run `cvector scan` once per project to populate the new shared Kuzu DB. The old `~/.cvector/kuzu-data/<projectId>/` directories are left in place for rollback; delete them once you've confirmed the shared layout works for your workflow.

---

## MCP server

cvector ships a full MCP server with **33 tools**, **9 resources**, and **9 prompts**. Run it via `cvector serve` (stdio subprocess) or co-host it with the dashboard (`cvector dashboard` adds the chosen HTTP transport in the same Tomcat).

The MCP server runs in-process with the graph store — `cv_scan_project`, `cv_purge_project`, and friends reuse the dashboard's live Kuzu/Neo4j handle rather than spawning a subprocess, which avoids the file-lock collisions an out-of-process scan would hit on the embedded backend.

### MCP transports

cvector supports all three MCP transports the spec defines. Pick one via `mcp.transport` in `settings.json` (or via the Settings view in the dashboard, or via `cvector mcp update --transport ...`). Each transport selects a different Spring AI auto-configuration at boot — to switch, edit the setting and restart the dashboard / serve process.

| `mcp.transport` | Protocol | Wire shape | Best for |
|---|---|---|---|
| `streamable` *(default)* | MCP 2025-03-26 — **Streamable HTTP** | Single endpoint `POST /mcp`. Server assigns an `Mcp-Session-Id` response header on the `initialize` call; clients echo it back on every subsequent request. Optional SSE stream-back when the server has more than one response. | Modern MCP clients: **Eclipse Copilot**, MCP Inspector v2, newer Claude integrations. |
| `sse` | MCP 2024-11-05 — **HTTP+SSE** | Two endpoints. Client opens `GET /sse` to receive an `endpoint` event carrying `/mcp/message?sessionId=<uuid>`, then `POST`s every JSON-RPC call to that per-session URL. Responses come back on the original SSE stream. | Legacy MCP clients: original Claude Desktop, MCP Inspector v1, anything pre-2025. |
| `stdio` | JSON-RPC over the subprocess's stdin/stdout | No port involved. The MCP client launches `cvector serve` as a child process. | Claude CLI, agent frameworks that spawn the server, and any client that doesn't want a network socket. Setting this **disables HTTP MCP co-hosting on the dashboard** — use `cvector serve` instead. |

Internally, `streamable` and `sse` flip `spring.ai.mcp.server.protocol` (`STREAMABLE` / `SSE`) plus the matching endpoint property — Spring AI's auto-config wires the matching `WebMvc*ServerTransportProvider` and the others stay inert. CORS is open on `/sse`, `/mcp`, and `/mcp/**` (allowed origin patterns: `*`) so browser-hosted MCP Inspector tabs can connect.

A workspace can hold any combination of MCP clients pointed at the same cvector dashboard — the only constraint is that one server instance speaks one HTTP protocol at a time. If you have clients on both protocols, run two cvector processes on different ports, or upgrade the older client.

### MCP tools

33 tools in three families: project lifecycle (register / scan / purge / remove), read queries (search / explain / impact / etc.), and async-job plumbing. Every read tool accepts an optional `project` argument (name, UUID, or rootPath) and falls back to the workspace's `activeProject` when omitted; the wildcard `"*"` runs cross-project for the tools that support it.

**Project lifecycle**

| Tool | Description | Parameters |
|---|---|---|
| `cv_list_projects` | Enumerate every project with name, projectId, rootPath, isolation flag, scan freshness, node/edge counts. | — |
| `cv_find_project` | Look up by name / UUID / rootPath (descendant paths match). | `query` (req) |
| `cv_add_project` | Register a new project (no scan). Rejects overlapping rootPaths. | `name` (req), `rootPath` (req), `isolated` (opt) |
| `cv_scan_project` | (Re-)populate the graph for an existing project. Supports `async`. | `project` (opt), `async` (opt) |
| `cv_onboard_project` | Register + scan + briefing in one call. Supports `async`. | `name` (req), `rootPath` (req), `isolated` (opt), `async` (opt) |
| `cv_set_default_project` | Update the workspace's active project. | `project` (req) |
| `cv_remove_project` | Delete a project's graph data AND remove the registration. Two-step (dry-run + `confirm:true`). Supports `async`. | `project` (opt), `confirm` (opt), `async` (opt) |
| `cv_purge_project` | Delete a project's graph data but KEEP its registration — recovery primitive for rebuilding a corrupted graph. Two-step. Supports `async`. | `project` (opt), `confirm` (opt), `async` (opt) |
| `cv_purge_orphans` | Find and delete graph data for projectIds present in the shared Kuzu DB but no longer registered in `settings.json`. Two-step. Supports `async`. | `confirm` (opt), `async` (opt) |
| `cv_job_status` | Poll the status of a job started with `async: true`. | `jobId` (req) |
| `cv_jobs_list` | List every job currently in the registry (running + retained for ~1 h post-completion). | `state` (opt: `running`/`done`/`failed`) |

**Read & analysis**

| Tool | Description | Parameters |
|---|---|---|
| `cv_stats` | Node + edge counts. | `project` (opt, `*` for all) |
| `cv_health` | Connectivity + graph size + last scan commit. | `project` (opt, `*` for all) |
| `cv_search` | Substring node search; supports `*` wildcards. | `query` (req), `project` (opt, `*` for all), `limit` (def 25), `label` (opt) |
| `cv_explain` | Symbol context: type, file:line, callers, callees. Accepts bare names, full FQ names with/without signature, and `Class.method` partial-FQ. | `symbol` (req), `project` (opt) |
| `cv_impact` | Downstream impact via CALLS/REFERENCES. | `symbol` (req), `project` (opt), `depth` (def 3) |
| `cv_test_impact` | Tests that transitively reach a symbol. | `symbol` (req), `project` (opt), `depth` (def 5) |
| `cv_context` | Members + references for a class/method. | `symbol` (req), `project` (opt) |
| `cv_rename` | Rename impact: callers, refs, importing files. | `symbol` (req), `project` (opt) |
| `cv_path` | Shortest CALLS path between two symbols. | `from` (req), `to` (req), `project` (opt), `maxDepth` (opt) |
| `cv_changes` | Recently-ingested nodes. | `since` (def `24h`), `project` (opt, `*` for all), `limit` (def 50) |
| `cv_onboard` | Full codebase briefing. | `project` (opt) |
| `cv_wiki` | Structured documentation snapshot. | `project` (opt) |
| `cv_rules` | Rules engine results. | `project` (opt) |
| `cv_communities` | Cluster the call graph. | `algorithm` (leiden\|louvain\|connected-components), `project` (opt, `*` for all), `minSize` (def 3), `limit` (def 10) |
| `cv_flows` | Trace from REST/main/test entry points. | `kind` (rest\|main\|test\|all), `project` (opt), `maxDepth` (def 3), `limit` (def 25) |
| `cv_trace` | Multi-hop trace from a starting node. | `from` (req), `project` (opt), `maxDepth` (opt) |
| `cv_service_links` | Cross-service deps via HTTP, queues, exposed endpoints. | `project` (opt, `*` for all) |
| `cv_db_impact` | Methods touching a given table/column. | `table` (req), `column` (opt), `project` (opt) |
| `cv_guard` | Quality-gate pass/fail. | `project` (opt) |
| `cv_audit` | OSV vulnerabilities × graph blast radius. | `project` (opt) |
| `cv_diff_start` / `cv_diff_status` | Async git-diff between two commits (separate subprocess). | `shaA` (req), `shaB` (req), `project` (opt), `includeCalls` (opt), `keep` (opt) |

### Long-running tools: async + `cv_job_status`

Five tools accept an optional `async: true` argument: `cv_scan_project`, `cv_onboard_project`, `cv_purge_project`, `cv_purge_orphans`, `cv_remove_project`. Use it for any call that might exceed the client's HTTP read timeout (typical default 30 s) — e.g. a from-scratch scan of a multi-thousand-file codebase, or a purge of a large graph.

Two-step pattern when `async: true`:

```jsonc
// 1. Submit the work. Returns immediately (typically <100 ms).
{"name": "cv_scan_project", "arguments": {"project": "my-app", "async": true}}
// → { "jobId": "27f12ab9-...", "kind": "cv_scan_project", "state": "running", "accepted": true, "startedAt": "..." }

// 2. Poll cv_job_status until state is "done" or "failed".
{"name": "cv_job_status", "arguments": {"jobId": "27f12ab9-..."}}
// → while running: { "state": "running", "elapsedMs": 1234 }
// → on done:       { "state": "done",    "elapsedMs": 17728, "result": { ...full sync envelope... } }
// → on failed:     { "state": "failed",  "exceptionClass": "...", "exceptionMessage": "..." }
```

When state reaches `done`, the `result` field is the exact response the synchronous variant of the tool would have returned — same shape, same keys. No second call required.

**Recovery & lifecycle**

- **Lost jobId?** Call `cv_jobs_list` to enumerate every job currently tracked (running + terminal jobs retained for ~1 h post-completion). Filter by `state: "running"` / `"done"` / `"failed"`. Useful when token-window truncation or a conversation restart dropped the original envelope.
- **Stuck jobs.** Every async job has a built-in 15-minute wall-clock cap. If a job is still `RUNNING` past that deadline, the watchdog flips it to `FAILED` with a `TimeoutException` (best-effort `Future.cancel(true)` follows; native Kuzu calls can't be interrupted so the background work may keep running until completion, but the job state stops reporting `running` forever).
- **Slow-sync hint.** When a *synchronous* call to one of the async-capable tools runs ≥10 s, the response gains a `syncElapsedMs` field and a one-line `hint` recommending `async: true` for similarly-sized future calls. Behaviour is unchanged; the hint self-documents the escape hatch when you're close to typical client timeouts.

When `confirm: true` is required (`cv_purge_project`, `cv_purge_orphans`, `cv_remove_project`), the dry-run path stays synchronous since it only counts. Only the destructive path moves to the background when `async: true` is set.

### MCP resources

9 browsable read-only JSON snapshots — no parameters, no composition needed. All are scoped to the workspace's active project.

| URI | Contents |
|---|---|
| `cvector://stats` | Node + edge counts. |
| `cvector://schema` | All node labels + relationship types with counts. |
| `cvector://files` | Every File node with language + method counts. |
| `cvector://projects` | All tracked projects. |
| `cvector://health` | Top 20 god-files / god-classes / long-methods / dead-code. |
| `cvector://onboard` | Briefing: language mix, hubs, endpoints, dependencies. |
| `cvector://infrastructure` | Endpoints, listeners, scheduled jobs, config keys, env vars, container ports, IaC resources. |
| `cvector://guard` | Quality-gate pass/fail with severity totals. |
| `cvector://communities` | Call-graph clusters from the community-detection algorithms. |

### MCP prompts

9 pre-built conversation starters that name the tools/resources the assistant should call.

| Prompt | Arguments | Purpose |
|---|---|---|
| `cvector-discover-projects` | — | Walk through `cv_list_projects` and explain the workspace layout. |
| `cvector-onboard` | — | Architecture brief for a new team member. |
| `cvector-onboard-new-project` | `name`, `rootPath` | End-to-end registration + scan + briefing for a brand-new codebase. |
| `cvector-review-change` | `symbol` (req) | Impact analysis pre-refactor. |
| `cvector-health-check` | — | Prioritised action items. |
| `cvector-explain-module` | `path` (req) | Module deep-dive. |
| `cvector-migration-plan` | `from` (req), `to` (req), `scope` (opt) | Phased migration plan with risk register. |
| `cvector-infrastructure` | — | Infrastructure surface audit. |
| `cvector-cross-project-audit` | — | Cross-project audit using the wildcard `project: "*"` reads. |

### MCP client setup

Pick your client and run the matching helper from the project root:

```bash
java -jar cvector.jar setup-claude           # Claude Desktop
java -jar cvector.jar setup-cursor           # Cursor (.cursor/mcp.json local to project)
java -jar cvector.jar setup-windsurf         # Windsurf
java -jar cvector.jar setup-antigravity      # Google Antigravity
```

Each helper writes an MCP server entry that runs `java -jar <path>/cvector.jar serve`. Manual config equivalent:

```json
{
  "mcpServers": {
    "cvector": {
      "command": "java",
      "args": ["-jar", "/path/to/cvector.jar", "serve"]
    }
  }
}
```

### Testing the stdio server with `@modelcontextprotocol/inspector`

The official [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the quickest way to confirm `cvector serve` is wired correctly and to browse its tools / resources / prompts without involving an IDE. Run it from inside any project workspace that has a `.cvector/settings.json`:

```bash
cd /path/to/your/project

# Windows .exe install
npx @modelcontextprotocol/inspector "%LOCALAPPDATA%\Programs\cvector\cvector.exe" serve

# Or against the fat jar (faster iteration, easier `-D` overrides)
npx @modelcontextprotocol/inspector java -jar /path/to/cvector-app/target/cvector.jar serve
```

The Inspector spawns cvector as a child process, attaches to its stdin/stdout, and opens a web UI (default `http://localhost:6274`). With the connection panel pre-filled (Transport `STDIO`, the command + `serve` arg), click **Connect** — you should see:

- **Server log** pane shows cvector's stderr banner: `cvector mcp server (stdio) ready` followed by `Registered tools: 33` / `Registered resources: 9` / `Registered prompts: 9`.
- **Tools** tab lists all 33 `cv_*` tools, callable with form-rendered argument inputs.
- **Resources** tab lists the 9 `cvector://*` URIs; click one to fetch its JSON.
- **Prompts** tab lists the 9 prompts.

#### Gotchas

- **Working directory matters.** cvector's `.cvector/settings.json` is found by walking up from the cwd Inspector launched it from. Either `cd` into the project before invoking Inspector or set a working directory in the Inspector's "Configuration" panel once it's open.
- **Don't type into the Inspector's terminal.** cvector's stdio MCP reads stdin as JSON-RPC; arbitrary input crashes the parser.
- **Logs go to `~/.cvector/mcp-server.log`** plus the Inspector's "Server log" pane (stderr). cvector keeps stdout clean for JSON-RPC by design.
- **Reconnects respawn the process.** `cvector serve` is single-shot — it exits when stdin closes, so each Inspector reconnect starts a fresh JVM. First start pays the JDK warm-up cost (~3.8 s on this repo).

For a one-shot CLI sanity check without the web UI:

```bash
npx @modelcontextprotocol/inspector --cli "%LOCALAPPDATA%\Programs\cvector\cvector.exe" serve
```

That mode prints the JSON-RPC handshake to the terminal so you can verify `initialize` succeeds without opening a browser tab.

### Testing the HTTP server (dashboard mode) with Inspector

`cvector dashboard` exposes the same tools over HTTP. Which protocol the Inspector should pick depends on the current `mcp.transport` setting:

```bash
cvector.exe dashboard                       # leave running in another terminal
npx @modelcontextprotocol/inspector         # opens the web UI with no preset
```

In the Inspector connection panel:

| `mcp.transport` | Inspector "Transport Type" | URL |
|---|---|---|
| `streamable` *(default)* | **Streamable HTTP** | `http://localhost:2969/mcp` |
| `sse` | **SSE** | `http://localhost:2969/sse` |

Click **Connect**. The CORS headers cvector ships on `/sse`, `/mcp`, and `/mcp/**` (allowed origin patterns: `*`) let the Inspector's browser tab complete the handshake.

Common gotchas:

- **"Failed to fetch"** almost always means the Inspector's transport dropdown is set to the protocol your dashboard isn't serving. Either flip the dropdown or run `cvector mcp update --transport <choice>` and restart.
- **404 on `/mcp` or `/sse`** means `mcp.url` in `settings.json` points at a different path. Match the URL or restart the dashboard after editing.
- **Tools list shows up but tool calls hang silently.** Classic Streamable-HTTP-client-on-an-SSE-server (or vice versa) symptom — the handshake half-succeeds because the initial `initialize` is forgiving, but subsequent POSTs land at the wrong handler. Confirm transport match.

For a scripted smoke check, see the Python drivers in `.test/` (`test_stdio.py`, `test_streamable.py`, `test_sse.py`) — they exercise the full `initialize` → `notifications/initialized` → `tools/list` handshake against a freshly-built jar.

### GitHub Copilot for Eclipse

Eclipse Copilot's MCP support uses the modern **Streamable HTTP** transport — which is the cvector default since the Spring AI 2.0 / MCP SDK 2.0 bump, so a freshly-installed cvector dashboard works out of the box. Two equivalent ways to wire it up:

**Option A — point Copilot at the running dashboard (recommended).** Leave `cvector dashboard` running in another terminal (or as a Windows / launchd service), then add an MCP server entry in Eclipse:

```json
{
  "mcpServers": {
    "cvector": {
      "url": "http://localhost:2969/mcp"
    }
  }
}
```

Copilot opens a Streamable HTTP session against `/mcp`; cvector replies with an `Mcp-Session-Id` header, and every subsequent tool call carries that header. No subprocess, no stdin/stdout plumbing, and the dashboard SPA stays available on the same port.

**Option B — let Copilot launch `cvector serve` as a stdio subprocess.** Use this if you'd rather not keep a long-running dashboard around:

```json
{
  "mcpServers": {
    "cvector": {
      "command": "C:\\Users\\<your-user>\\AppData\\Local\\Programs\\cvector\\cvector.exe",
      "args": ["serve"]
    }
  }
}
```

(Linux / macOS: replace the `command` with the absolute path to the installed `cvector` binary — no `.exe`.)

**Where to put the snippet** — try these in order, the right location varies by plugin version:

1. **Eclipse → Preferences → GitHub Copilot → Model Context Protocol** (or `MCP Servers`). If you see an `Edit JSON` / `Configure` button, paste the snippet directly into the editor.
2. **Workspace file**: `<workspace>/.metadata/.plugins/com.github.copilot/mcp.json`. The plugin id varies — search your workspace `.metadata/.plugins/` for a directory containing `copilot` or `mcp`.
3. **User-home fallback**: `~/.github-copilot/mcp.json` or `~/.copilot/mcp.json`. Some Copilot ports share settings here across IDEs.

**Verify it's working:**

- Restart Eclipse (or use the plugin's "Reload MCP Servers" command if present).
- Open Copilot Chat and ask "what tools do you have?" — `cv_search`, `cv_explain`, `cv_impact`, etc. should show up alongside Copilot's built-in tools.
- For option A, watch the dashboard's `~/.cvector/mcp-server.log`: each Copilot call logs `mcp POST /mcp -> 200 (… ms) sessionId=<uuid>` via `McpRequestLogFilter`. A `404 [STALE-SESSION]` line means Copilot is sending a session id the server has evicted — restart the dashboard and reconnect.
- For option B, the launch banner reads `cvector mcp server (stdio) ready` followed by `Registered tools: 33`.

**Earlier Copilot plugin versions that only speak SSE.** Run cvector with `mcp.transport=sse` (`cvector mcp update --transport sse`) and point Copilot at `http://localhost:2969/sse`. The dashboard then exposes the legacy MCP 2024-11-05 protocol and Streamable HTTP clients no longer work against it. If you need both at the same time, run two cvector processes on different ports.

---

## Supported languages

25 parser modules. Each emits `File`, `Class`/`Struct`/`Interface`, `Method`, and language-specific nodes (`ApiEndpoint`, `Table`, `Resource`, `ContainerImage`, …) into the graph.

| Module | Language | Extensions | Parser kind |
|---|---|---|---|
| `cvector-parser-java` | Java | `.java` | JavaParser AST |
| `cvector-parser-python` | Python | `.py`, `.pyi` | ANTLR4 (Python 3.13) |
| `cvector-parser-typescript` | TypeScript | `.ts`, `.tsx` + JS exts | ANTLR4 |
| `cvector-parser-javascript` | JavaScript | `.js`, `.mjs`, `.cjs` | ANTLR4 |
| `cvector-parser-go` | Go | `.go` | ANTLR4 |
| `cvector-parser-rust` | Rust | `.rs` | ANTLR4 |
| `cvector-parser-c` | C | `.c`, `.h` | ANTLR4 |
| `cvector-parser-cpp` | C++ | `.cpp`, `.cc`, `.cxx`, `.hpp`, `.hh`, `.hxx` | ANTLR4 (CPP14) |
| `cvector-parser-csharp` | C# | `.cs` | ANTLR4 (C# v7) |
| `cvector-parser-kotlin` | Kotlin | `.kt`, `.kts` | ANTLR4 |
| `cvector-parser-php` | PHP | `.php`, `.phtml` | ANTLR4 |
| `cvector-parser-solidity` | Solidity | `.sol` | ANTLR4 |
| `cvector-parser-graphql` | GraphQL | `.graphql`, `.gql` | ANTLR4 |
| `cvector-parser-sql` | SQL (generic) | `.sql` | JSqlParser |
| `cvector-parser-plsql` | Oracle PL/SQL | `.pls`, `.plsql`, `.pks`, `.pkb` | ANTLR4 |
| `cvector-parser-tsql` | T-SQL (SQL Server) | `.tsql` | ANTLR4 |
| `cvector-parser-hive` | HiveQL | `.hql`, `.hive` | ANTLR4 |
| `cvector-parser-sparql` | SPARQL | `.sparql`, `.rq` | ANTLR4 |
| `cvector-parser-cypher` | Cypher | `.cypher`, `.cql` | ANTLR4 |
| `cvector-parser-protobuf` | Protocol Buffers 3 | `.proto` | ANTLR4 |
| `cvector-parser-terraform` | Terraform / HCL | `.tf`, `.tfvars`, `.hcl` | ANTLR4 |
| `cvector-parser-bicep` | Azure Bicep | `.bicep` | ANTLR4 |
| `cvector-parser-docker` | Dockerfile | `Dockerfile`, `Containerfile`, `*.dockerfile` | Line-based |
| `cvector-parser-config` | YAML / JSON / properties / Maven POM / `.env` | `.yml`, `.yaml`, `.json`, `.env`, `.properties`, `pom.xml` | Native libraries |
| `cvector-parser-style` | CSS / SCSS / SASS / Less / Stylus | `.css`, `.scss`, `.sass`, `.less`, `.styl`, `.stylus` | Regex-based |

---

## Embedded KuzuDB (default backend)

The `cvector-embedded-kuzudb-server` module ships an in-process [KuzuDB](https://kuzudb.com) store — and is the **default** graph backing for cvector. KuzuDB is an embedded property-graph database (MIT licensed) that speaks Cypher: no separate process, no port, no Docker. The Kuzu native library is bundled in the artifact for Linux / macOS / Windows. Set `backend: "remote"` or `backend: "docker"` in `settings.json` to opt into a Neo4j backing instead — see [Backends & settings.json](#backends--settingsjson).

The module is named with its backend (`-kuzudb-`) so additional embedded backends (e.g. DuckDB, SQLite-backed graph) can ship as parallel modules without colliding.

### How it works

- **Schema.** `KuzuSchemaBootstrap` declares one polymorphic `Node` table (~65 typed columns covering everything parsers emit) plus 14 typed `REL` tables (`CALLS`, `CONTAINS`, `EXTENDS`, `IMPLEMENTS`, `IMPORTS`, `EXPOSES`, `HANDLES`, `DEPENDS_ON`, `DECLARES`, `READS_TABLE`, `WRITES_TABLE`, `READS_COLUMN`, `WRITES_COLUMN`, `READS_CONFIG`). `ALTER TABLE Node ADD ...` runs at boot for any property the current schema declares that's missing on disk, so existing databases lift in place — no wipe needed across upgrades.
- **`GraphStore` abstraction.** `cvector-core/store/GraphStore` is the single interface every read path goes through; both `KuzuGraphStore` and `Neo4jGraphStore` implement it. The MCP, REST, rules, and watcher modules all consume it directly. Every query method takes a `projectId` argument that's pushed into the Cypher predicate — required since shared-DB mode puts multiple projects' nodes in the same Kuzu directory.
- **Cypher dialect translation.** Kuzu's Cypher subset replaces Neo4j features the parsers and queries use: `regexp_matches()` in place of `=~`, `list_slice()` in place of slice subscripts, variable-length `*1..N` traversal in place of `shortestPath()`. Custom rules in `.cvector/rules.yml` can supply an optional `cypherKuzu` body when their Neo4j Cypher uses features Kuzu doesn't have (`EXISTS { ... }` subqueries, `SET r += $props`, etc.).
- **Thread safety.** Kuzu's Java `Connection` is single-threaded; `EmbeddedKuzu` serialises every native call through one mutex so concurrent reads from MCP tool calls + REST controllers + the scan ingestor stay correct. Throughput per query is unchanged (Kuzu's intra-query parallelism still kicks in via `setMaxNumThreadForExec`); only the JNI entry is serialised.
- **Two ingest paths**, picked automatically by `InProcessScanService`:
  - **Bulk mode** (empty project — `isKuzuEmpty(projectId)`): `KuzuBulkLoader` buffers events in memory, stages typed CSVs, and runs `COPY Node FROM '...'` + one `COPY <REL_TYPE> FROM '...'` per populated edge table. Fastest path for first-time scans.
  - **Merge mode** (re-scan): `KuzuIngestor` carries a `contentHash` SHA-256 (excluding `lastIngestedAt`) on every row. At flush time it pre-fetches existing `(id, contentHash)` and `(from, to)` pairs and skips MERGEs for unchanged rows. Content-hash file-level skip on top of that means re-scans of unchanged source code touch almost nothing — typical re-scan flushes a handful of nodes/edges instead of the whole graph.
- **Chunked deletes.** `deleteProjectSubtree` (the surface behind `cv_remove_project` / `cv_purge_project` / `cv_purge_orphans`) issues `DETACH DELETE` in 500-node batches with the connection lock released between batches. Concurrent `cv_health` / `cv_search` calls interleave with the delete instead of queueing behind one long native call.
- **In-process scan.** `cv_scan_project` runs in the same JVM as the dashboard's `GraphStore`, reusing the live Kuzu/Neo4j handle. Avoids the file-lock collisions a subprocess scan would hit on the embedded backend; pairs with `JobRegistry` for the optional async path.
- **REST + MCP parity.** Every controller (`StatsController`, `QueryController`, `CodeHealthController`, `FlowsController`, etc.) routes through `GraphStore`. Every MCP tool ditto. `cvector-rules` and `cvector-watcher` are backend-agnostic — neither has a `cvector-neo4j` dependency.

### CLI

```bash
cvector embedded init                 # create the database directory and bootstrap the schema
cvector embedded info                 # path, on-disk size, declared tables
cvector embedded query "MATCH (n:Node) RETURN count(n)"
cvector embedded wipe                 # delete the project's database directory
```

The embedded backend is the default — no flag required. The legacy `--embedded` global flag (and `CVECTOR_EMBEDDED=true` env var) remain for back-compat: they force embedded regardless of `settings.json`. To switch to a Neo4j backing instead, use `cvector db --remote` or `cvector db --docker` and fill in the `neo4j` block of `settings.json`. See [Backends & settings.json](#backends--settingsjson).

### Layout

| Path | Contents |
|---|---|
| `~/.cvector/kuzu-data/<projectId>/graph.kuzu/` | KuzuDB database directory for the project. |

A KuzuDB "database" is a directory, not a single file — Kuzu writes columnar storage files inside it. Wipe by deleting the directory.

### Config knobs

| System property | Default | Effect |
|---|---|---|
| `cvector.embedded` | unset | Same as the `--embedded` flag. Set via `-Dcvector.embedded=true` or env. |

### Trade-offs: Kuzu (default) vs Neo4j (optional)

| Aspect | KuzuDB (default, in-process) | Neo4j (optional, Bolt) |
|---|---|---|
| Cold start | ~50 ms | ~10 s |
| Memory floor | ~50 MB | ~500 MB heap |
| License | MIT | GPL v3 (Community) |
| Cypher dialect | Subset; no `SET n += $props`, limited `MERGE` | Reference |
| Schema | Upfront `CREATE NODE TABLE` (handled by cvector at boot) | Schema-on-read |
| Tooling | CLI + Kuzu Explorer (separate web UI) | Neo4j Browser, GDS, Bloom |
| Network | Java function call | Bolt TCP |
| Setup | Built-in; no extra process | Run a Neo4j 5.x instance or use `backend: "docker"` |

---

## Rules & quality gates

Built-in rules ship in `cvector-rules`. Run via `cvector rules` (human/JSON) or `cvector guard` (CI mode — exits non-zero on errors).

| Rule | Threshold key | Default | Severity | Description |
|---|---|---|---|---|
| `god-file` | `godFileMethods` | 40 | ERROR | File contains more methods than the threshold. |
| `god-class` | `godClassMethods` | 40 | ERROR | Class contains more methods than the threshold. |
| `long-method` | `longMethodLines` | 80 | WARN | Method body exceeds the line threshold. |
| `deep-inheritance` | `deepInheritance` | 5 | WARN | Class extends-chain exceeds the depth threshold. |
| `dead-code` | — | — | WARN | Private/package method with no incoming `CALLS` edges. Skipped for tests, `main`, constructors, REST handlers. |

Path-based exclusions (`excludePaths` in `rules.yml`) skip vendored grammar code, generated sources, etc. Custom Cypher rules are first-class — declare them under `custom:` in `rules.yml`.

---

## Roles & access control

Set `cvector_role` (env) to restrict MCP tools and REST paths.

| Role | Tools | REST paths |
|---|---|---|
| `dev` (default) | All | All |
| `architect` | `cv_stats`, `cv_health`, `cv_list_projects`, `cv_find_project`, `cv_search`, `cv_context`, `cv_explain`, `cv_impact`, `cv_test_impact`, `cv_path`, `cv_trace`, `cv_communities`, `cv_flows`, `cv_rules`, `cv_guard`, `cv_diff_start`, `cv_diff_status`, `cv_service_links`, `cv_db_impact`, `cv_job_status`, `cv_jobs_list` | `/api/health`, `/api/stats`, `/api/projects`, `/api/search`, `/api/explain`, `/api/impact`, `/api/test-impact` |
| `security` | `cv_audit`, `cv_guard`, `cv_rules`, `cv_health`, `cv_stats`, `cv_list_projects`, `cv_find_project`, `cv_job_status`, `cv_jobs_list` | `/api/health`, `/api/stats`, `/api/audit`, `/api/guard`, `/api/rules` |
| `pm` | `cv_stats`, `cv_health`, `cv_list_projects`, `cv_find_project`, `cv_onboard`, `cv_changes`, `cv_wiki`, `cv_job_status`, `cv_jobs_list` | `/api/health`, `/api/stats`, `/api/projects`, `/api/onboard` |

Resources and prompts are not currently role-gated (additive surface).

---

## Module layout

```
cvector/
├── cvector-core/              # GraphEvent, NodeKey, ProjectContext, CvectorConfig, CvectorRole, GraphStore interface
├── cvector-neo4j/             # Neo4jClient, Ingestor, SchemaBootstrap, GraphQueries
├── cvector-embedded-kuzudb-server/  # Embedded KuzuDB store (default backend): EmbeddedKuzu, KuzuGraphStore, KuzuIngestor / KuzuBulkLoader, KuzuPostScan
├── cvector-parser-*/          # 25 language/format parsers (see Supported languages)
├── cvector-rules/             # Rule engine + builtin rules
├── cvector-watcher/           # Live file-watch + cron-driven re-scan
├── cvector-cli/               # Picocli commands wired as Spring beans
│   └── scan/                  # InProcessScanService / ScanRequest / ScanResult — the shared scan loop used by both the CLI and the MCP cv_scan_project tool
├── cvector-rest/              # Spring Web controllers for the dashboard
├── cvector-mcp/               # MCP server: tools, resources, prompts
│   ├── CvectorTools.java      # 33 @Tool methods + the async opt-in plumbing
│   └── JobRegistry.java       # In-memory async-job tracking for the cv_*_project tools + cv_job_status / cv_jobs_list
└── cvector-app/               # Spring Boot main + fat-jar assembly (entry point)
```

Build the fat jar:

```bash
mvn -DskipTests install
# → cvector-app/target/cvector.jar (~53 MB)
```

Run the tests:

```bash
mvn test                       # full suite
mvn -pl cvector-parser-java test   # one module
```

---

## Performance notes

The Java parser used to dominate scans (~48 s for a ~200-file project). Optimisations applied:

- **Symbol resolution lifted out of the parse loop.** `JavaSymbolSolver.resolve()` was the hottest path; every unresolved call threw a fully-stack-traced `RuntimeException`. The parser now emits `unresolved.<name>:<arity>` placeholder Method nodes during parse, and a post-scan Cypher pass rewires single-candidate matches by `(name, paramCount)`.
- **Ingestor batches** at 500 nodes/edges per Cypher `UNWIND`, single round-trip per label.
- **Cleanup pass** collapses 13 per-label `DETACH DELETE` round-trips into one count + one delete.
- **Parallel file walk** via `ForkJoinPool`. Ingestor is `synchronized` so concurrent `accept()` is safe.
- **Extension dispatch map** replaces O(N parsers) per-file scanning with O(1) lookup.
- **Virtual threads** enabled in MCP and dashboard modes (Spring property `spring.threads.virtual.enabled=true`). Active on JDK 21+, no-op otherwise.

### Measured numbers (this repo)

Hardware: Windows 11, JDK 25 runtime.

Project size: ~226 files, ~3.5 k distinct nodes, ~10 k distinct edges, 28 active parsers, ~126 cross-file calls resolved by the post-pass.

| Phase | Neo4j | Embedded Kuzu (bulk, initial scan) | Embedded Kuzu (re-scan, source unchanged) |
|---|---|---|---|
| Parser walk (parallel) | sequential ~3 s | **~1.7 s** | **~1.7 s** |
| Kuzu flush | n/a | **~0.7 s** (COPY) | **~5 s** (skip-MERGE + ~145 node + ~765 edge writes) |
| Scan loop (parser + flush) | 5.7–6.1 s | **~2.5 s** | **~6.5 s** |
| Wall clock incl. JVM start, Spring boot, post-pass | ~12 s | **~10 s** *(default)* / **~8 s** *(AOT + CDS)* | **~15 s** *(default)* / **~13 s** *(AOT + CDS)* |

Read-only commands (`status`, `explain`, `search`, `recent`, etc.) start at **~2 s** with AOT + CDS, down from ~3.7 s — Spring lazy initialization alone (`spring.main.lazy-initialization=true`) saves ~0.5 s by not constructing the 28 parser beans for commands that never call a parser; AOT-processed bean factories + a CDS shared-archive on top save another ~1 s.

Parser walk uses Java's parallel stream over `Files.walk(...)`. All 28 parsers were audited for thread safety; the two real fixes needed were `JavaParserAdapter`'s `JavaParser` field (now `ThreadLocal<JavaParser>` — JavaParser's library isn't thread-safe for concurrent `parse()` on a single instance) and `PomParserAdapter.bomCache` (HashMap → ConcurrentHashMap). The `Ingestor` / `KuzuIngestor` sinks already serialise via `synchronized accept(GraphEvent)`, so concurrent emits are safe; the speedup comes from parallel AST construction across worker threads.

`ScanCommand` automatically picks between bulk mode (when the project's Kuzu DB is empty — `KuzuBulkLoader` stages CSVs and runs `COPY <table> FROM '...'`) and merge mode (re-scan — `KuzuIngestor` pre-fetches existing `contentHash` per node + existing `(from, to)` per edge type, skips writes for unchanged rows, bulk-bumps `lastIngestedAt` on the skipped set). On a re-scan with substantive code changes, only changed rows actually MERGE, so the worst-case scan time scales with the diff size rather than the full graph size.

E2E coverage on the embedded backend: `cvector-app/src/test/java/io/doindev/cvector/EndToEndKuzuScanIT.java` exercises the full parser → bulk-load → KuzuGraphStore read pipeline against the same `sample-project` and `comment-audit` fixtures the Neo4j IT uses. No Testcontainers / Docker requirement; both tests complete in ~3 s combined.

### Optional: enable AOT + CDS for the JVM/Spring fast path

Two layered optimisations cut Spring Boot startup roughly in half:

1. **Spring AOT** is built in. The Maven build runs `spring-boot:process-aot` automatically (see `cvector-app/pom.xml`), so `BOOT-INF/classes/.../*__BeanDefinitions.class` ships inside the fat jar. Activate it at runtime with `-Dspring.aot.enabled=true`.
2. **Class Data Sharing**: generate a `cvector.jsa` shared archive once, then point every subsequent invocation at it.

```bash
# One-time, run after rebuilding the fat jar (writes cvector.jsa next to the jar):
java -Dspring.aot.enabled=true -XX:ArchiveClassesAtExit=cvector-app/target/cvector.jsa \
     -jar cvector-app/target/cvector.jar --embedded status

# Subsequent invocations:
java -Dspring.aot.enabled=true -XX:SharedArchiveFile=cvector-app/target/cvector.jsa \
     -jar cvector-app/target/cvector.jar --embedded <command> ...
```

The shared archive is platform-specific (regenerate per OS / JDK). The default invocation without these flags still works — AOT bean-definition classes are inert when `spring.aot.enabled` is unset.
| Embedded KuzuDB foundation test (open + schema + round-trip) | ~0.6 s for 4 tests |

The parse loop steady state is **~5.9 s for 218 files** — about 27 ms per file averaged across the parser mix.
