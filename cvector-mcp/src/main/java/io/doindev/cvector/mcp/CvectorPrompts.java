package io.doindev.cvector.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-built conversation starters that guide an AI assistant through common analysis workflows.
 *
 * <p>Every prompt's body explicitly mentions the {@code project} argument every cv_* read tool
 * accepts (name, UUID, or rootPath). When the user doesn't name a project, the agent should
 * either elide the {@code project} arg entirely (tool falls back to the workspace default) or
 * call {@code cv_list_projects} first to disambiguate.
 *
 * <p>Prompts that target a single project take an optional {@code project} argument so the
 * caller can scope the prompt up-front; the rendered prompt body threads that value through to
 * every recommended tool call.
 */
public class CvectorPrompts {

    public List<McpServerFeatures.SyncPromptSpecification> getPrompts() {
        List<McpServerFeatures.SyncPromptSpecification> out = new ArrayList<>();
        out.add(discoverProjectsPrompt());
        out.add(onboardPrompt());
        out.add(onboardNewProjectPrompt());
        out.add(reviewChangePrompt());
        out.add(healthCheckPrompt());
        out.add(explainModulePrompt());
        out.add(migrationPlanPrompt());
        out.add(infrastructurePrompt());
        out.add(crossProjectAuditPrompt());
        return out;
    }

    private static McpServerFeatures.SyncPromptSpecification discoverProjectsPrompt() {
        return prompt(
                "cvector-discover-projects",
                "Enumerate every project in the workspace with metadata and recommend which one matches the user's intent.",
                List.of(arg("hint", "Optional: what the user is looking for (name fragment, path, or description).", false)),
                args -> {
                    String hint = argOrEmpty(args, "hint");
                    return """
                            You are about to operate on a cvector workspace. Before any per-project query,
                            establish which project you're targeting.

                            Required steps:
                            1. Call cv_list_projects to get every project with its name, projectId (UUID),
                               rootPath, isolation flag, total node/edge counts, and last scan timestamp.
                            2. %s

                            Produce a summary with:
                            - Total project count and which is the workspace default (active=true).
                            - For each project: one-line summary including name, rootPath, scan freshness,
                              and rough size (nodes).
                            - If a hint was provided, the single best match with a rationale.
                            - Concrete next-step suggestions (e.g. "use project='%s' on the next call").
                            """.formatted(
                            hint.isBlank()
                                    ? "If the user mentioned a project name, UUID, or directory, call cv_find_project to resolve it."
                                    : "Call cv_find_project(query=\"" + hint + "\") and report whether it resolved.",
                            hint.isBlank() ? "<name>" : hint);
                },
                "Project discovery");
    }

    private static McpServerFeatures.SyncPromptSpecification onboardPrompt() {
        return prompt(
                "cvector-onboard",
                "Architecture briefing for a project that's already scanned into cvector.",
                List.of(arg("project", "Project name, UUID, or rootPath. Omit to use the workspace default.", false)),
                args -> {
                    String project = argOrEmpty(args, "project");
                    String projectArg = project.isBlank() ? "" : ", project=\"" + project + "\"";
                    String projectClause = project.isBlank()
                            ? "the workspace's default project (set via cv_set_default_project)"
                            : "project '" + project + "'";
                    return """
                            You are onboarding to %s. Use the cvector MCP server to produce a one-page brief.

                            Required steps (pass project=… to every call if a specific project was named%s):
                            1. Call cv_onboard%s for a structured codebase snapshot.
                            2. Call cv_stats%s and cv_communities%s to map the call graph.
                            3. Call cv_rules%s to surface the most pressing health issues.
                            4. Call cv_service_links%s for the cross-service surface.

                            Confirm in your answer which project (name + projectId) the brief is for —
                            every tool response carries a top-level `project` block; quote its
                            `name` and `projectId` so the user can verify the scope is correct.

                            Produce a brief with these sections:
                            - Project name, projectId, rootPath, language mix
                            - Top 5 modules by call-graph centrality
                            - External surface (REST endpoints, message queues, scheduled jobs)
                            - Configuration & environment variables that matter at startup
                            - Top 3 risks (god classes, long methods, dead code) with file:line citations
                            - "Where to start reading" — 5 suggested files in dependency order
                            """.formatted(projectClause,
                            project.isBlank() ? "" : "; omit it to use the default",
                            projectArg, projectArg, projectArg, projectArg, projectArg);
                },
                "Architecture briefing");
    }

    private static McpServerFeatures.SyncPromptSpecification onboardNewProjectPrompt() {
        return prompt(
                "cvector-onboard-new-project",
                "Register and scan a brand-new codebase into cvector, then produce a first-look briefing.",
                List.of(
                        arg("name", "Human-readable project name (used as the workspace key).", true),
                        arg("rootPath", "Absolute directory path of the codebase root.", true),
                        arg("isolated", "If 'true', give this project its own Kuzu DB. Default false.", false)),
                args -> {
                    String name = argOrEmpty(args, "name");
                    String rootPath = argOrEmpty(args, "rootPath");
                    String isolated = argOrEmpty(args, "isolated");
                    return """
                            You are onboarding a brand-new codebase to cvector. The user wants you to
                            register, scan, and briefly describe it.

                            Required steps:
                            1. Call cv_find_project(query="%s") to confirm this path isn't already
                               registered (avoid duplicating an existing project).
                            2. If not found, call cv_onboard_project(name="%s", rootPath="%s"%s).
                               This atomically:
                               - Registers the project in settings.json with a fresh UUID
                               - Rejects rootPaths that overlap an existing project (sub-directory or parent)
                               - Scans the entire codebase into the graph (synchronous; may take ~30 s)
                               - Returns a codebase briefing
                            3. Inspect the returned `project.projectId` and `scan.summary` — both will
                               appear in subsequent tool calls' responses too.

                            Confirm to the user:
                            - The new project's name + projectId + rootPath
                            - Scan results (file count, total nodes/edges, elapsed time)
                            - 2–3 sentence first-look summary based on the briefing payload
                            - Suggested next moves (e.g. cv_rules to surface health issues,
                              cv_onboard for a deeper architecture brief)

                            If the path is already registered or overlaps an existing project, surface
                            the conflict clearly with the offending project's name and rootPath,
                            and ask the user how to proceed (use existing project? remove the old one?
                            pick a different rootPath?).
                            """.formatted(rootPath, name, rootPath,
                            isolated.equalsIgnoreCase("true") ? ", isolated=true" : "");
                },
                "Onboard new project");
    }

    private static McpServerFeatures.SyncPromptSpecification reviewChangePrompt() {
        return prompt(
                "cvector-review-change",
                "Impact analysis before changing a function, class, or file.",
                List.of(
                        arg("symbol", "Fully-qualified method or class name, or file path.", true),
                        arg("project", "Project name, UUID, or rootPath. Omit to use the workspace default.", false)),
                args -> {
                    String symbol = argOrEmpty(args, "symbol");
                    String project = argOrEmpty(args, "project");
                    String projectArg = project.isBlank() ? "" : ", project=\"" + project + "\"";
                    return """
                            You are reviewing a proposed change to `%s`. Use the cvector MCP server to map
                            the blast radius before approving it.

                            Required steps:
                            1. cv_explain(symbol="%s"%s) — direct neighbours, file:line.
                            2. cv_impact(symbol="%s"%s, depth=4) — downstream callers.
                            3. cv_test_impact(symbol="%s"%s) — tests that cover the symbol.
                            4. cv_service_links(%s) — cross-service consumers that might break.

                            Every response carries `project.projectId` + `project.rootPath`; cite the project
                            in your answer so the user knows which codebase you're analysing.

                            Produce a review note with:
                            - One-sentence summary of what the symbol does
                            - Direct callers (with confidence < 0.5 flagged as "ambiguous")
                            - Tests that cover it
                            - Cross-service consumers (if any)
                            - "Safe to change" verdict (yes / yes-with-tests / requires-coordination)
                            - Suggested test additions if coverage looks thin
                            """.formatted(
                            symbol, symbol, projectArg, symbol, projectArg, symbol, projectArg,
                            project.isBlank() ? "" : "project=\"" + project + "\"");
                },
                "Change impact review");
    }

    private static McpServerFeatures.SyncPromptSpecification healthCheckPrompt() {
        return prompt(
                "cvector-health-check",
                "Comprehensive health report with prioritized action items.",
                List.of(arg("project", "Project name, UUID, rootPath, or '*' for cross-project. Omit to use the default.", false)),
                args -> {
                    String project = argOrEmpty(args, "project");
                    String projectArg = project.isBlank() ? "" : "project=\"" + project + "\"";
                    String scope = project.isBlank() ? "the default project"
                            : "*".equals(project) ? "every project in the workspace"
                            : "project '" + project + "'";
                    return """
                            You are auditing code health for %s.

                            Required steps:
                            1. cv_guard(%s) — quality-gate snapshot.
                            2. cv_rules(%s) — detailed violations grouped by severity.
                            3. cv_audit(%s) — dependency CVEs cross-referenced with the graph.
                            4. cv_changes(%s, since="7d") — what changed recently.

                            Every response carries the project context — cite `project.name` + projectId
                            in your answer.

                            Produce a report with:
                            - Pass/fail verdict from the guard for each scoped project
                            - Top 10 action items, prioritized by (severity × blast-radius)
                            - For each: file:line, brief problem statement, suggested fix
                            - Vulnerabilities ranked by exposure (callers × CVSS)
                            - One-sentence trajectory note ("better / worse / same than last scan")
                            """.formatted(scope, projectArg, projectArg, projectArg, projectArg);
                },
                "Health report");
    }

    private static McpServerFeatures.SyncPromptSpecification explainModulePrompt() {
        return prompt(
                "cvector-explain-module",
                "Deep-dive into a specific file or module.",
                List.of(
                        arg("path", "Project-relative file path or class FQN.", true),
                        arg("project", "Project name, UUID, or rootPath. Omit to use the default.", false)),
                args -> {
                    String path = argOrEmpty(args, "path");
                    String project = argOrEmpty(args, "project");
                    String projectArg = project.isBlank() ? "" : ", project=\"" + project + "\"";
                    return """
                            You are explaining `%s` to a new contributor. Use the cvector MCP server.

                            Required steps:
                            1. cv_explain(symbol="%s"%s) — declarations + neighbours.
                            2. cv_impact on the top public methods to see who depends on this module.
                            3. cv_flows from any entry points (REST handlers, queue listeners, main) that
                               reach into this module.

                            Confirm which project (name + projectId) the explanation is for.

                            Produce an explanation covering:
                            - Module's responsibility in 2–3 sentences
                            - Public surface (exported classes / methods, with one-line each)
                            - Inbound dependencies (who calls in, and why)
                            - Outbound dependencies (what it depends on, including external libs)
                            - Lifecycle / state notes if any (Spring beans, singletons, statics)
                            - Reading order: 3–5 files to read in sequence to understand it end-to-end
                            """.formatted(path, path, projectArg);
                },
                "Module deep-dive");
    }

    private static McpServerFeatures.SyncPromptSpecification migrationPlanPrompt() {
        return prompt(
                "cvector-migration-plan",
                "Step-by-step migration plan with risk assessment.",
                List.of(
                        arg("from", "What is being replaced (library, framework, API, etc.).", true),
                        arg("to", "What it is being migrated to.", true),
                        arg("scope", "Optional: file glob or class prefix to limit the scope.", false),
                        arg("project", "Project name, UUID, or rootPath. Omit to use the default.", false)),
                args -> {
                    String from = argOrEmpty(args, "from");
                    String to = argOrEmpty(args, "to");
                    String scope = argOrEmpty(args, "scope");
                    String project = argOrEmpty(args, "project");
                    String projectArg = project.isBlank() ? "" : ", project=\"" + project + "\"";
                    return """
                            Plan a migration from `%s` to `%s`%s. Use the cvector MCP server.

                            Required steps:
                            1. cv_search(query="%s"%s) — every reference to the legacy API.
                            2. cv_impact on each high-usage reference to estimate per-call-site effort.
                            3. cv_test_impact on each — which tests will need to change.
                            4. cv_wiki(%s) — full infrastructure surface (config keys, env vars, container
                               images, dependencies) that may need deprecation messaging.

                            Cite `project.name` + projectId in your answer so the user knows which
                            codebase the plan covers.

                            Produce a migration plan with:
                            - Inventory: total touch points grouped by module
                            - Sequencing: 3–5 phases in dependency order, with rollback criteria for each
                            - Risk register: 5–10 entries scored (likelihood × impact) with mitigations
                            - Test additions needed before the migration starts
                            - Rollout: feature-flag strategy, canary plan, and a single "no-go" signal
                            - Estimated effort in eng-days
                            """.formatted(
                            from, to,
                            scope.isEmpty() ? "" : " (scope: " + scope + ")",
                            from, projectArg,
                            project.isBlank() ? "" : "project=\"" + project + "\"");
                },
                "Migration plan");
    }

    private static McpServerFeatures.SyncPromptSpecification infrastructurePrompt() {
        return prompt(
                "cvector-infrastructure",
                "Audit all infrastructure: queues, metrics, events, external APIs, env vars, IaC resources.",
                List.of(arg("project", "Project name, UUID, rootPath, or '*' for cross-project. Omit to use the default.", false)),
                args -> {
                    String project = argOrEmpty(args, "project");
                    String projectArg = project.isBlank() ? "" : "project=\"" + project + "\"";
                    String scope = project.isBlank() ? "the default project"
                            : "*".equals(project) ? "every project in the workspace"
                            : "project '" + project + "'";
                    return """
                            You are mapping the infrastructure surface of %s.

                            Required steps:
                            1. cv_wiki(%s) — full snapshot (endpoints, listeners, scheduled jobs,
                               config keys, env vars, container images, ports, IaC resources).
                            2. cv_service_links(%s) — cross-service dependencies (queues, event buses, HTTP).
                            3. cv_flows(%s) — entry-point reachability into internal code paths.

                            Confirm the project context in your answer (`project.name` + projectId).

                            Produce an infrastructure audit with:
                            - Inbound surface table: protocol, endpoint, handler method, auth assumptions
                            - Outbound surface table: external service, call site(s), retry/timeout posture
                            - Configuration table: every env var + config key, where it is read, default
                            - Cloud / IaC: list of declared resources grouped by provider
                            - Risk callouts: endpoints with no auth in the call path, env vars without
                              defaults that crash startup, orphaned IaC resources
                            """.formatted(scope, projectArg, projectArg, projectArg);
                },
                "Infrastructure audit");
    }

    private static McpServerFeatures.SyncPromptSpecification crossProjectAuditPrompt() {
        return prompt(
                "cvector-cross-project-audit",
                "Workspace-wide query across every project for shared concerns (dependency, framework usage, common patterns).",
                List.of(arg("query", "What to find across projects (e.g. 'org.slf4j', 'PrintStream.println', 'TODO').", true)),
                args -> {
                    String query = argOrEmpty(args, "query");
                    return """
                            You are running a workspace-wide audit for `%s`. Use the cvector MCP server.

                            Required steps:
                            1. cv_list_projects — confirm how many projects you'll be searching across.
                            2. cv_search(query="%s", project="*") — every match across every project.
                               Results are tagged with `project.projectId` + `project.name` per row.
                            3. For interesting hits, follow up with cv_explain(symbol=…, project=<name>)
                               to drill into specific call sites.

                            Produce a report with:
                            - Total match count and a per-project breakdown (rows tagged by project)
                            - Top 10 most-used call sites by project
                            - Cross-project patterns: identical fqNames in multiple projects
                            - Recommended consolidation candidates (shared library extraction, etc.)
                            """.formatted(query, query);
                },
                "Cross-project audit");
    }

    private static McpServerFeatures.SyncPromptSpecification prompt(
            String name, String description, List<McpSchema.PromptArgument> args,
            java.util.function.Function<Map<String, Object>, String> renderer, String resultDescription) {
        McpSchema.Prompt prompt = new McpSchema.Prompt(name, description, args);
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            String text = renderer.apply(req.arguments() == null ? Map.of() : req.arguments());
            McpSchema.PromptMessage msg = new McpSchema.PromptMessage(
                    McpSchema.Role.USER, new McpSchema.TextContent(text));
            return new McpSchema.GetPromptResult(resultDescription, List.of(msg));
        });
    }

    private static McpSchema.PromptArgument arg(String name, String description, boolean required) {
        return new McpSchema.PromptArgument(name, description, required);
    }

    private static String argOrEmpty(Map<String, Object> args, String name) {
        Object v = args.get(name);
        return v == null ? "" : v.toString();
    }
}
