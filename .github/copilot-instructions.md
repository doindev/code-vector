# Copilot instructions for cvector

## What this project is

cvector is a polyglot code knowledge graph. Twenty-six parsers walk a project, emit `GraphEvent` records, and an ingestor batches them into a graph (`Class → Method → CALLS → Method`, REST endpoints, SQL tables, config keys, container images, IaC resources, GraphQL types, …). Three surfaces consume the graph: a Picocli CLI, a Spring Boot REST API + Angular dashboard, and an MCP server for AI assistants.

Backends are pluggable. The **default is embedded KuzuDB** (in-process, MIT-licensed, native lib bundled in the fat-jar). Optional Neo4j (`backend: "remote"`) or Docker-managed Neo4j (`backend: "docker"`). Every read path goes through `cvector-core/store/GraphStore`; parser code and rule code are backend-agnostic.

## Stack (current, all on the kuzu branch)

| Component | Version | Notes |
|---|---|---|
| Java compile target | 17 | Some Antlr-generated base classes assume 17; don't bump without coordinated work. |
| Java runtime | 21+ recommended | Virtual threads on for **dashboard mode only** (see Spring conventions). |
| Spring Boot | **4.1.0-RC1** | Pre-release. Spring Framework 7. Drives most "where did this package go?" surprises — see Spring Boot 4 gotchas. |
| Spring AI | **2.0.0-M6** | Pre-release milestone. Adds Streamable HTTP MCP transport. |
| MCP SDK | **2.0.0-M2** | `io.modelcontextprotocol.sdk:mcp-bom` pinned in the root pom *before* `spring-ai-bom` so it wins. |
| KuzuDB | 0.11.3 | Native lib auto-extracted from the fat-jar. |
| Maven | 3.9+ | ANTLR4 maven plugin generates parsers. |

## Architecture map

```
cvector-core/                     GraphEvent, NodeKey, Parser interface, CvectorConfig, CvectorRole, GraphStore
cvector-neo4j/                    Neo4j Bolt driver wrapper, Ingestor, SchemaBootstrap, GraphQueries
cvector-embedded-kuzudb-server/   Default backend. EmbeddedKuzu, KuzuGraphStore, KuzuIngestor / KuzuBulkLoader, KuzuPostScan
cvector-parser-*/                 26 language/format parsers (Java + 25 ANTLR / native lib parsers)
cvector-rules/                    Rule engine + builtins (god-file, god-class, long-method, deep-inheritance, dead-code)
cvector-watcher/                  Live file-watch + cron-driven re-scan
cvector-cli/                      Picocli commands wired as Spring beans (`commands/...`); InProcessScanService is the shared scan loop
cvector-rest/                     Spring Web controllers (`/api/*`), CvectorErrorController, JsonCache
cvector-mcp/                      MCP server: CvectorTools (**33** @Tool methods), CvectorResources (**9**), CvectorPrompts (**9**), JobRegistry, McpRequestLogFilter
cvector-dashboard/                Angular 17 SPA + Spring MVC handler (DashboardConfiguration). Optional via `-Pdashboard-ui`.
cvector-app/                      Spring Boot main (`CvectorApplication`) + fat-jar assembly. The single entry point.
```

The fat jar at `cvector-app/target/cvector.jar` selects mode from `args[0]`:

- `dashboard` → web (servlet, profile `mcp` co-active so HTTP MCP runs alongside the SPA)
- `serve` → MCP stdio (no web)
- anything else → CLI command

## Build & test

```bash
mvn -DskipTests install                                      # fat jar (~10-15 s warm cache)
mvn -pl cvector-app -am -Pdashboard-ui package               # + Angular dashboard
mvn -pl cvector-app -am -Pdashboard-ui,dist -DskipTests install  # + jpackage native image (cvector.exe / .app)
mvn test                                                     # full suite (Testcontainers spins Neo4j for IT)
mvn -pl cvector-parser-java test                             # one parser module
```

CI / smoke validation:

```bash
java -jar cvector-app/target/cvector.jar status              # quick sanity check on the active project
java -jar cvector-app/target/cvector.jar rules               # 0 violations on this repo
```

## MCP transports

`cvector dashboard` co-hosts an HTTP MCP transport in the same Tomcat. Which transport is wired is chosen by `mcp.transport` in `settings.json`:

| `mcp.transport` | Protocol | Endpoint | Notes |
|---|---|---|---|
| `http` *(default)* | MCP 2025-03-26 "Streamable HTTP" | `POST /mcp`, session via `Mcp-Session-Id` header | Modern clients: Eclipse Copilot, MCP Inspector v2, newer Claude. |
| `sse` | MCP 2024-11-05 "HTTP+SSE" | `GET /sse` → endpoint event → `POST /mcp/message?sessionId=…` | Legacy clients: original Claude Desktop, MCP Inspector v1. |
| `stdio` | JSON-RPC over stdin/stdout | n/a (subprocess) | Skips co-hosting entirely — only `cvector serve` exposes the MCP server. |
| `streamable` | alias | — | Legacy value briefly written during the Spring AI 2.0 transition, canonicalised back to `http` on read/write. |

`CvectorApplication.coHostMcp` translates this into `spring.ai.mcp.server.protocol=STREAMABLE` (or `SSE`) + the matching endpoint property at JVM-system-property precedence (slot 6), so it beats the `application-mcp.properties` defaults (slot 9). Spring AI 2.0's auto-config wires one of `WebMvcStreamableServerTransportProvider` / `WebMvcSseServerTransportProvider` based on that property; the others stay inert. `cvector serve` always uses stdio regardless of this setting.

## settings.json essentials

Per-workspace config at `.cvector/settings.json` (project-local) or `~/.cvector/settings.json` (home fallback, auto-created on first run). Schema fields most likely to matter when writing/reading config:

- `activeProject` (string), `projects` (map name → `{ projectId, name, rootPath, isolated? }`)
- `backend`: `embedded` *(default)* / `remote` / `docker`
- `rest.port` (2969), `rest.host` (127.0.0.1), `rest.server.*` (free-form Boot `server.*` overrides)
- `mcp.transport` (see table above), `mcp.url` (informational; path component drives the endpoint property)
- `kuzu.bufferSizeMb` (optional; auto-sizes to 25% RAM clamped to 512 MB – 4 GB)
- `rules.*` and per-project `projects.<name>.rules.*` — layered (built-in → rules.yml → workspace → per-project)

Old installs may carry `.cvector/project.json` — still readable, but every write goes to `settings.json`.

## Conventions worth following

### Parser conventions

- **Parsers don't do symbol resolution.** Emit `unresolved.<name>:<arity>` placeholder `Method` nodes for cross-file calls; `ScanCommand.resolveUnresolvedCalls` post-pass rewires single-candidate matches in Cypher. Don't reintroduce `JavaSymbolSolver.resolve()` on the hot path — ~10× scan-time regression via reflection + stack-trace generation on every unresolved call.
- **ANTLR is preferred** for new languages. Grammars live under `src/main/antlr4/io/doindev/cvector/parser/<lang>/internal/`; base classes under `src/main/java/.../internal/` with a `package` declaration added (grammars-v4 ships them without packages).
- **Comments-in-comments suppression** is non-negotiable: every parser has tests proving it ignores declarations inside `//`, `/* */`, docstrings, and string literals.
- **`Parser.accepts(Path)`** can be overridden when the extension alone isn't enough (Dockerfile, `.d.ts` skip-list).
- Vendored grammar code lives under `*/internal/` paths — `rules.yml` excludes those by default.

### Graph conventions

- Every node carries `projectId`, `label` (the Neo4j label), and `fqName` — composed into a SHA-256 `id` by `NodeKey.id()`.
- Methods carry `paramCount` so the post-pass resolver can disambiguate by arity.
- Edges are typed: `CONTAINS`, `CALLS`, `EXTENDS`, `IMPLEMENTS`, `IMPORTS`, `EXPOSES`, `HANDLES`, `READS_TABLE`, `WRITES_TABLE`, `DEPENDS_ON`, `USES`, `DECLARES`, `READS_CONFIG`.
- New node types: pick a descriptive label, set `name` + `fqName`, follow the existing per-parser pattern. The `Ingestor` will batch them transparently.
- Always parameterise Cypher with `$pid` for projectId; pull from `ProjectContext.projectId()` or `McpActiveProject.projectId()`. **Never** hardcode.

### Rules conventions

- Built-in rules live in `cvector-rules/src/main/java/io/doindev/cvector/rules/builtin/`.
- Each rule implements `Rule.evaluate(projectId, GraphQueries, RulesConfig)` and returns `List<Violation>`.
- Rules support **path exclusion** via `RulesConfig.isPathExcluded(path)`. New rules that query Methods/Classes should pull the owning File's `path` into their Cypher and call `isPathExcluded` before adding to the violation list.

### MCP conventions

- Tools live in `CvectorTools.java`, annotated `@Tool(name = "cv_*", description = "...")` with `@ToolParam` for each argument. **33 currently.**
- Resources live in `CvectorResources.getResources()`, returned as `List<SyncResourceSpecification>`. URIs follow `cvector://<name>`. MIME type is always `application/json`. **9 currently.**
- Prompts live in `CvectorPrompts.getPrompts()` and return USER messages that *name the cv_\* tools and `cvector://` resources the AI should call*. They're conversation starters, not implementations. **9 currently.**
- Role gating (`CvectorRole.allowsTool`) wraps the `ToolCallbackProvider`. Resources and prompts are not yet role-gated.
- `McpSchema.Resource` in MCP SDK 2.0 is an **8-arg record**: `(uri, name, title, description, mimeType, size, annotations, _meta)`. Most callers pass `null` for `title`, `size`, `annotations`, and `_meta`.
- Long-running tools (`cv_scan_project`, `cv_onboard_project`, `cv_purge_project`, `cv_purge_orphans`, `cv_remove_project`) accept `async: true` and return a jobId. Pair with `cv_job_status` / `cv_jobs_list`. `JobRegistry` is in-memory, 15-min watchdog timeout, terminal jobs retained ~1 h.

### Spring / Boot conventions

- The Spring Boot main (`CvectorApplication`) inspects `args[0]` to pick `dashboard` (servlet web + `mcp` profile), `serve` (MCP stdio, profile `mcp`, `WebApplicationType.NONE`), or `none` (CLI).
- All beans are configured manually in `*Configuration` classes — no `@ComponentScan` magic on parsers. Parsers register through `CliConfiguration.@Bean Parser ...()` methods.
- **Virtual threads** are enabled in **dashboard mode only** (`spring.threads.virtual.enabled=true` set in `CvectorApplication` for `coHostMcp`). They are **disabled** on the `mcp` profile (`application-mcp.properties` pins `false`) because Spring AI's `McpServerSession.handle(...).block()` synchronously blocks during SSE writes; on virtual threads a large response pin the carrier thread and starves the rest. See the comment block in `application-mcp.properties` for the full reasoning.
- `spring.main.lazy-initialization=true` is on by default in `application.properties`. Only `McpSyncServer` is pinned eager via `McpSyncServerEagerInitializer` in `cvector-mcp/.../McpServerConfig.java` — its constructor wires Spring AI's transport provider's `sessionFactory`, which would otherwise stay null and NPE on the first request.
- **Jackson 2 `ObjectMapper`** must be re-provided manually: Spring Boot 4 dropped its auto-config in favour of the new Jackson 3 `tools.jackson.databind.json.JsonMapper`. `CvectorApplication.objectMapper()` exposes a `@Bean @ConditionalOnMissingBean ObjectMapper` so the 13 Jackson-2 call sites (JsonCache, SettingsController, AuditService, CvectorTools, …) keep working without a migration.
- `CvectorErrorController` lives in `cvector-rest` and replaces Spring's Whitelabel page. Two `@RequestMapping("/error")` handlers: `produces = text/html` for browsers, no-`produces` fallback for everything else (curl, MCP clients, scripts).

### Embedded KuzuDB conventions

- `--embedded` (Picocli, `scope = INHERIT`) and `CVECTOR_EMBEDDED=true` set the `cvector.embedded` system property. The `cvector embedded` subcommand tree (`init`, `info`, `query`, `wipe`) exercises the Kuzu store directly.
- Default location: `~/.cvector/kuzu-data/<projectId>/graph.kuzu/` (a directory). Shared-DB mode (default for non-isolated projects) puts every project under `~/.cvector/kuzu-data/graph.kuzu/` partitioned by `projectId`.
- Schema is **declared upfront**: one polymorphic `Node` table (discriminated by a `label` property) plus 14 typed `REL` tables. New node labels are first-class properties, not new tables. New edge types must be added to `KuzuSchemaBootstrap.EDGE_TYPES`.
- Kuzu's Cypher dialect is a subset of Neo4j's. Specifically: **`SET n += $props` is NOT supported** — properties must be enumerated. `MERGE` semantics are more limited. Use `MATCH ... CREATE` idioms; supply an optional `cypherKuzu` body on custom rules when the Neo4j Cypher uses features Kuzu doesn't have.
- Connection is single-threaded; `EmbeddedKuzu` serialises every native call through one mutex.
- Kuzu native lib auto-extracts from the jar at first load — triggers the JDK-25 `System.load` restricted-method warning, which is harmless.
- `cv_scan_project` runs **in-process** in the dashboard's JVM, reusing the live Kuzu handle. Avoids file-lock collisions a subprocess scan would hit.

### Spring Boot 4 gotchas already absorbed (don't re-hit)

- `org.springframework.boot.web.servlet.error.ErrorController` → `org.springframework.boot.webmvc.error.ErrorController`
- `org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory` → `org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory`
- No auto-configured Jackson 2 `ObjectMapper` (see above).
- Spring AI 2.0's autoconfigs use `@Qualifier("mcpServerJsonMapper") JsonMapper` (Jackson 3). Our own code keeps Jackson 2; the two coexist on the classpath.

### Performance reminders

- Java parsing was the historical bottleneck. Current scan is ~5.9 s warm for 218 files / 14 k nodes / 14 k edges. Don't reintroduce per-call `.resolve()` exception throwing.
- `Ingestor` batches at 500 nodes/edges per `UNWIND`. Don't issue per-node `MERGE` writes.
- `cleanupStale` is one count + one delete across all label types, not 13 round-trips. Keep it that way.
- Scan parallelism uses `ForkJoinPool.parallelStream()`. Any new Parser code must be thread-safe (fresh lexer/parser per `parse()` is the easy path; sharing state across threads is the trap).
- The `cvector-mcp` `McpSyncServerEagerInitializer` is load-bearing — without it, lazy-init leaves `McpSyncServer` uncreated and the first SSE/Streamable request NPEs deep inside Spring AI.

## Coding style

- Java 17 features welcome: records, sealed interfaces, pattern matching for `instanceof`, switch expressions, text blocks.
- **No comments stating *what* the code does** — names should do that. Comments are reserved for *why*: non-obvious invariants, references to a previous bug, performance trade-offs.
- Prefer one-line javadoc for public methods; use multi-line only when the *why* genuinely requires it.
- Don't introduce abstractions for hypothetical future code. Three similar lines beat a premature `AbstractFooFactory`.
- Don't add error handling for impossible cases. Validate at boundaries (file I/O, network), trust internal invariants.

## Testing requirements

- Every parser module has a `*ParserAdapterTest` with at minimum: a happy-path extraction test, a comments-suppression test, and (where applicable) a string-literal suppression test.
- Integration tests live in `cvector-app/src/test/java/io/doindev/cvector/`. `EndToEndScanIT` uses Testcontainers Neo4j; `EndToEndKuzuScanIT` exercises the embedded backend in-process (~3 s, no Docker).
- Use AssertJ. JUnit 5. `@TempDir` for filesystem tests.
- Don't mock the Neo4j driver in IT tests — the whole point is exercising real graph queries.
- For MCP transport changes, the smoke-test trio in `.test/` (`test_stdio.py`, `test_streamable.py`, `test_sse.py`) drives the full `initialize` → `notifications/initialized` → `tools/list` handshake against a freshly-built jar.

## Common tasks

| Task | Where to start |
|---|---|
| Add a new language | Copy a grammar from `grammars-v4` cache into `cvector-parser-<lang>/src/main/antlr4/.../internal/`, add base class with `package` declaration, write `*ParserAdapter` implementing `Parser`, register `@Bean` in `CliConfiguration`. |
| Add a CLI command | New class in `cvector-cli/src/main/java/.../commands/` annotated `@Component @Command(name = ...)` implementing `Callable<Integer>`. Picocli + Spring autowire pick it up. |
| Add an MCP tool | New `@Tool` method on `CvectorTools`. Update the tool count in this file. Add the tool to `CvectorRole.allowsTool` if it should be role-gated. |
| Add an MCP resource | New `out.add(resource(...))` call in `CvectorResources.getResources()`. URI under `cvector://`. MIME `application/json`. |
| Add a rule | New class in `cvector-rules/.../builtin/` implementing `Rule`, register in the `RulesEngine` builtin list. Remember `isPathExcluded`. |
| Add a Cypher query helper | `GraphStore` interface in `cvector-core/store`. Keep it parameterized — never concatenate user input into Cypher. Implement on both `KuzuGraphStore` (Kuzu dialect) and `Neo4jGraphStore`. |
| Switch active MCP transport | `cvector mcp update --transport <http\|sse\|stdio>` (writes `settings.json`), then restart the dashboard. |

## Things to avoid

- **Don't add JDK / library calls** as graph nodes during parse (e.g., `java.util.Map.put`). Those used to come from JavaSymbolSolver; they're noise. Cross-file project calls are what matters.
- **Don't write to Neo4j from inside a `client.read(...)` callback.** Read returns a list; do the write as a separate `client.write(...)` call.
- **Don't hardcode the projectId.** Always parameterize Cypher with `$pid`.
- **Don't enable virtual threads on the `mcp` profile.** The Spring AI server-session `.block()` will pin the carrier thread during SSE writes and starve siblings — see `application-mcp.properties` for the incident notes.
- **Don't use `git rebase -i`, `git add -i`,** or other interactive git commands — they hang in non-TTY contexts.
- **Don't bump `<maven.compiler.release>` past 17** without a coordinated effort. Some grammars-v4 base classes assume baseline Java 17.
- **Don't override `spring.ai.mcp.server.protocol`** outside `CvectorApplication.coHostMcp` — that's the one place that knows the right endpoint property to set alongside it.
- **Don't bypass `GraphStore`** to write directly against `Neo4jClient` or `EmbeddedKuzu` from rules / MCP / REST code. Backend-agnostic code goes through the interface.
