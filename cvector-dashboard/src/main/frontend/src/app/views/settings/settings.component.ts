import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { ThemeService } from '../../core/theme.service';

type Backend = 'embedded' | 'remote' | 'docker';
/**
 * MCP transport options surfaced in the dashboard. The canonical user-facing values are
 * 'http' (MCP 2025-03-26 Streamable HTTP), 'sse' (MCP 2024-11-05 HTTP+SSE), and 'stdio'.
 * 'streamable' is accepted on the wire as a legacy alias for 'http' — the backend's
 * canonicalTransport() folds it back so the UI always sees the canonical name.
 */
type Transport = 'http' | 'sse' | 'stdio' | 'streamable';

interface RestSection { port: number; host: string; }
/**
 * MCP session timeout knobs writable from settings.json. Every field is independent
 * and `null` means "don't override" — the JVM keeps the application-mcp default.
 *  - requestTimeoutMs      → spring.ai.mcp.server.request-timeout
 *  - keepAliveIntervalMs   → spring.ai.mcp.server.streamable-http.keep-alive-interval (http)
 *                            or spring.ai.mcp.server.keep-alive-interval (sse)
 *  - asyncRequestTimeoutMs → spring.mvc.async.request-timeout (-1 = no SSE eviction)
 */
interface McpTimeoutsSection {
  requestTimeoutMs: number | null;
  keepAliveIntervalMs: number | null;
  asyncRequestTimeoutMs: number | null;
}
interface McpSection { url: string; transport: Transport; timeouts?: McpTimeoutsSection | null; }
interface DockerSection {
  image: string;
  containerName: string;
  neo4jVersion: string;
  boltPort: number;
  httpPort: number;
}
interface Neo4jSection { uri: string; user: string; password: string; }

interface SettingsResponse {
  readonly path: string;
  readonly activeProject?: string;
  readonly backend: Backend;
  readonly rest: RestSection;
  readonly mcp: McpSection;
  readonly docker: DockerSection;
  readonly neo4j: Neo4jSection | null;
}

interface SettingsPatch {
  backend?: Backend;
  rest?: Partial<RestSection>;
  mcp?: Partial<McpSection>;
  docker?: Partial<DockerSection>;
  neo4j?: Partial<Neo4jSection>;
}

@Component({
  selector: 'cv-settings',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Settings</h1>
        <div class="cv-page-subtitle">
          Persisted in
          @if (data(); as d) { <code>{{ d.path }}</code> } @else { settings.json }
        </div>
      </div>
      <div class="d-flex gap-2 align-items-center">
        @if (restartRequired() && !restarting()) {
          <button class="btn btn-sm btn-warning" (click)="restartServer()">
            <i class="bi bi-arrow-clockwise"></i>
            Restart required — click to restart
          </button>
        }
        @if (restarting()) {
          <span class="badge text-bg-warning">
            <i class="bi bi-arrow-repeat"></i>
            Restarting… {{ restartElapsed() }}s
          </span>
        }
        @if (restartError()) {
          <span class="badge text-bg-danger" [title]="restartError()">
            <i class="bi bi-exclamation-octagon"></i>
            Restart failed
          </span>
        }
        @if (saved()) {
          <span class="badge text-bg-success">
            <i class="bi bi-check2"></i> Saved
          </span>
        }
      </div>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      <div class="row g-3">
        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-3">Backend</h2>
            <div class="mb-3">
              <label class="form-label small text-secondary">Graph store</label>
              <select class="form-select" [(ngModel)]="backend" name="backend">
                <option value="embedded">Embedded KuzuDB (default — no setup)</option>
                <option value="remote">Remote Neo4j (bolt://)</option>
                <option value="docker">Docker-managed Neo4j</option>
              </select>
            </div>
            @if (backend() === 'remote') {
              <div class="mb-3">
                <label class="form-label small text-secondary">Neo4j URI</label>
                <input class="form-control font-monospace" [(ngModel)]="neoUri" name="neoUri" />
              </div>
              <div class="row g-2 mb-3">
                <div class="col-md-6">
                  <label class="form-label small text-secondary">User</label>
                  <input class="form-control" [(ngModel)]="neoUser" name="neoUser" />
                </div>
                <div class="col-md-6">
                  <label class="form-label small text-secondary">
                    Password
                    <span class="text-secondary" title="Leave as *** to keep the existing password.">
                      <i class="bi bi-info-circle"></i>
                    </span>
                  </label>
                  <input class="form-control" type="password" [(ngModel)]="neoPassword" name="neoPassword" />
                </div>
              </div>
            }
            @if (backend() === 'docker') {
              <div class="row g-2 mb-3">
                <div class="col-md-7">
                  <label class="form-label small text-secondary">Image</label>
                  <input class="form-control font-monospace" [(ngModel)]="dockerImage" name="dockerImage" />
                </div>
                <div class="col-md-5">
                  <label class="form-label small text-secondary">Neo4j tag</label>
                  <input class="form-control font-monospace" [(ngModel)]="dockerVersion" name="dockerVersion" />
                </div>
              </div>
              <div class="row g-2 mb-3">
                <div class="col-md-6">
                  <label class="form-label small text-secondary">Container name</label>
                  <input class="form-control" [(ngModel)]="dockerContainer" name="dockerContainer" />
                </div>
                <div class="col-md-3">
                  <label class="form-label small text-secondary">Bolt port</label>
                  <input class="form-control" type="number" [(ngModel)]="dockerBolt" name="dockerBolt" />
                </div>
                <div class="col-md-3">
                  <label class="form-label small text-secondary">HTTP port</label>
                  <input class="form-control" type="number" [(ngModel)]="dockerHttp" name="dockerHttp" />
                </div>
              </div>
            }
            <button class="btn cv-bg-accent btn-sm" (click)="saveBackend()">Save backend</button>
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-3">REST / dashboard listener</h2>
            <div class="row g-2 mb-3">
              <div class="col-md-7">
                <label class="form-label small text-secondary">Bind host</label>
                <select class="form-select" [(ngModel)]="restHost" name="restHost">
                  <option value="127.0.0.1">127.0.0.1 (localhost only — default)</option>
                  <option value="0.0.0.0">0.0.0.0 (any interface)</option>
                  <option value="localhost">localhost</option>
                </select>
              </div>
              <div class="col-md-5">
                <label class="form-label small text-secondary">Port</label>
                <input class="form-control" type="number" min="1" max="65535"
                       [(ngModel)]="restPort" name="restPort" />
              </div>
            </div>
            @if (restHost() === '0.0.0.0') {
              <div class="alert alert-warning small p-2 mb-3">
                <i class="bi bi-exclamation-triangle"></i>
                Switching to <code>0.0.0.0</code> exposes the API to anything on the
                network. cvector has no auth; put it behind a firewall or VPN.
              </div>
            }
            <button class="btn cv-bg-accent btn-sm" (click)="saveRest()">Save listener</button>
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-3">MCP server</h2>
            <div class="mb-3">
              <label class="form-label small text-secondary">Transport</label>
              <select class="form-select" [(ngModel)]="mcpTransport" name="mcpTransport">
                <option value="http">http (default — MCP 2025-03-26 Streamable HTTP; Eclipse Copilot, Inspector v2, newer Claude)</option>
                <option value="sse">sse (legacy — MCP 2024-11-05 HTTP+SSE; original Claude Desktop, MCP Inspector v1)</option>
                <option value="stdio">stdio (subprocess JSON-RPC — use 'cvector serve' on the client side)</option>
              </select>
            </div>
            @if (mcpTransport() === 'stdio') {
              <div class="alert alert-info small p-2 mb-3">
                <i class="bi bi-info-circle"></i>
                stdio can't run alongside the dashboard from one terminal — the JSON-RPC
                reader would race the user for stdin. The dashboard will boot without an
                HTTP MCP transport; clients should spawn <code>cvector serve</code> as a
                subprocess instead.
              </div>
            }
            <div class="mb-3">
              <label class="form-label small text-secondary">URL</label>
              <input class="form-control font-monospace" [(ngModel)]="mcpUrl" name="mcpUrl" />
              <div class="form-text small">
                @if (mcpTransport() === 'http') {
                  Single endpoint; session via the <code>Mcp-Session-Id</code> header. Default path: <code>/mcp</code>.
                } @else if (mcpTransport() === 'sse') {
                  GET <code>/sse</code> opens the stream; POSTs go to <code>/mcp/message?sessionId=…</code>.
                } @else {
                  Informational only — stdio clients spawn the cvector subprocess directly.
                }
              </div>
            </div>
            <button class="btn cv-bg-accent btn-sm" (click)="saveMcp()">Save MCP</button>
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-3">MCP session timeouts <span class="badge text-bg-secondary fw-normal">advanced</span></h2>
            <p class="small text-secondary mb-3">
              All fields are optional. Leave blank to keep the cvector defaults
              pinned in <code>application-mcp.properties</code>. Restart required.
            </p>
            <div class="mb-3">
              <label class="form-label small text-secondary">Request timeout (ms)</label>
              <input class="form-control" type="number" min="0" placeholder="default ~20000"
                     [ngModel]="mcpRequestTimeoutMs()" (ngModelChange)="mcpRequestTimeoutMs.set($event)"
                     name="mcpRequestTimeoutMs" />
              <div class="form-text small">
                Per-tool-call ceiling. Wired to <code>spring.ai.mcp.server.request-timeout</code>.
              </div>
            </div>
            <div class="mb-3">
              <label class="form-label small text-secondary">Keep-alive interval (ms)</label>
              <input class="form-control" type="number" min="0" placeholder="default disabled"
                     [ngModel]="mcpKeepAliveIntervalMs()" (ngModelChange)="mcpKeepAliveIntervalMs.set($event)"
                     name="mcpKeepAliveIntervalMs" />
              <div class="form-text small">
                Server → client ping interval. Helps when load balancers / proxies drop idle
                connections. Wired to
                @if (mcpTransport() === 'sse') {
                  <code>spring.ai.mcp.server.keep-alive-interval</code>.
                } @else {
                  <code>spring.ai.mcp.server.streamable-http.keep-alive-interval</code>.
                }
              </div>
            </div>
            <div class="mb-3">
              <label class="form-label small text-secondary">Async stream timeout (ms)</label>
              <input class="form-control" type="number" placeholder="default -1 (no eviction)"
                     [ngModel]="mcpAsyncRequestTimeoutMs()" (ngModelChange)="mcpAsyncRequestTimeoutMs.set($event)"
                     name="mcpAsyncRequestTimeoutMs" />
              <div class="form-text small">
                How long Spring MVC keeps an idle SSE / Streamable-HTTP stream open before
                evicting the session. <code>-1</code> = forever. Wired to
                <code>spring.mvc.async.request-timeout</code>.
              </div>
            </div>
            <button class="btn cv-bg-accent btn-sm" (click)="saveMcpTimeouts()">Save timeouts</button>
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-3">Appearance</h2>
            <div class="mb-3">
              <label class="form-label small text-secondary d-block">Theme</label>
              <div class="btn-group" role="group">
                <button class="btn btn-outline-secondary"
                        [class.active]="theme.theme() === 'light'"
                        (click)="theme.set('light')">
                  <i class="bi bi-sun"></i> Light
                </button>
                <button class="btn btn-outline-secondary"
                        [class.active]="theme.theme() === 'dark'"
                        (click)="theme.set('dark')">
                  <i class="bi bi-moon-stars"></i> Dark
                </button>
              </div>
            </div>
            <p class="small text-secondary mb-0">
              Theme is stored in the browser only — it doesn't write to settings.json.
            </p>
          </div>
        </div>
      </div>

      <p class="small text-secondary mt-3 mb-0">
        Writes go through <code>PUT /api/settings</code> and merge with the on-disk
        config. After saving REST host/port, MCP transport, or backend changes you must
        restart the dashboard / serve process.
      </p>
    } @else if (!error()) {
      <div class="cv-surface text-center py-5 text-secondary">Loading settings…</div>
    }
  `,
})
export class SettingsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  readonly theme = inject(ThemeService);

  readonly data = signal<SettingsResponse | null>(null);
  readonly error = signal('');
  readonly restartRequired = signal(false);
  readonly restarting = signal(false);
  readonly restartError = signal('');
  readonly restartElapsed = signal(0);
  readonly saved = signal(false);

  readonly backend = signal<Backend>('embedded');
  readonly restHost = signal<string>('127.0.0.1');
  readonly restPort = signal<number>(2969);
  readonly mcpUrl = signal<string>('');
  readonly mcpTransport = signal<Transport>('http');
  // null = field omitted from settings.json → use cvector default. Empty input clears
  // back to null so users can drop a previously-set override with no friction.
  readonly mcpRequestTimeoutMs = signal<number | null>(null);
  readonly mcpKeepAliveIntervalMs = signal<number | null>(null);
  readonly mcpAsyncRequestTimeoutMs = signal<number | null>(null);
  readonly dockerImage = signal<string>('neo4j');
  readonly dockerVersion = signal<string>('5');
  readonly dockerContainer = signal<string>('cvector-neo4j');
  readonly dockerBolt = signal<number>(7687);
  readonly dockerHttp = signal<number>(7474);
  readonly neoUri = signal<string>('bolt://localhost:7687');
  readonly neoUser = signal<string>('neo4j');
  readonly neoPassword = signal<string>('***');

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.http
      .get<SettingsResponse>('/api/settings')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (d) => {
          this.data.set(d);
          this.hydrate(d);
        },
        error: (err) => this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load settings'),
      });
  }

  private hydrate(d: SettingsResponse): void {
    this.backend.set(d.backend);
    this.restHost.set(d.rest?.host ?? '127.0.0.1');
    this.restPort.set(d.rest?.port ?? 2969);
    this.mcpUrl.set(d.mcp?.url ?? '');
    // Map legacy 'streamable' (briefly written during the Spring AI 2.0 transition) to
    // its canonical 'http' name so the <select> shows the right option. The backend
    // already canonicalises on write; this covers reads from intermediate-state files
    // that still carry 'streamable' verbatim.
    const incoming = (d.mcp?.transport ?? 'http') as Transport;
    this.mcpTransport.set(incoming === 'streamable' ? 'http' : incoming);
    const t = d.mcp?.timeouts;
    this.mcpRequestTimeoutMs.set(t?.requestTimeoutMs ?? null);
    this.mcpKeepAliveIntervalMs.set(t?.keepAliveIntervalMs ?? null);
    this.mcpAsyncRequestTimeoutMs.set(t?.asyncRequestTimeoutMs ?? null);
    this.dockerImage.set(d.docker?.image ?? 'neo4j');
    this.dockerVersion.set(d.docker?.neo4jVersion ?? '5');
    this.dockerContainer.set(d.docker?.containerName ?? 'cvector-neo4j');
    this.dockerBolt.set(d.docker?.boltPort ?? 7687);
    this.dockerHttp.set(d.docker?.httpPort ?? 7474);
    this.neoUri.set(d.neo4j?.uri ?? 'bolt://localhost:7687');
    this.neoUser.set(d.neo4j?.user ?? 'neo4j');
    this.neoPassword.set(d.neo4j?.password ?? '***');
  }

  saveBackend(): void {
    const patch: SettingsPatch = { backend: this.backend() };
    if (this.backend() === 'remote') {
      patch.neo4j = {
        uri: this.neoUri(),
        user: this.neoUser(),
        // Sending *** preserves the existing password server-side.
        password: this.neoPassword(),
      };
    } else if (this.backend() === 'docker') {
      patch.docker = {
        image: this.dockerImage(),
        neo4jVersion: this.dockerVersion(),
        containerName: this.dockerContainer(),
        boltPort: this.dockerBolt(),
        httpPort: this.dockerHttp(),
      };
    }
    this.put(patch);
  }

  saveRest(): void {
    this.put({ rest: { host: this.restHost(), port: this.restPort() } });
  }

  saveMcp(): void {
    this.put({ mcp: { url: this.mcpUrl(), transport: this.mcpTransport() } });
  }

  /**
   * Persist just the timeouts sub-section. Empty inputs become `null`s in the patch so
   * clearing a field rolls the JVM back to the cvector default rather than persisting a
   * meaningless zero.
   */
  saveMcpTimeouts(): void {
    const timeouts: McpTimeoutsSection = {
      requestTimeoutMs: nullIfBlank(this.mcpRequestTimeoutMs()),
      keepAliveIntervalMs: nullIfBlank(this.mcpKeepAliveIntervalMs()),
      asyncRequestTimeoutMs: nullIfBlank(this.mcpAsyncRequestTimeoutMs()),
    };
    this.put({ mcp: { timeouts } });
  }

  private put(patch: SettingsPatch): void {
    this.error.set('');
    this.saved.set(false);
    this.http
      .put<SettingsResponse & { restartRequired: boolean }>('/api/settings', patch)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (d) => {
          this.data.set(d);
          this.hydrate(d);
          this.saved.set(true);
          if (d.restartRequired) this.restartRequired.set(true);
          setTimeout(() => this.saved.set(false), 2500);
        },
        error: (err) => this.error.set(err?.error?.message ?? err?.message ?? 'Failed to save settings'),
      });
  }

  /**
   * POST /api/dashboard/restart, then poll /api/health until the new JVM is reachable and
   * reload the page. The server spawns a detached replacement before shutting itself down,
   * so the URL stays the same — just need to wait for the new process to bind the port.
   */
  restartServer(): void {
    this.restarting.set(true);
    this.restartError.set('');
    this.restartElapsed.set(0);
    const startedAt = Date.now();
    this.http.post<{ ok: boolean; reason?: string; message?: string }>(
      '/api/dashboard/restart', null,
    ).subscribe({
      next: () => this.pollForRecovery(startedAt),
      error: (err) => {
        // Spring sends the 202 response then exits; the HttpClient may surface the dropped
        // connection as an error AFTER the response was actually received. If we got that
        // far, treat it as a successful kickoff and start polling.
        if (err?.status === 0 || err?.status === 202) {
          this.pollForRecovery(startedAt);
          return;
        }
        this.restarting.set(false);
        const reason = err?.error?.reason ?? err?.error?.message ?? err?.message;
        this.restartError.set('Restart failed: ' + (reason ?? 'server error'));
      },
    });
  }

  private pollForRecovery(startedAt: number): void {
    // Poll every 500 ms for up to 45 s. The new JVM typically binds the port within 5-10 s
    // on a warm OS file cache; allow generous slack for cold boots / antivirus scans.
    const intervalMs = 500;
    const maxMs = 45_000;
    const tick = () => {
      const elapsed = Date.now() - startedAt;
      this.restartElapsed.set(Math.round(elapsed / 1000));
      if (elapsed > maxMs) {
        this.restarting.set(false);
        this.restartError.set('Restart timed out — check the terminal');
        return;
      }
      // Wait at least 1 s before the first probe so the old JVM has a chance to release
      // the port (otherwise the probe hits the dying old server and returns 200 falsely).
      if (elapsed < 1500) {
        setTimeout(tick, intervalMs);
        return;
      }
      fetch('/api/health', { cache: 'no-store' })
        .then((r) => (r.ok ? r.text() : Promise.reject(new Error('not-ok'))))
        .then(() => {
          // Reload to pick up any frontend bundle changes shipped by the new JVM and to
          // re-fetch all view data fresh.
          window.location.reload();
        })
        .catch(() => setTimeout(tick, intervalMs));
    };
    setTimeout(tick, 500);
  }
}

/**
 * An empty <input type="number"> emits `null` on Angular's two-way binding, but a blank
 * <input type="text"> can also surface as a literal empty string. Normalise both to `null`
 * so the timeouts PATCH transmits "no override" cleanly instead of an unparseable empty
 * string (which the SettingsController's `toLong` would reject anyway, but explicit is
 * better than relying on fallback semantics).
 */
function nullIfBlank(v: number | null | undefined | string): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}
