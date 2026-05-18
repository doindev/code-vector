# cvector-dashboard

Optional Angular 17 + Bootstrap 5 SPA for cvector. Bundled into the cvector fat-jar so `cvector dashboard --open` serves it on `http://localhost:2969/dashboard`.

The dashboard process **co-hosts the MCP server** in the same Spring Boot JVM (one Kuzu/Neo4j handle shared across REST + MCP). The active MCP transport is configurable via `settings.json` — see [MCP co-host](#mcp-co-host) below.

## Layout

```
cvector-dashboard/
├── pom.xml                          # Maven module; activates frontend-maven-plugin under the `dashboard-ui` profile
├── src/main/java/.../dashboard/
│   ├── DashboardConfiguration.java  # Spring MVC: serves classpath:/static/dashboard/** with SPA fallback to index.html
│   └── DashboardStore.java          # Persistence for dashboard-only state (monitors, schedules)
├── src/main/resources/static/dashboard/
│   └── index.html                   # Placeholder when the Angular build hasn't run
└── src/main/frontend/               # Angular project (Angular 17, standalone components, signal-based state)
    ├── package.json                 # ng build outputs into ../resources/static/dashboard/
    ├── angular.json
    ├── tsconfig.json / tsconfig.app.json
    ├── proxy.conf.json              # /api/* -> http://localhost:2969 during ng serve
    └── src/
        ├── index.html
        ├── main.ts
        ├── styles.scss              # Bootstrap 5 + dark/light CSS-var theming
        └── app/
            ├── app.config.ts        # provideRouter, provideHttpClient
            ├── app.routes.ts        # 28 lazy-loaded views
            ├── app.component.ts     # <router-outlet/>
            ├── core/
            │   ├── theme.service.ts            # data-bs-theme on <html>, localStorage persistence
            │   ├── api.service.ts              # HttpClient wrapper for /api/*
            │   ├── event-stream.service.ts     # SSE consumer (scan progress, etc.)
            │   ├── cypher-format.ts            # Cypher pretty-printer
            │   ├── cypher-ref.service.ts       # Shared cypher reference state
            │   └── poll.ts                     # Polling helper (job-status, restart-recovery)
            ├── layout/
            │   ├── shell.component.ts/.scss    # Sidebar nav + main content
            │   ├── command-palette.component   # Cmd-K command palette
            │   └── cypher-ref-panel.component  # Inline Cypher reference drawer
            ├── shared/
            │   └── folder-picker.component
            └── views/                          # See the routes table below
```

## Build

The dashboard build is opt-in. A plain `mvn package` produces a backend-only fat-jar (no Node.js install required). Activate the `dashboard-ui` profile to pull in the Angular build:

```bash
# From the repo root
mvn -P dashboard-ui clean package

# To also produce a standalone cvector.exe with the dashboard bundled in,
# combine with the dist profile and run through the verify phase (install):
mvn -pl cvector-app -am -Pdashboard-ui,dist -DskipTests install
```

What happens:

1. `frontend-maven-plugin` downloads a pinned Node 20 + npm 10 into `cvector-dashboard/target/node-install/` (sandboxed; doesn't touch your system Node).
2. `npm install` resolves `package.json`.
3. `npm run build` runs `ng build --configuration production --base-href /dashboard/ --output-path=../resources/static/dashboard --delete-output-path`, which drops the built assets where Spring's static-resource handler picks them up.
4. `cvector-app` (with the matching `dashboard-ui` profile flag) depends on `cvector-dashboard`, so the JAR gets stitched into the fat-jar.

If your build machine blocks `frontend-maven-plugin` (corporate proxy / no npm download), build the SPA manually before `mvn package`:

```bash
cd cvector-dashboard/src/main/frontend
npm install
ng build --configuration production --base-href /dashboard/ \
   --output-path=../resources/static/dashboard --delete-output-path
cd ../../..
mvn -pl cvector-app -am package -DskipTests
```

## Run

```bash
java -jar cvector-app/target/cvector.jar dashboard --open
```

Output:

```
cvector dashboard running
  project:    code-vector (0d35...)
  backend:    kuzu (embedded)
  api:        http://localhost:2969/api
  dashboard:  http://localhost:2969/dashboard
  mcp:        http://localhost:2969/mcp  (transport: streamable)
...
opened http://localhost:2969/dashboard/ in default browser
```

## Dev workflow

For UI development, run Spring on 2969 (in another terminal) and start `ng serve` against the proxy config:

```bash
# Terminal 1
java -jar cvector-app/target/cvector.jar dashboard

# Terminal 2
cd cvector-dashboard/src/main/frontend
npm install
npm start    # ng serve on http://localhost:4200, proxies /api to 2969
```

`ng serve` rebuilds on save; the Spring backend stays alive across reloads.

## Routes

28 lazy-loaded views, all served under `/dashboard/`:

| Path | Backed by | Purpose |
|---|---|---|
| `/dashboard/` | `/api/dashboard/status`, `/api/health` | Overview — backend, active project, scan freshness, quality-gate verdict. |
| `/dashboard/projects` | `/api/projects` + project-lifecycle endpoints | List / switch / add / remove projects in the workspace. |
| `/dashboard/monitors` | `DashboardStore` | Monitored directories for the file-watcher. |
| `/dashboard/schedules` | `DashboardStore` | Cron schedules for re-scan. |
| `/dashboard/explorer` | `/api/search`, `/api/schema` | Substring search + schema viewer. |
| `/dashboard/query` | `/api/query` | Ad-hoc Cypher REPL (with `$pid` auto-bound). |
| `/dashboard/graph` | `/api/graph` (Cytoscape JSON) | Interactive graph view with layout switcher. |
| `/dashboard/explain` | `/api/explain` | Symbol context: declaration, callers, callees. |
| `/dashboard/impact` | `/api/impact` | Downstream blast-radius for a symbol. |
| `/dashboard/trace` | `/api/trace` | Multi-hop trace from a starting node. |
| `/dashboard/rename` | `/api/rename` | Rename impact: callers, refs, importing files. |
| `/dashboard/db-impact` | `/api/db-impact` | Methods that touch a given table/column. |
| `/dashboard/flows` | `/api/flows` | Reachable paths from REST/main/test entry points. |
| `/dashboard/services` | `/api/service-links` | Cross-service dependencies (HTTP, queues, exposed endpoints). |
| `/dashboard/communities` | `/api/communities` | Call-graph clusters (Louvain / Leiden / connected components). |
| `/dashboard/duplicates` | `/api/duplicates` | Cross-file duplicate / near-duplicate code. |
| `/dashboard/changelog` | `/api/changelog` | Recently-ingested nodes, formatted as a changelog. |
| `/dashboard/recent` | `/api/recent` | Most recently touched nodes. |
| `/dashboard/diff` | `/api/diff` (async + SSE) | Drift between two git commits. |
| `/dashboard/pr-impact` | `/api/pr-impact` (async + SSE) | Impact of an in-flight PR diff. |
| `/dashboard/migrate` | `/api/migrate` | Phased migration plan with risk register. |
| `/dashboard/wiki` | `/api/wiki` | Structured codebase wiki snapshot. |
| `/dashboard/audit` | `/api/audit` | Maven deps × OSV vulnerability DB. |
| `/dashboard/guard` | `/api/guard` | Quality-gate pass/fail snapshot. |
| `/dashboard/rules` | `/api/rules` | Rule violations grouped by severity. |
| `/dashboard/health` | `/api/health/rollup` | God-files, god-classes, long-methods, dead-code rollup. |
| `/dashboard/doctor` | `/api/doctor` | Setup diagnostics (config, backend connectivity, schema). |
| `/dashboard/settings` | `/api/settings` (GET + PUT) | Persisted config: backend, REST listener, **MCP transport**, theme. |

## MCP co-host

Started with `cvector dashboard`, the JVM also exposes an MCP server on the same Tomcat. Which transport gets wired is chosen by `mcp.transport` in `settings.json` (settable from the Settings view):

| Choice | Protocol | Default endpoint | Clients |
|---|---|---|---|
| `streamable` *(default)* | MCP 2025-03-26 — Streamable HTTP | `POST /mcp` + `Mcp-Session-Id` header | Eclipse Copilot, MCP Inspector v2, newer Claude |
| `sse` | MCP 2024-11-05 — HTTP+SSE | `GET /sse` then `POST /mcp/message?sessionId=…` | Original Claude Desktop, MCP Inspector v1 |
| `stdio` | JSON-RPC over stdin/stdout | n/a — dashboard skips co-hosting | Use `cvector serve` as a subprocess instead |

Switching transports in the Settings view writes `settings.json` and surfaces a "Restart required" banner — the actual transport wiring happens during Spring Boot startup, so a process restart is needed for the change to take effect. The view's restart button kicks off the in-place restart flow (`POST /api/dashboard/restart`).

## Theming

- Bootstrap 5.3+ first-class dark mode via `data-bs-theme` attribute on `<html>`.
- `ThemeService` (`core/theme.service.ts`) persists the user's choice in `localStorage` (`cv.theme`) and falls back to `prefers-color-scheme`.
- Accent colour controlled via the `--cv-accent` CSS variable in `styles.scss`.

## Settings UI

The Settings view (`/dashboard/settings`) is the single GUI surface for `settings.json`. Each section has its own Save button so changes ship one block at a time:

- **Backend.** Switch between `embedded` / `remote` / `docker`. Shows Neo4j credentials inline for remote and Docker image / port fields for docker. Password masking is enforced by the REST layer — sending the literal `"***"` back is a no-op.
- **REST listener.** Port + bind host. Warning banner appears if you flip `host` from `127.0.0.1` to `0.0.0.0` (off-loopback exposure has no built-in auth).
- **MCP server.** Transport dropdown (streamable / sse / stdio) + URL field. Helper text under the URL adjusts to the chosen transport (Streamable HTTP defaults to `/mcp`; SSE uses `/sse` + `/mcp/message`; stdio shows an info banner explaining it can't co-host with the dashboard).
- **Appearance.** Light / dark theme toggle (browser-only, not persisted to `settings.json`).

Any save that needs a restart sets a `restartRequired` flag in the response; the page shows a yellow banner with a "Restart required — click to restart" button that triggers the in-place restart flow and reloads once the new JVM is reachable.
