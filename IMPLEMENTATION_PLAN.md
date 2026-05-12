# cvector — Implementation Plan

## Context
Greenfield Java project (`io.doindev:cwc-code-vector`) at `C:\Users\timhj\eclipse-workspace\code-vector` — starting from an empty Maven skeleton. `cvector-ideas.txt` describes the target system: a Neo4j-backed **code knowledge graph** that thoroughly parses a project, models every file/class/method/table/endpoint/config-key as a node and every call/import/read/write/extend/implement as an edge, and answers structural questions like *"if I change this table schema, what breaks?"* with file paths and line numbers. The system must support multiple projects in one Neo4j instance (via `projectId` isolation) and expose its capabilities through three surfaces: a CLI of ~40 commands, a 14-endpoint REST API, and an MCP server (21 tools / 9 resources / 6 prompts). It must watch directories live or on a cron, evaluate architectural rules, and gate CI quality.

This plan is the result of an interactive option/pros/cons review with the project owner. Each decision is recorded below with rationale, followed by the full schema, module layout, phase-by-phase build sequence, and verification steps.

---

## Locked Decisions (from interactive planning)

| Area | Choice | Rationale |
|------|--------|-----------|
| JDK | 17+ (LTS) | Records, sealed interfaces, pattern matching; Spring Boot 3 minimum |
| Phasing | Vertical-slice MVP | Real graph data drives schema decisions; de-risks the core loop fast |
| Framework | Spring Boot 3 | Mature ecosystem, Spring Data Neo4j optional, `@Scheduled`, WebMVC, Actuator, Spring AI MCP starter |
| Parsing strategy | Hybrid per-language | Best tool per language; semantic precision where it matters (Java) |
| Project layout | Multi-module Maven | Enforces boundaries; parsers as swappable modules |
| Neo4j topology | Bolt to user/Docker server | Standard pattern; Neo4j Browser included; clean licensing |
| Neo4j access | Raw Neo4j Java Driver + Java records | This domain is 90% custom graph traversals; OGM would add friction |
| MCP server | Spring AI MCP starter | Tools-as-beans; native Spring integration |
| Distribution | Fat JAR (Spring Boot uber-jar) + AppCDS + shell wrappers | One artifact; AppCDS halves startup; native-image deferred to v2 |
| Phase 1 scope | Standard — 10 commands, Java parser only, CLI only | End-to-end demo without over-scoping the slice |
| REST API | Phase 2 | Built after parsing breadth lands; endpoints exposed against real data |
| MCP server | Phase 3 | Reuses REST query layer; clean separation |
| Watcher + rules + guard | Phase 4 | Needs solid incremental ingest first |

## Judgment Calls (committed; flag any to be changed)

| Area | Choice | Why |
|------|--------|-----|
| CLI library | Picocli + `picocli-spring-boot-starter` | De facto Java CLI standard; one-shot commands (vs Spring Shell's REPL) |
| REST stack | Spring WebMVC | Low concurrency, no streaming need; reactive is unnecessary complexity |
| File watcher | `io.methvin:directory-watcher` | Best cross-platform Java watcher (FSEvents/ReadDirectoryChangesW); recursive |
| Build tool | Keep Maven | Already configured; Spring/Eclipse-friendly; switching cost > Gradle benefit here |
| Testing | JUnit 5 + Testcontainers (`org.testcontainers:neo4j`) + AssertJ + Mockito | Real Neo4j in tests; idiomatic Spring Boot test stack |
| Config format | JSON (`.cvector/project.json`) | Matches ideas file spec |
| Logging | SLF4J + Logback (Spring default) | Conventional |
| Node ID strategy | Deterministic SHA-256 of `(projectId, label, fqName)`, truncated to 16 hex | Makes `MERGE` idempotent; re-running scan never duplicates |
| Multi-project isolation | `projectId` property on every node + edge endpoint pair; every Cypher injects `WHERE n.projectId = $projectId` | Matches ideas file; simple, query-efficient |
| Type system | Java records for DTOs/events; sealed interfaces for `GraphEvent` variants | Modern Java idioms |

---

## Multi-Module Maven Layout

```
cvector-parent/                         (packaging: pom)
├── pom.xml                             (Spring Boot BOM 3.x, Java 17, child modules)
├── cvector-core/
│   └── domain: Node/Edge records, NodeKey, GraphEvent sealed interface, Parser SPI
├── cvector-neo4j/
│   └── Neo4jClient wrapper, SchemaBootstrap, Ingestor (batched MERGE), node repositories
├── cvector-parser-java/
│   └── JavaParserAdapter (uses JavaParser SymbolSolver), emits GraphEvents
├── cvector-parser-sql/                 (Phase 2: JSqlParser)
├── cvector-parser-config/              (Phase 2: Jackson + SnakeYAML)
├── cvector-parser-ts/                  (Phase 5: Tree-sitter or ANTLR)
├── cvector-watcher/                    (Phase 4: methvin/directory-watcher + cron)
├── cvector-rules/                      (Phase 4: architecture rules engine)
├── cvector-cli/                        (Picocli commands, all phases)
├── cvector-rest/                       (Phase 2: Spring WebMVC controllers)
├── cvector-mcp/                        (Phase 3: Spring AI MCP starter)
└── cvector-app/                        (Spring Boot main; fat-jar assembly target)
```

---

## Graph Schema

### Node labels and key properties (Phase 1 in **bold**)
- **`Project`** — `id`, `name`, `rootPath`, `lastScannedAt`, `lastScanCommit`
- **`File`** — `id`, `projectId`, `path`, `language`, `sha256`, `lineCount`, `lastModified`
- **`Module`** — `id`, `projectId`, `name` (Java package or Maven module)
- **`Class`** — `id`, `projectId`, `name`, `fqName`, `kind` (class/interface/enum/record/annotation), `startLine`, `endLine`, `fileId`
- **`Method`** — `id`, `projectId`, `name`, `fqName`, `signature`, `returnType`, `params`, `startLine`, `endLine`, `classId`, `fileId`, `isStatic`, `visibility`
- **`Field`** — `id`, `projectId`, `name`, `fqName`, `type`, `startLine`, `classId`, `fileId`
- `Table` — Phase 2 (SQL parser)
- `Column` — Phase 2
- `ApiEndpoint` — Phase 2 (Spring annotations: `@RequestMapping`, `@GetMapping`, etc.)
- `EnvVar` — Phase 2
- `ConfigKey` — Phase 2 (YAML/JSON properties)
- `MavenDependency` — Phase 2
- `Queue` / `Topic` / `Schedule` — Phase 4 (service-links + cron)

### Relationship types (Phase 1 in **bold**)
- **`CONTAINS`** (Project→File, File→Class, Class→Method, Class→Field, Class→Class for inner)
- **`CALLS`** (Method→Method) — props: `confidence` (1.0 if symbol-resolved, <1 if heuristic), `callSiteLine`
- **`REFERENCES`** (Method→Class, Method→Field, Class→Class for type refs)
- **`IMPORTS`** (File→Class, File→Module)
- **`EXTENDS`** (Class→Class)
- **`IMPLEMENTS`** (Class→Class)
- **`OVERRIDES`** (Method→Method)
- `READS_TABLE` / `WRITES_TABLE` / `READS_COLUMN` / `WRITES_COLUMN` — Phase 2
- `EXPOSES` (Class→ApiEndpoint), `HANDLES` (ApiEndpoint→Method) — Phase 2
- `USES_ENV`, `READS_CONFIG` — Phase 2
- `DEPENDS_ON` (Project→MavenDependency) — Phase 2
- `TESTS` (Method→Method, heuristic) — Phase 2
- `PRODUCES_QUEUE` / `CONSUMES_QUEUE` / `SCHEDULED_BY` — Phase 4

### Constraints and indexes (created by `SchemaBootstrap` on first connect)
- `CREATE CONSTRAINT FOR (n:<Label>) REQUIRE n.id IS UNIQUE` for every label
- `CREATE INDEX FOR (n:Method) ON (n.projectId, n.fqName)`
- `CREATE INDEX FOR (n:Class) ON (n.projectId, n.fqName)`
- `CREATE INDEX FOR (n:File) ON (n.projectId, n.path)`

### Parser SPI (in `cvector-core`)
```java
public interface Parser {
    String name();
    Set<String> supportedExtensions();
    void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink);
}

public sealed interface GraphEvent {
    record NodeUpsert(NodeKey key, Map<String, Object> props) implements GraphEvent {}
    record EdgeUpsert(NodeKey from, String type, NodeKey to, Map<String, Object> props) implements GraphEvent {}
    record NodeRemove(NodeKey key) implements GraphEvent {}
}

public record NodeKey(String projectId, String label, String fqName) {
    public String id() {
        return Hashing.sha256(projectId + ":" + label + ":" + fqName).substring(0, 16);
    }
}
```

---

## Phase 1 — Vertical Slice

### Module pom dependencies (Phase 1)

**`cvector-parent/pom.xml`** — Spring Boot BOM 3.2+, `<java.version>17</java.version>`, modules list, `spring-boot-maven-plugin` configured in `cvector-app`.

**`cvector-core/pom.xml`** — `com.fasterxml.jackson.core:jackson-databind` for config serialization.

**`cvector-neo4j/pom.xml`** — `org.neo4j.driver:neo4j-java-driver:5.x`, depends on `cvector-core`.

**`cvector-parser-java/pom.xml`** — `com.github.javaparser:javaparser-symbol-solver-core:3.25.x`, depends on `cvector-core`.

**`cvector-cli/pom.xml`** — `info.picocli:picocli:4.7.x`, `info.picocli:picocli-spring-boot-starter:4.7.x`; depends on `cvector-core`, `cvector-neo4j`, `cvector-parser-java`.

**`cvector-app/pom.xml`** — `spring-boot-starter`, depends on `cvector-cli`. Has `spring-boot-maven-plugin` with AppCDS configuration. Main class `io.doindev.cvector.CvectorApplication`.

### Phase 1 commands and expected behavior

- `cvector init` — Creates `.cvector/project.json` in CWD: `{ "projectId": "<uuid>", "name": "<dirname>", "neo4j": { "uri": "bolt://localhost:7687", "user": "neo4j", "password": "..." } }`. Writes `.cvector/docker-compose.yml` Neo4j 5 template. Refuses to overwrite existing config.
- `cvector scan [path]` — Resolves path (default `.`), invokes `JavaParserAdapter` recursively over `.java` files, streams `GraphEvent`s into `Ingestor`. Ingestor batches into UNWIND-driven `MERGE` Cypher statements (chunks of 500). Prints summary `Files: X, Classes: Y, Methods: Z, Edges: N`. `--project <name>` selects/creates project context.
- `cvector status` — Bolt ping; project stats from graph (node counts by label, edge counts by type, last scan timestamp).
- `cvector doctor` — Config exists? Valid JSON? Neo4j URI reachable? Auth OK? Constraints/indexes present? Disk space OK? Returns non-zero on any failure.
- `cvector query "<cypher>"` — Wraps user Cypher in a `projectId` parameter, executes, pretty-prints results (tab-aligned columns or JSON via `--json`).
- `cvector explain <symbol>` — `MATCH (n) WHERE n.projectId = $pid AND n.fqName ENDS WITH $sym`; renders type, file:line, owner class, all incoming CALLS edges (callers), all outgoing CALLS edges (callees).
- `cvector impact <symbol> [--depth N]` — BFS outward via CALLS/REFERENCES from matched symbol; prints affected methods grouped by file with line numbers. Default depth 3.
- `cvector project list|create <name>|switch <name>|info` — Manages multi-project workspace inside `.cvector/project.json`. `create` adds a new project entry with fresh `projectId`; `switch` sets `activeProject`; `info` prints active config.

### Phase 1 file layout (Java sources)

```
cvector-core/src/main/java/io/doindev/cvector/core/
  ├── GraphEvent.java              (sealed interface + 3 records)
  ├── NodeKey.java                 (record)
  ├── Parser.java                  (interface)
  ├── ProjectContext.java          (active projectId, neo4j config)
  └── config/CvectorConfig.java    (JSON model for .cvector/project.json)

cvector-neo4j/src/main/java/io/doindev/cvector/neo4j/
  ├── Neo4jClient.java             (Bolt driver wrapper)
  ├── SchemaBootstrap.java         (creates constraints/indexes)
  ├── Ingestor.java                (batches GraphEvents into MERGE statements)
  └── repo/{SymbolRepo, ImpactRepo, ExplainRepo}.java

cvector-parser-java/src/main/java/io/doindev/cvector/parser/java/
  ├── JavaParserAdapter.java       (implements Parser)
  ├── JavaTypeSolver.java          (builds CombinedTypeSolver)
  └── visitors/{ClassVisitor, MethodVisitor, CallVisitor}.java

cvector-cli/src/main/java/io/doindev/cvector/cli/
  ├── CvectorCommand.java          (Picocli root)
  ├── commands/{Init, Scan, Status, Doctor, Query, Explain, Impact, Project}.java
  └── output/{TableRenderer, JsonRenderer}.java

cvector-app/src/main/java/io/doindev/cvector/
  └── CvectorApplication.java      (Spring Boot @SpringBootApplication)
```

---

## Phase 2 — Parser breadth + REST API
- Parsers: SQL (JSqlParser), JSON/YAML (Jackson + SnakeYAML), Maven POM (`MavenXpp3Reader`), Spring annotation scanner (reads `.class` via ASM or source-level via JavaParser).
- New node types: Table, Column, ApiEndpoint, EnvVar, ConfigKey, MavenDependency.
- New CLI: `scan:incremental` (git-diff-driven), `recent`, `rename`, `migrate`, `pr-impact`, `test-impact`.
- `cvector-rest` module: 14 endpoints (`/api/stats`, `/api/health`, `/api/onboard`, `/api/rules`, `/api/dead-code`, `/api/god-files`, `/api/duplicates`, `/api/communities`, `/api/flows`, `/api/projects`, `/api/impact?symbol=`, `/api/explain?symbol=`, `/api/test-impact?symbol=`, `/api/search?q=`).
- `cvector dashboard` starts the REST server on port 2969.

## Phase 3 — MCP server + reporting
- `cvector-mcp` module: Spring AI MCP starter. 21 tools (`cv_search`, `cv_context`, `cv_explain`, `cv_projects`, `cv_impact`, `cv_trace`, `cv_path`, `cv_db_impact`, `cv_test_impact`, `cv_rename`, `cv_health`, `cv_communities`, `cv_flows`, `cv_rules`, `cv_guard`, `cv_diff`, `cv_service_links`, `cv_changes`, `cv_onboard`, `cv_wiki`, `cv_audit`) as `@Tool`-annotated beans calling the same service layer as REST controllers.
- 9 MCP resources: `/stats`, `/health`, `/files`, `/communities`, `/onboard`, `/schema`, `/projects`, `/infrastructure`, `/guard`.
- 6 MCP prompts: `cvector-onboard`, `cvector-review-change`, `cvector-health-check`, `cvector-explain-module`, `cvector-migration-plan`, `cvector-infrastructure`.
- IDE setup commands: `setup-cursor`, `setup-windsurf`, `setup-claude`, `setup-antigravity`.
- Reporting commands: `onboard`, `wiki --out`, `changelog --since`, `badge`.
- `cvector serve` boots MCP server + visualization dashboard.

## Phase 4 — Live/automation
- `cvector-watcher` (methvin/directory-watcher): live mode emits `Path` events into Ingestor (debounced 250ms); cron mode driven by Spring `@Scheduled` with a user-defined expression.
- `cvector-rules`: 9 configurable rules + custom Cypher rules. `cvector rules`, `cvector rules --init`.
- `cvector guard` — quality gate with `--ci`, `--json`, `--god-file-threshold`, `--install-hook`, `--uninstall-hook`.
- `cvector audit` — dependency vulnerability check (OSV API or OWASP Dependency-Check) cross-referenced with `DEPENDS_ON` → `IMPORTS` → ... graph blast radius.
- `cvector ci` — runs scan + rules + guard + audit; `--skip-scan` if graph already up to date.

## Phase 5 — Advanced + roles
- Additional parsers: TypeScript/JavaScript (Tree-sitter via java-tree-sitter), Python, Go, Rust, Terraform, Dockerfile, C#, `.env`.
- `cvector communities` — union-find clustering on the call graph + Jaccard cohesion scoring.
- `cvector flows` — BFS from entry points (HTTP handlers, queue consumers, schedulers, `main` methods).
- `cvector diff <sha1> <sha2>` — runs two scans, diffs node/edge sets, reports drift.
- `cvector service-links` — cross-service deps via queues/topics/HTTP clients.
- **Role-scoped access** — env var `cvector_role` (`dev`/`architect`/`security`/`pm`) read at MCP/REST startup; a `RoleFilter` interceptor on REST + tool registration filter on MCP suppress disallowed surfaces.

---

## Verification

### Phase 1 acceptance (end-to-end happy path)
1. `mvn clean install` from `cvector-parent` — all modules build, no warnings.
2. Start Neo4j: `docker compose -f .cvector/docker-compose.yml up -d` (template generated by `init`).
3. In a Java project dir: `cvector init` — writes `.cvector/project.json`.
4. `cvector doctor` — exits 0; reports Neo4j healthy, schema bootstrapped.
5. `cvector scan .` — reports non-zero counts in <30s for a medium repo.
6. `cvector status` — prints `Files: X, Classes: Y, Methods: Z, Edges: N` matching the scan.
7. `cvector explain SomeClass.someMethod` — prints definition file:line, callers, callees.
8. `cvector impact SomeClass.someMethod --depth 2` — prints downstream methods grouped by file with line numbers.
9. `cvector query "MATCH (m:Method {projectId: $pid}) RETURN m.name LIMIT 5"` — runs against the active project.
10. `cvector project list/create/switch/info` — manages two projects in one workspace successfully.

### Phase 1 integration test
`cvector-app/src/test/java/io/doindev/cvector/EndToEndScanIT.java`:
- `@Testcontainers` with `Neo4jContainer<>("neo4j:5")`.
- Fixture under `src/test/resources/fixtures/sample-project/` containing a small Java project (3–4 classes, controlled call graph).
- Run `Scan` command against the fixture, then assert Cypher queries return expected counts and specific `CALLS` edges exist between named methods.

### Per-phase smoke tests
Each later phase adds an IT class in the relevant module exercising the new commands/parsers/endpoints with Testcontainers Neo4j.

---

## Build Sequence (Phase 1 execution order)

1. Convert root `pom.xml` to multi-module parent (packaging=pom, Spring Boot BOM, Java 17).
2. Scaffold child module directories with their `pom.xml` files (still empty source).
3. Verify `mvn clean install` succeeds with the empty scaffold.
4. Implement `cvector-core` types (`NodeKey`, `GraphEvent`, `Parser`, `ProjectContext`, `CvectorConfig`).
5. Implement `cvector-neo4j` (`Neo4jClient`, `SchemaBootstrap`, `Ingestor`, repositories).
6. Implement `cvector-parser-java` (`JavaParserAdapter`, `JavaTypeSolver`, visitors).
7. Implement `cvector-cli` Picocli commands (`Init`, `Scan`, `Status`, `Doctor`, `Query`, `Explain`, `Impact`, `Project`).
8. Implement `cvector-app` `CvectorApplication` main and AppCDS config.
9. Write Testcontainers-based `EndToEndScanIT` against the fixture project.
10. Run `mvn clean install` and confirm IT passes.
