import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { visiblePoll } from '../../core/poll';
import { EventStreamService } from '../../core/event-stream.service';

import { ApiService } from '../../core/api.service';

interface ScanStatus {
  readonly running: boolean;
  readonly pid?: number;
  readonly startedAt?: string;
  readonly elapsedMillis?: number;
  readonly action?: string;
  readonly lastFinishedAt?: string;
  readonly lastExitCode?: number;
}

interface LangRow      { readonly language: string;  readonly files: number; }
interface ClassRow     { readonly fqName: string;    readonly methods: number; }
interface EndpointRow  { readonly method?: string;   readonly path?: string;
                         readonly framework?: string; readonly file?: string;
                         readonly handler?: string; }
interface TableRow     { readonly table: string;     readonly columns: number; }
interface ConfigRow    { readonly key: string;       readonly value?: unknown; }
interface EnvRow       { readonly name: string;      readonly value?: unknown; }
interface HubRow       { readonly fqName: string;    readonly outDeg?: number;
                         readonly inDeg?: number;    readonly total?: number; }
interface MavenRow     { readonly groupId: string;   readonly artifactId: string;
                         readonly version?: string;  readonly scope?: string; }

interface OnboardResponse {
  readonly project: { projectId: string; name: string; rootPath: string };
  readonly totals: { nodes: number; edges: number };
  readonly languages: ReadonlyArray<LangRow>;
  readonly topClasses: ReadonlyArray<ClassRow>;
  readonly restEndpoints: ReadonlyArray<EndpointRow>;
  readonly tables: ReadonlyArray<TableRow>;
  readonly configKeys: ReadonlyArray<ConfigRow>;
  readonly envVars: ReadonlyArray<EnvRow>;
  readonly callGraphHubs: ReadonlyArray<HubRow>;
  readonly mavenDependencies: ReadonlyArray<MavenRow>;
}

@Component({
  selector: 'cv-overview',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Overview</h1>
        <div class="cv-page-subtitle">
          @if (data(); as d) {
            {{ d.project.name }} · {{ d.project.rootPath }}
          } @else {
            cvector project briefing
          }
        </div>
      </div>
      <div class="d-flex gap-2 align-items-center">
        @if (scanStatus(); as s) {
          @if (s.running) {
            <span class="badge cv-bg-accent">
              <i class="bi bi-arrow-repeat"></i>
              Scanning ({{ s.action }}, {{ ((s.elapsedMillis ?? 0) / 1000) | number: '1.0-0' }}s)
            </span>
          } @else if (s.lastFinishedAt) {
            <span class="badge bg-secondary" [title]="'last scan: ' + s.lastFinishedAt">
              <i class="bi" [class]="s.lastExitCode === 0 ? 'bi-check-circle' : 'bi-exclamation-circle'"></i>
              Last scan {{ s.lastExitCode === 0 ? 'OK' : 'exit ' + s.lastExitCode }}
            </span>
          }
        }
        <span class="badge" [class]="status() ? 'cv-bg-accent' : 'bg-secondary'">
          {{ status() ? 'Backend online' : 'Probing…' }}
        </span>
        <div class="btn-group">
          <button class="btn btn-sm cv-bg-accent"
                  [disabled]="(scanStatus()?.running ?? false) || launching()"
                  (click)="runScan('scan-incremental')">
            <i class="bi" [class]="launching() ? 'bi-arrow-repeat' : 'bi-play-fill'"></i>
            Incremental scan
          </button>
          <button class="btn btn-sm btn-outline-secondary"
                  [disabled]="(scanStatus()?.running ?? false) || launching()"
                  (click)="runScan('scan')"
                  title="Full scan (slower but comprehensive)">
            <i class="bi bi-arrow-clockwise"></i>
          </button>
        </div>
      </div>
    </div>

    @if (scanError()) {
      <div class="alert alert-warning small">{{ scanError() }}</div>
    }

    @if (data(); as d) {
      <div class="row g-3 mb-3">
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Total nodes</div>
            <div class="fs-4 fw-semibold">{{ d.totals.nodes | number }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Total edges</div>
            <div class="fs-4 fw-semibold">{{ d.totals.edges | number }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">REST endpoints</div>
            <div class="fs-4 fw-semibold">{{ d.restEndpoints.length }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Maven deps</div>
            <div class="fs-4 fw-semibold">{{ d.mavenDependencies.length }}</div>
          </div>
        </div>
      </div>

      <div class="row g-3">
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">Languages</h2>
            @if (d.languages.length === 0) {
              <div class="text-secondary small">No data yet.</div>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <tbody>
                  @for (row of d.languages; track row.language) {
                    <tr class="cv-language-row" (click)="drillLanguage(row.language)" style="cursor:pointer">
                      <td><span class="badge bg-secondary">{{ row.language || '?' }}</span></td>
                      <td class="text-end font-monospace small">{{ row.files | number }} file(s)</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>

        <div class="col-md-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">Top classes by method count</h2>
            @if (d.topClasses.length === 0) {
              <div class="text-secondary small">No data yet.</div>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <tbody>
                  @for (row of d.topClasses; track row.fqName) {
                    <tr>
                      <td class="font-monospace small text-truncate" style="max-width:24rem;"
                          [title]="row.fqName">{{ row.fqName }}</td>
                      <td class="text-end font-monospace small">{{ row.methods }}</td>
                      <td class="text-end" style="width:3rem">
                        <a class="btn btn-sm btn-link p-0 text-secondary"
                           [routerLink]="['/graph']"
                           [queryParams]="{ symbol: row.fqName }"
                           title="View call graph">
                          <i class="bi bi-diagram-3"></i>
                        </a>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">REST endpoints <span class="text-secondary fw-normal small">{{ d.restEndpoints.length }}</span></h2>
            @if (d.restEndpoints.length === 0) {
              <div class="text-secondary small">None found.</div>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <thead><tr><th>Method</th><th>Path</th><th>Framework</th></tr></thead>
                <tbody>
                  @for (row of d.restEndpoints.slice(0, 15); track $index) {
                    <tr>
                      <td><span class="badge bg-secondary">{{ row.method || '?' }}</span></td>
                      <td class="font-monospace small">{{ row.path }}</td>
                      <td class="small text-secondary">{{ row.framework }}</td>
                    </tr>
                  }
                </tbody>
              </table>
              @if (d.restEndpoints.length > 15) {
                <div class="small text-secondary mt-2">+{{ d.restEndpoints.length - 15 }} more</div>
              }
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">Call-graph hubs</h2>
            @if (d.callGraphHubs.length === 0) {
              <div class="text-secondary small">No CALLS edges in this graph yet.</div>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <thead><tr><th>Method</th><th class="text-end">in / out</th><th></th></tr></thead>
                <tbody>
                  @for (row of d.callGraphHubs.slice(0, 10); track row.fqName) {
                    <tr>
                      <td class="font-monospace small text-truncate" style="max-width:24rem;"
                          [title]="row.fqName">{{ row.fqName }}</td>
                      <td class="text-end font-monospace small text-secondary">
                        {{ row.inDeg ?? 0 }} / {{ row.outDeg ?? 0 }}
                      </td>
                      <td class="text-end" style="width:3rem">
                        <a class="btn btn-sm btn-link p-0 text-secondary"
                           [routerLink]="['/graph']"
                           [queryParams]="{ symbol: row.fqName }">
                          <i class="bi bi-diagram-3"></i>
                        </a>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>

        @if (d.mavenDependencies.length > 0) {
          <div class="col-12">
            <div class="cv-surface">
              <h2 class="fs-6 fw-semibold mb-2">Maven dependencies <span class="text-secondary fw-normal small">{{ d.mavenDependencies.length }}</span></h2>
              <table class="table table-sm mb-0">
                <thead><tr><th>Group</th><th>Artifact</th><th>Version</th><th>Scope</th></tr></thead>
                <tbody>
                  @for (row of d.mavenDependencies.slice(0, 25); track $index) {
                    <tr>
                      <td class="font-monospace small">{{ row.groupId }}</td>
                      <td class="font-monospace small">{{ row.artifactId }}</td>
                      <td class="font-monospace small text-secondary">{{ row.version }}</td>
                      <td><span class="badge bg-secondary">{{ row.scope }}</span></td>
                    </tr>
                  }
                </tbody>
              </table>
              @if (d.mavenDependencies.length > 25) {
                <div class="small text-secondary mt-2">+{{ d.mavenDependencies.length - 25 }} more</div>
              }
            </div>
          </div>
        }
      </div>
    } @else {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">
          {{ error() || 'Loading project briefing…' }}
        </div>
      </div>
    }
  `,
})
export class OverviewComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stream = inject(EventStreamService);

  readonly data = signal<OnboardResponse | null>(null);
  readonly status = signal(false);
  readonly error = signal('');
  readonly scanStatus = signal<ScanStatus | null>(null);
  readonly launching = signal(false);
  readonly scanError = signal('');

  ngOnInit(): void {
    this.api
      .health()
      .pipe(takeUntilDestroyed(this.destroyRef), catchError(() => of(null)))
      .subscribe((res) => this.status.set(!!res));

    this.loadOnboard();
    this.refreshScanStatus();

    // SSE: refresh scan status the instant the backend tells us the runner state changed.
    // We still poll on a slower cadence as a fallback in case the EventSource drops.
    this.stream.on('scan-status')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshScanStatus());
    // graph-mutated arrives when a scan or schedule commits new data; reload the onboard
    // briefing so the language/class counts reflect the post-scan graph.
    this.stream.on('graph-mutated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.loadOnboard());
    // Fallback poll: longer interval than before since SSE handles the hot path. Still
    // useful when the SSE connection itself is the thing that broke.
    visiblePoll(15000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshScanStatus());
  }

  private loadOnboard(): void {
    this.http.get<OnboardResponse>('/api/onboard').subscribe({
      next: (d) => this.data.set(d),
      error: (err) => this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load briefing'),
    });
  }

  refreshScanStatus(): void {
    this.http.get<ScanStatus>('/api/dashboard/scans/status').subscribe({
      next: (s) => {
        const prev = this.scanStatus();
        this.scanStatus.set(s);
        // Detect scan-just-finished and reload the briefing so totals/hubs reflect the
        // new graph state without a manual refresh.
        if (prev?.running && !s.running) this.loadOnboard();
      },
      error: () => {},
    });
  }

  runScan(action: 'scan' | 'scan-incremental'): void {
    this.launching.set(true);
    this.scanError.set('');
    this.http.post<unknown>('/api/dashboard/scans', { action }).subscribe({
      next: () => {
        this.launching.set(false);
        this.refreshScanStatus();
      },
      error: (err) => {
        const msg = err?.error?.reason
          ? `Scan not started: ${err.error.reason}`
          : (err?.message ?? 'Failed to start scan');
        this.scanError.set(msg);
        this.launching.set(false);
      },
    });
  }

  /** Clicking a language row jumps to the Query view with a pre-filled cypher. */
  drillLanguage(language: string): void {
    if (!language) return;
    const cypher = `MATCH (f:Node) WHERE f.label = 'File' AND f.language = '${language}' RETURN f.path AS path, f.lineCount AS lines ORDER BY lines DESC LIMIT 25`;
    this.router.navigate(['/query'], { queryParams: { cypher } });
  }
}
