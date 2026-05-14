# Copilot instructions for cvector

## What this project is

cvector is a Neo4j-backed code knowledge graph. Parsers walk a project, emit `GraphEvent` records, an `Ingestor` batches them into Neo4j, and three surfaces (CLI, REST, MCP) query the resulting graph. Build target Java 17, runtime Java 21+ to enable virtual threads in MCP/dashboard modes.

## Architecture map

```
cvector-core/             GraphEvent (NodeUpsert/EdgeUpsert/NodeRemove), NodeKey, Parser interface, CvectorConfig, CvectorRole
cvector-neo4j/            Neo4jClient (driver wrapper), Ingestor (synchronized batcher), SchemaBootstrap, GraphQueries
cvector-embedded-kuzudb-server/  Optional embedded KuzuDB store. EmbeddedKuzu (in-process), KuzuSchemaBootstrap (idempotent DDL), KuzuIngestor (deduping, shape-cached MERGEs in 500-row transactions), KuzuPostScan (unresolved-call rewiring, cleanupStale), KuzuGraphStore (read surface). Module is named with `-kuzudb-` so siblings (e.g. DuckDB-backed embedded server) can sit alongside.
cvector-parser-*/         25 language/format parsers — most ANTLR4-based, all implement Parser
cvector-rules/            RulesEngine + builtin rules (god-file, god-class, long-method, deep-inheritance, dead-code)
cvector-watcher/          File watcher + cron-driven re-scan
cvector-cli/              Picocli commands wired as Spring beans (33 commands)
cvector-rest/             Spring Web controllers (`/api/*`)
cvector-mcp/              MCP server: CvectorTools (16 tools), CvectorResources (8), CvectorPrompts (6)
cvector-app/              Spring Boot main + fat-jar assembly (single entrypoint for all modes)
```

The fat jar at `cvector-app/target/cvector.jar` selects mode via the first argument: `dashboard` → web, `serve` → MCP stdio, anything else → CLI command.

## Build & test

```bash
mvn -DskipTests install          # build the fat jar (~5–15s with warm cache)
mvn test                         # full test suite (Testcontainers spin Neo4j for IT tests)
mvn -pl cvector-parser-foo test  # one parser module
```

CI / scan-from-clean validation:

```bash
java -jar cvector-app/target/cvector.jar scan
java -jar cvector-app/target/cvector.jar rules    # should report 0 violations
```

## Conventions worth following

### Parser conventions

- **Parsers don't do symbol resolution.** They emit `unresolved.<name>:<arity>` placeholder `Method` nodes for cross-file calls; `ScanCommand.resolveUnresolvedCalls` post-pass rewires single-candidate matches in Cypher. Don't reintroduce `JavaSymbolSolver.resolve()` on the hot path — it adds ~10× scan time via reflection + stack-trace generation on every unresolved call.
- **ANTLR is preferred** for new languages. Grammars live under `src/main/antlr4/io/doindev/cvector/parser/<lang>/internal/`, base classes under `src/main/java/.../internal/` with a `package` declaration added (grammars-v4 ships them without packages).
- **Comments-in-comments suppression** is non-negotiable: every parser has tests proving it ignores declarations inside `//`, `/* */`, docstrings, and string literals.
- **`Parser.accepts(Path)`** can be overridden when the extension alone isn't enough (Dockerfile, `.d.ts` skip-list).
- Vendored grammar code lives under `*/internal/` paths — `rules.yml` excludes those by default.

### Graph conventions

- Every node carries `projectId`, `label` (the Neo4j label), and `fqName` — composed into a SHA-256-based `id` by `NodeKey.id()`.
- Methods carry `paramCount` so the post-pass resolver can disambiguate by arity.
- Edges are typed: `CONTAINS`, `CALLS`, `EXTENDS`, `IMPLEMENTS`, `IMPORTS`, `EXPOSES`, `HANDLES`, `READS_TABLE`, `WRITES_TABLE`, `DEPENDS_ON`, `USES`, `DECLARES`, `READS_CONFIG`.
- New node types: pick a descriptive label, set `name` + `fqName`, follow the existing per-parser pattern. The `Ingestor` will batch them transparently.

### Rules conventions

- Built-in rules live in `cvector-rules/src/main/java/io/doindev/cvector/rules/builtin/`.
- Each rule implements `Rule.evaluate(projectId, GraphQueries, RulesConfig)` and returns `List<Violation>`.
- Rules support **path exclusion** via `RulesConfig.isPathExcluded(path)`. New rules that query Methods/Classes should pull the owning File's `path` into their Cypher and call `isPathExcluded` before adding to the violation list.

### MCP conventions

- Tools live in `CvectorTools.java`, annotated `@Tool(name = "cv_*", description = "...")` with `@ToolParam` for each argument.
- Resources live in `CvectorResources.getResources()`, returned as `List<SyncResourceSpecification>`. URIs follow `cvector://<name>`. MIME type is always `application/json`.
- Prompts live in `CvectorPrompts.getPrompts()` and return USER messages that *name the cv_\* tools and `cvector://` resources the AI should call*. They're conversation starters, not implementations.
- Role gating (`CvectorRole.allowsTool`) wraps the `ToolCallbackProvider`. Resources and prompts are not yet role-gated.

### Spring / Boot conventions

- The Spring Boot main (`CvectorApplication`) inspects `args[0]` to pick `dashboard` (servlet web), `serve` (MCP stdio, profile `mcp`), or `none` (CLI).
- All beans are configured manually in `*Configuration` classes — no `@ComponentScan` magic on parsers. Parsers register through `CliConfiguration.@Bean Parser ...()` methods.
- Virtual threads are enabled via property `spring.threads.virtual.enabled=true` in `application-mcp.properties` and in the dashboard's web properties. **Do not** enable for the scan loop — it's CPU-bound and virtual threads don't help there.

### Embedded KuzuDB conventions

- `--embedded` (Picocli, `scope = INHERIT`) and `CVECTOR_EMBEDDED=true` set the `cvector.embedded` system property. The `cvector embedded` subcommand tree (`init`, `info`, `query`, `wipe`) exercises the Kuzu store directly.
- Database location: `~/.cvector/kuzu-data/<projectId>/graph.kuzu/` — a directory, not a file.
- Schema is **declared upfront**: one polymorphic `Node` table (discriminated by a `label` property) plus 14 typed `REL` tables (`Node → Node`). New node labels are first-class properties, not new tables. New edge types must be added to `KuzuSchemaBootstrap.EDGE_TYPES`.
- Kuzu's Cypher dialect is a subset of Neo4j's. Specifically: **`SET n += $props` is NOT supported** — properties must be enumerated. `MERGE` semantics are more limited. Anyone writing new Kuzu-targeted queries must use `MATCH ... CREATE` idioms.
- Kuzu native lib auto-extracts from the jar at first load — it triggers the JDK-25 `System.load` restricted-method warning, which is harmless.
- Don't try to "pretend" Kuzu is Neo4j by adapting `Neo4jClient.Record`. `EmbeddedKuzu.read()` returns `List<Map<String, Object>>` deliberately.

### Performance reminders

- Java parsing was the historical bottleneck (~48s for 200 files). Current scan is **~5.9 s warm** for 218 files / 14 k nodes / 14 k edges (~27 ms per file). Don't reintroduce per-call `.resolve()` exception throwing.
- `Ingestor` batches at 500 nodes/edges per `UNWIND`. Don't issue per-node `MERGE` writes.
- `cleanupStale` is one count + one delete across all label types, not 13 round-trips. Keep it that way.
- The file-walk uses `ForkJoinPool.parallelStream()`. Anything Parser code does must be thread-safe (ANTLR parsers create fresh lexer/parser per `parse()` call, which is the easy path; sharing state across threads is the trap).

## Coding style

- Java 17 features welcome: records, sealed interfaces, pattern matching for `instanceof`, switch expressions, text blocks.
- No comments stating *what* the code does — names should do that. Comments are reserved for *why*: non-obvious invariants, references to a previous bug, performance trade-offs.
- Prefer one-line javadoc for public methods; use multi-line only when the *why* genuinely requires it.
- Don't introduce abstractions for hypothetical future code. Three similar lines is better than a premature `AbstractFooFactory`.
- Don't add error handling for impossible cases. Validate at boundaries (file I/O, network), trust internal invariants.

## Testing requirements

- Every parser module has a `*ParserAdapterTest` with at minimum: a happy-path extraction test, a comments-suppression test, and (where applicable) a string-literal suppression test.
- Integration test lives in `cvector-app/src/test/java/.../EndToEndScanIT.java`. It uses Testcontainers to spin Neo4j and asserts node/edge counts across the full parser stack.
- Use AssertJ. JUnit 5. `@TempDir` for filesystem tests.
- Don't mock the Neo4j driver in IT tests — the whole point is exercising real graph queries.

## Common tasks

| Task | Where to start |
|---|---|
| Add a new language | Copy a grammar from `grammars-v4` cache (host-local at `/c/Users/timhj/grammars-cache/grammars-v4`) into `cvector-parser-<lang>/src/main/antlr4/.../internal/`, add base class with `package` declaration, write `*ParserAdapter` implementing `Parser`, register `@Bean` in `CliConfiguration`. |
| Add a CLI command | New class in `cvector-cli/src/main/java/.../commands/` annotated `@Component @Command(name = ...)` implementing `Callable<Integer>`. Picocli + Spring autowire pick it up. |
| Add an MCP tool | New `@Tool` method on `CvectorTools`. |
| Add a rule | New class in `cvector-rules/.../builtin/` implementing `Rule`, register in the `RulesEngine` builtin list. Remember `isPathExcluded`. |
| Add a Cypher query helper | `GraphQueries` in `cvector-neo4j`. Keep it parameterized — never concatenate user input into Cypher. |

## Things to avoid

- **Don't add JDK/library calls** as graph nodes during parse (e.g., `java.util.Map.put`). Those used to come from JavaSymbolSolver; they're noise. Cross-file project calls are what matters.
- **Don't write to Neo4j from inside a `client.read(...)` callback.** Read returns a list; do the write as a separate `client.write(...)` call.
- **Don't hardcode the projectId.** Always parameterize Cypher with `$pid` and pull from `ProjectContext.projectId()` or `McpActiveProject.projectId()`.
- **Don't use `git rebase -i`, `git add -i`, or other interactive git commands** — they hang in non-TTY contexts.
- **Don't bump `<maven.compiler.release>` past 17** without a coordinated effort. Some grammars-v4 base classes assume baseline Java; the runtime target is intentionally higher (JDK 21) than the compile target.
