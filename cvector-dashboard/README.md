# cvector-dashboard

Optional Angular + Bootstrap dashboard for cvector. Bundled into the cvector fat jar so `cvector dashboard --open` serves it on `http://localhost:2969/dashboard`.

## Layout

```
cvector-dashboard/
├── pom.xml                          # Maven module; activates frontend-maven-plugin under the `dashboard-ui` profile
├── src/main/java/.../dashboard/
│   ├── DashboardConfiguration.java  # Spring MVC: serves classpath:/static/dashboard/** with SPA fallback to index.html
│   └── DashboardStatusController.java
├── src/main/resources/static/dashboard/
│   └── index.html                   # Placeholder when the Angular build hasn't run
└── src/main/frontend/               # Angular project (Angular 17, standalone components)
    ├── package.json                 # ng build outputs into ../resources/static/dashboard/
    ├── angular.json
    ├── tsconfig.json / tsconfig.app.json
    ├── proxy.conf.json              # /api/* -> http://localhost:2969 during ng serve
    └── src/
        ├── index.html
        ├── main.ts
        ├── styles.scss              # Bootstrap 5 + dark/light CSS-var theming
        └── app/
            ├── app.config.ts        # provideRouter + withHashLocation, provideHttpClient
            ├── app.routes.ts        # lazy-loads each view
            ├── app.component.ts     # <router-outlet/>
            ├── core/
            │   ├── theme.service.ts # data-bs-theme on <html>, localStorage persistence
            │   └── api.service.ts   # HttpClient wrapper for /api/*
            ├── layout/
            │   └── shell.component.ts/.scss  # Sidebar nav + main content
            └── views/
                ├── overview/
                ├── monitors/
                ├── schedules/
                ├── explorer/
                ├── query/
                ├── graph/           # Cytoscape.js sample
                └── settings/
```

## Build

The dashboard build is opt-in. A plain `mvn package` produces a backend-only fat jar (no Node.js install required). Activate the `dashboard-ui` profile to pull in the Angular build:

```bash
# From the repo root
mvn -P dashboard-ui clean package
```

What happens:

1. `frontend-maven-plugin` downloads a pinned Node 20 + npm 10 into `cvector-dashboard/target/node-install/` (sandboxed; doesn't touch your system Node).
2. `npm install` resolves `package.json`.
3. `npm run build` runs `ng build --configuration production --base-href /dashboard/ --output-path=../resources/static/dashboard --delete-output-path`, which drops the built assets where Spring's static-resource handler picks them up.
4. `cvector-app` (with the matching `dashboard-ui` profile flag) depends on `cvector-dashboard`, so the JAR gets stitched into the fat jar.

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

## Routes

| Path                  | View                  | Status |
| --------------------- | --------------------- | ------ |
| `/dashboard/`         | Overview              | live (calls `/api/dashboard/status`) |
| `/dashboard/monitors` | Monitored directories | scaffold (local state, backend pending) |
| `/dashboard/schedules`| Cron schedules        | scaffold (local state, backend pending) |
| `/dashboard/explorer` | Schema + search       | scaffold (uses `/api/search`; schema viewer pending) |
| `/dashboard/query`    | Cypher REPL           | scaffold (calls `/api/query`) |
| `/dashboard/graph`    | Cytoscape graph view  | scaffold (sample subgraph; live wiring pending) |
| `/dashboard/settings` | Settings              | scaffold (theme is live; backend persistence pending) |

## Theming

- Bootstrap 5.3+ first-class dark mode via `data-bs-theme` attribute on `<html>`.
- `ThemeService` (`core/theme.service.ts`) persists the user's choice in `localStorage` (`cv.theme`) and falls back to `prefers-color-scheme`.
- Accent colour controlled via the `--cv-accent` CSS variable in `styles.scss`.

## What's stubbed vs live

**Live**:
- The full SPA shell (routing, theme toggle, lazy view loading).
- `/api/dashboard/status` round-trip on the Overview page (proves backend reachability).
- Cytoscape canvas with a sample subgraph + layout switcher (`cose`, `grid`, `circle`, `concentric`, `breadthfirst`).
- `/api/search` integration on the Explorer view.
- `/api/query` integration on the Query view.

**Pending (intentionally scaffolded for follow-up iterations)**:
- Backend persistence for monitors, schedules, and settings — controllers + Spring Data repos.
- Schema viewer port from the Kuzu Explorer's `SchemaView/*` (Vue → Angular).
- Production graph endpoint that returns a Cytoscape-shaped JSON slice for any `Method` / `Class` symbol.
- Monaco editor integration for the Query view (currently a plain `<textarea>`).
- Node-click side panel on the Graph view.
- Query history (port from the explorer's session DB pattern, persisted via Spring Data JPA).
