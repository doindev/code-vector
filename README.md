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
   - [Tools](#mcp-tools)
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
| **Java** | 17 (compile target) | Runs on JDK 21+ to enable virtual threads in MCP/dashboard. |
| **Maven** | 3.9+ | The build uses ANTLR4 maven plugin for grammar generation. |
| **OS** | Windows / Linux / macOS | Build is OS-agnostic. |
| **Neo4j** | *(optional)* 5.x | Only required if you opt out of the embedded KuzuDB default — see [Backends](#backends--settingsjson). The Kuzu native library is bundled in the fat-jar for all three platforms. |
| **Docker** | *(optional)* | Only needed for the `docker` backend mode where cvector manages a Neo4j container for you. |

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

---

## Backends & settings.json

cvector keeps all per-workspace state in a single file: `.cvector/settings.json` (created by `cvector init`, discovered by walking up from the current working directory). The file has seven top-level fields, only two of which are required after `init` (`activeProject`, `projects`); every other section falls back to a sensible default if absent.

> **Legacy filename.** Older installs used `.cvector/project.json`. The loader still reads that name as a fallback so existing workspaces keep working, but every write goes to `settings.json`. If you have both, `settings.json` wins.

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
    "host": "127.0.0.1"
  },
  "mcp": {
    "url": "http://127.0.0.1:2969/mcp",
    "transport": "http"
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
| `mcp.url` | string | `"http://127.0.0.1:2969/mcp"` | Informational URL clients can use to reach the MCP server. The server itself binds at `rest.host:rest.port`. |
| `mcp.transport` | string | `"http"` | One of `"http"` / `"sse"` / `"stdio"`. Drives how MCP clients connect. |
| `docker.image` | string | `"neo4j"` | Docker image name (without tag). |
| `docker.containerName` | string | `"cvector-neo4j"` | Compose service / container name. |
| `docker.neo4jVersion` | string | `"5"` | Image tag — used as `{image}:{neo4jVersion}`. |
| `docker.boltPort` | integer | `7687` | Host port the container's Bolt listener is mapped to. |
| `docker.httpPort` | integer | `7474` | Host port for Neo4j HTTP / Browser. |

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
| `cvector mcp update --transport <http\|sse\|stdio> <URL>` | Update `mcp.transport` and `mcp.url`. |
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

### `.cvector/rules.yml`

Optional. Generated by `cvector rules --init`. Sets thresholds, disables built-in rules, and defines custom Cypher rules.

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
| `mcp` | `cvector serve` | Stdio JSON-RPC server, MCP capabilities enabled, virtual threads on. |
| (web mode) | `cvector dashboard` | Embedded servlet container on `rest.host:rest.port` (default `127.0.0.1:2969`), virtual threads on. |

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
| `dashboard` | Start the REST API on port 2969. |
| `serve` | Start the MCP server over stdio (for IDE/AI integration). |

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

## MCP server

Run with `cvector serve` (stdio JSON-RPC). Default capabilities: **tools**, **resources**, **prompts**.

### MCP tools

| Tool | Description | Parameters |
|---|---|---|
| `cv_stats` | Node + edge counts. | — |
| `cv_health` | Connectivity + graph size + last scan commit. | — |
| `cv_projects` | Active project context. | — |
| `cv_search` | Substring node search; supports `*` wildcards. | `query` (req), `limit` (def 25), `label` (opt) |
| `cv_explain` | Symbol context: type, file:line, callers, callees. | `symbol` (req) |
| `cv_impact` | Downstream impact via CALLS/REFERENCES. | `symbol` (req), `depth` (def 3) |
| `cv_test_impact` | Tests that transitively reach a symbol. | `symbol` (req), `depth` (def 5) |
| `cv_context` | Members + references for a class/method. | `symbol` (req) |
| `cv_rename` | Rename impact: callers, refs, importing files. | `symbol` (req) |
| `cv_changes` | Recently-ingested nodes. | `since` (def `24h`), `limit` (def 50) |
| `cv_onboard` | Full codebase briefing. | — |
| `cv_rules` | Rules engine results. | — |
| `cv_communities` | Cluster the call graph. | `algorithm` (leiden\|louvain\|connected-components), `minSize` (def 3), `limit` (def 10) |
| `cv_flows` | Trace from REST/main/test entry points. | `kind` (rest\|main\|test\|all), `maxDepth` (def 3), `limit` (def 25) |
| `cv_service_links` | Cross-service deps via HTTP, queues, exposed endpoints. | — |
| `cv_audit` | OSV vulnerabilities × graph blast radius. | — |

### MCP resources

Browsable read-only JSON snapshots — no parameters, no composition needed.

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

### MCP prompts

Pre-built conversation starters that name the tools/resources the assistant should call.

| Prompt | Arguments | Purpose |
|---|---|---|
| `cvector-onboard` | — | Architecture brief for a new team member. |
| `cvector-review-change` | `symbol` (req) | Impact analysis pre-refactor. |
| `cvector-health-check` | — | Prioritised action items. |
| `cvector-explain-module` | `path` (req) | Module deep-dive. |
| `cvector-migration-plan` | `from` (req), `to` (req), `scope` (opt) | Phased migration plan with risk register. |
| `cvector-infrastructure` | — | Infrastructure surface audit. |

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

### Status

- **Write path: ready.** `cvector --embedded scan` runs the full parser pipeline against KuzuDB instead of Neo4j. `EmbeddedKuzu` opens a database in-process, `KuzuSchemaBootstrap` declares cvector's schema (one polymorphic `Node` table + 14 typed `REL` tables), and `KuzuIngestor` issues per-row MERGE/SET writes batched in 500-row transactions. 12 unit tests pass.
- **Post-passes: ported.** `KuzuPostScan.resolveUnresolvedCalls`, `resolveDeferredHandlers`, and `cleanupStale` all run on embedded scans. The Neo4j versions leaned on `EXISTS { ... }` subqueries, `properties(r)` map projections, and `SET r += oldProps`; the Kuzu ports replace those with OPTIONAL MATCH + IS NULL, explicit per-column reads, and explicit per-column SET.
- **Read path: most surfaces migrated.** The `GraphStore` interface in `cvector-core/store` covers {ping, displayUri, bootstrapSchema, schemaReady, nodeCounts, edgeCounts, findSymbol, searchByName, callers, callees, impactDownstream, fileOf, projectMeta, contains, referencingNodes, importingFiles, recentlyChanged, mavenDependencies, fileInventory, healthRollup, guardSummary, infrastructureSummary, traceFlows, serviceLinks, onboardSummary, projectsList, methodCallGraph, testReach, backend, supportsRawCypher}. `Neo4jGraphStore` and `KuzuGraphStore` both implement it. The MCP and REST modules wire `GraphStore` via Spring config that branches on `cvector.embedded`. Kuzu queries skip the `projectId` predicate (each Kuzu DB is per-project, so the filter is a no-op) and use Kuzu's `regexp_matches()` / `list_slice()` / variable-length `*1..N` traversal in place of Neo4j's `=~` / `collect()[0..N]`. The `testReach` impl is Java BFS over a fetched reverse-adjacency list since Kuzu has no `shortestPath()`.
- **CLI commands on `GraphStore`:** all 22 — `diff` included. On `--embedded`, `diff` spins up two ephemeral Kuzu DBs (one per SHA, each bulk-loaded via `COPY FROM`) and computes added/removed/changed sets in Java rather than via `NOT EXISTS` Cypher. On Neo4j it retains the original cross-snapshot model. `--keep` prints the temp Kuzu paths instead of project ids; `--include-calls` emits added/removed CALLS edges via the same set-difference pattern.
- **MCP tools on `GraphStore`:** all 16. `cv_rules` runs the full `RulesEngine` (god-file, god-class, long-method, deep-inheritance, dead-code + custom Cypher rules from `rules.yml`) on Kuzu via per-rule dialect-aware Cypher pairs.
- **MCP resources on `GraphStore`:** all 8 — `stats`, `schema`, `files`, `health`, `infrastructure`, `guard`, `projects`, `onboard`.
- **REST controllers:** both `StatsController` and `QueryController` (including `/test-impact`) fully on `GraphStore`.
- **`cvector-rules` and `cvector-watcher` are backend-agnostic.** Both modules dropped their `cvector-neo4j` dependency. `Rule.evaluate` and `CvectorWatcher` take `GraphStore` directly. Custom rules in `.cvector/rules.yml` can supply an optional `cypherKuzu` field for per-backend bodies; otherwise `cypher` is used for both.
- **Write paths.** `scan`, `scan:incremental`, `watch` (live + cron), and the watcher itself all go through `GraphStore.openIngestor()` → `GraphIngestor` (implemented by `Ingestor` for Neo4j and `KuzuIngestor` for Kuzu). File-removal uses `GraphStore.deleteFileSubtree(projectId, path)` — variable-length `CONTAINS*` DETACH DELETE on Neo4j, Java BFS over CONTAINS edges on Kuzu.
- **`testReach` is native on Kuzu.** Previously the Kuzu impl fetched every CALLS+REFERENCES edge into Java memory and BFS'd backwards from the target. Now uses Kuzu's variable-length union-edge traversal — `MATCH p = (t)-[:CALLS|REFERENCES*1..N]->(target)` + `min(length(p))` per test — so query cost scales with reachable subgraph instead of total edge count.
- **`EmbeddedKuzu` filters bound parameters** to only those the prepared statement references. Kuzu rejects `execute` calls where the param map contains extra keys (Neo4j silently ignores). The filter is a one-time regex scan per unique Cypher; the prepared-statement cache keeps the resolved references hot, so callers can pass uniform `{pid, t, ...}` maps regardless of which placeholders each dialect-specific query actually uses.
- **Schema completeness.** The Kuzu Node table declares 65+ properties parsers emit. `KuzuSchemaBootstrap` issues `ALTER TABLE Node ADD ...` per missing column on startup, so existing on-disk databases lift to the current schema without a wipe.
- **Performance.** Two ingest paths, picked automatically by `ScanCommand`:
  - **Bulk mode** (empty DB): `KuzuBulkLoader` buffers events in memory, stages typed CSVs, and runs `COPY Node FROM '...'` + one `COPY <REL_TYPE> FROM '...'` per populated edge table. Flush of ~3.5 k nodes + ~10 k edges takes **~700 ms** on this repo. Total scan wall-clock **~12 s** (most of it JVM/Spring startup + the post-pass rewire).
  - **Merge mode** (re-scan with existing data): `KuzuIngestor` carries a `contentHash` SHA-256 (excluding `lastIngestedAt`) on every row. At flush time it pre-fetches existing `(id, contentHash)` for all buffered nodes and existing `(from, to)` pairs per REL type, then skips MERGEs for unchanged rows. The ingestor tracks every node id it saw (written + skipped); `KuzuPostScan.cleanupStale` consumes that set and deletes managed-label rows whose id isn't in it, so `lastIngestedAt` doesn't need a per-row bulk-bump and `cv_changes` now reflects only rows whose content actually changed. On a no-source-change re-scan of this repo (3625 nodes / 11058 edges) **3481 nodes + 10294 edges get skipped, ~145 nodes + ~765 edges actually MERGE** (mostly placeholder churn the post-pass introduces). Re-scan wall-clock dropped from ~70 s to **~17 s**.
  - Both paths share the same in-memory dedup (14 k raw events → 3.5 k distinct nodes + 10 k distinct edges), a shape-keyed prepared-statement cache, and a regex-cached parameter filter. Read paths are sub-100 ms across the board.

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
| `architect` | `cv_stats`, `cv_projects`, `cv_search`, `cv_context`, `cv_explain`, `cv_impact`, `cv_test_impact`, `cv_communities`, `cv_flows`, `cv_rules`, `cv_guard`, `cv_diff`, `cv_service_links` | `/api/health`, `/api/stats`, `/api/projects`, `/api/search`, `/api/explain`, `/api/impact`, `/api/test-impact` |
| `security` | `cv_audit`, `cv_guard`, `cv_rules`, `cv_health`, `cv_stats` | `/api/health`, `/api/stats`, `/api/audit`, `/api/guard`, `/api/rules` |
| `pm` | `cv_stats`, `cv_projects`, `cv_onboard`, `cv_changes`, `cv_health`, `cv_wiki` | `/api/health`, `/api/stats`, `/api/projects`, `/api/onboard` |

Resources and prompts are not currently role-gated (additive surface).

---

## Module layout

```
cvector/
├── cvector-core/              # GraphEvent, NodeKey, ProjectContext, CvectorConfig, CvectorRole
├── cvector-neo4j/             # Neo4jClient, Ingestor, SchemaBootstrap, GraphQueries
├── cvector-embedded-kuzudb-server/  # Optional embedded KuzuDB store (in-process graph DB)
├── cvector-parser-*/          # 25 language/format parsers (see Supported languages)
├── cvector-rules/             # Rule engine + builtin rules
├── cvector-watcher/           # Live file-watch + cron-driven re-scan
├── cvector-cli/               # Picocli commands wired as Spring beans
├── cvector-rest/              # Spring Web controllers for the dashboard
├── cvector-mcp/               # MCP server: tools, resources, prompts
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
