package io.doindev.cvector.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-built conversation starters that guide an AI assistant through common analysis workflows. Each
 * prompt returns a user message that names the cvector tools/resources the assistant should call to
 * complete the task, plus the format the answer should take.
 */
public class CvectorPrompts {

    public List<McpServerFeatures.SyncPromptSpecification> getPrompts() {
        List<McpServerFeatures.SyncPromptSpecification> out = new ArrayList<>();
        out.add(onboardPrompt());
        out.add(reviewChangePrompt());
        out.add(healthCheckPrompt());
        out.add(explainModulePrompt());
        out.add(migrationPlanPrompt());
        out.add(infrastructurePrompt());
        return out;
    }

    private static McpServerFeatures.SyncPromptSpecification onboardPrompt() {
        return prompt(
                "cvector-onboard",
                "Full architecture briefing for a new team member.",
                List.of(),
                args -> """
                        You are onboarding to a new codebase. Use the cvector MCP server to produce a one-page brief.

                        Required steps:
                        1. Read the cvector://onboard resource for a structured snapshot.
                        2. Call cv_stats and cv_communities to map the call graph.
                        3. Call cv_rules to surface the most pressing health issues.
                        4. Read cvector://infrastructure for the external surface (APIs, queues, env vars).

                        Produce a brief with these sections:
                        - Purpose & language mix
                        - Top 5 modules by call-graph centrality
                        - External surface (REST endpoints, message queues, scheduled jobs)
                        - Configuration & environment variables that matter at startup
                        - Top 3 risks (god classes, long methods, dead code) with file:line citations
                        - "Where to start reading" — 5 suggested files in dependency order
                        """,
                "Architecture briefing");
    }

    private static McpServerFeatures.SyncPromptSpecification reviewChangePrompt() {
        return prompt(
                "cvector-review-change",
                "Impact analysis before changing a function, class, or file.",
                List.of(arg("symbol", "Fully-qualified method or class name, or file path.", true)),
                args -> {
                    String symbol = argOrEmpty(args, "symbol");
                    return """
                            You are reviewing a proposed change to `%s`. Use the cvector MCP server to map
                            the blast radius before approving it.

                            Required steps:
                            1. Call cv_explain on `%s` to get the symbol's file, class, and direct neighbours.
                            2. Call cv_impact on `%s` (depth ≤ 4) for downstream callers.
                            3. Call cv_test_impact on `%s` to identify which tests exercise it.
                            4. Call cv_service_links to surface cross-service dependencies that might break.

                            Produce a review note with:
                            - One-sentence summary of what the symbol does
                            - Direct callers (with confidence < 0.5 flagged as "ambiguous")
                            - Tests that cover it
                            - Cross-service consumers (if any)
                            - "Safe to change" verdict (yes / yes-with-tests / requires-coordination)
                            - Suggested test additions if coverage looks thin
                            """.formatted(symbol, symbol, symbol, symbol);
                },
                "Change impact review");
    }

    private static McpServerFeatures.SyncPromptSpecification healthCheckPrompt() {
        return prompt(
                "cvector-health-check",
                "Comprehensive health report with prioritized action items.",
                List.of(),
                args -> """
                        You are auditing code health. Use the cvector MCP server.

                        Required steps:
                        1. Read cvector://guard for the quality-gate snapshot.
                        2. Call cv_rules for detailed violations grouped by severity.
                        3. Read cvector://health for the raw top-20 lists per rule.
                        4. Call cv_audit (if available) for dependency vulnerabilities cross-referenced with the graph.

                        Produce a report with:
                        - Overall pass/fail verdict from the guard
                        - Top 10 action items, prioritized by (severity × blast-radius)
                        - For each item: file:line, brief problem statement, suggested fix
                        - Vulnerabilities ranked by exposure (callers × CVSS)
                        - One-sentence trajectory note ("better / worse / same than last scan" — use cv_changes for the comparison)
                        """,
                "Health report");
    }

    private static McpServerFeatures.SyncPromptSpecification explainModulePrompt() {
        return prompt(
                "cvector-explain-module",
                "Deep-dive into a specific file or module.",
                List.of(arg("path", "Project-relative file path or class FQN.", true)),
                args -> {
                    String path = argOrEmpty(args, "path");
                    return """
                            You are explaining `%s` to a new contributor. Use the cvector MCP server.

                            Required steps:
                            1. Call cv_explain on `%s` for declarations + neighbours.
                            2. Call cv_impact on its top public methods to see what depends on this module.
                            3. Call cv_flows from any entry points (REST handlers, queue listeners, main) that reach
                               into this module.

                            Produce an explanation covering:
                            - Module's responsibility in 2–3 sentences
                            - Public surface (exported classes / methods, with one-line each)
                            - Inbound dependencies (who calls in, and why)
                            - Outbound dependencies (what it depends on, including external libs)
                            - Lifecycle / state notes if any (Spring beans, singletons, statics)
                            - Reading order: 3–5 files to read in sequence to understand it end-to-end
                            """.formatted(path, path);
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
                        arg("scope", "Optional: file glob or class prefix to limit the scope.", false)),
                args -> {
                    String from = argOrEmpty(args, "from");
                    String to = argOrEmpty(args, "to");
                    String scope = argOrEmpty(args, "scope");
                    return """
                            Plan a migration from `%s` to `%s`%s. Use the cvector MCP server.

                            Required steps:
                            1. Call cv_search to enumerate every reference to `%s` (classes, methods, imports).
                            2. Call cv_impact on each high-usage reference to estimate per-call-site effort.
                            3. Call cv_test_impact to identify which tests will need to change.
                            4. Read cvector://infrastructure to check whether `%s` is part of the external surface
                               (config keys, env vars, container images, dependencies) that needs deprecation messaging.

                            Produce a migration plan with:
                            - Inventory: total touch points grouped by module
                            - Sequencing: 3–5 phases in dependency order, with rollback criteria for each
                            - Risk register: 5–10 entries scored (likelihood × impact) with mitigations
                            - Test additions needed before the migration starts
                            - Rollout: feature-flag strategy, canary plan, and a single "no-go" signal that pauses the migration
                            - Estimated effort in eng-days
                            """.formatted(
                            from, to,
                            scope.isEmpty() ? "" : " (scope: " + scope + ")",
                            from, from);
                },
                "Migration plan");
    }

    private static McpServerFeatures.SyncPromptSpecification infrastructurePrompt() {
        return prompt(
                "cvector-infrastructure",
                "Audit all infrastructure: queues, metrics, events, external APIs, env vars, IaC resources.",
                List.of(),
                args -> """
                        You are mapping the infrastructure surface. Use the cvector MCP server.

                        Required steps:
                        1. Read cvector://infrastructure for the full snapshot (endpoints, listeners, scheduled jobs,
                           config keys, env vars, container images, ports, Terraform/Bicep resources).
                        2. Call cv_service_links for cross-service dependencies (queues, event buses, HTTP).
                        3. Call cv_flows from each REST endpoint and queue listener to see which internal code paths
                           are reachable from the outside.

                        Produce an infrastructure audit with:
                        - Inbound surface table: protocol, endpoint, handler method, auth assumptions
                        - Outbound surface table: external service, call site(s), retry/timeout posture (if known)
                        - Configuration table: every env var + config key, where it is read, and the default
                        - Cloud / IaC: list of declared resources grouped by provider, with cross-resource USES edges
                        - Risk callouts: any endpoint with no auth-related token in the call path, any env var
                          with no default that crashes startup, any orphaned IaC resource
                        """,
                "Infrastructure audit");
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
