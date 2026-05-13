import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';

interface Finding {
  readonly dependency: string;
  readonly id: string;
  readonly summary: string;
  readonly severity: string;
  readonly severityScore: number;
}

interface AuditSnapshot {
  readonly status: 'idle' | 'running' | 'done' | 'error';
  readonly startedAt?: string;
  readonly finishedAt?: string;
  readonly dependenciesInGraph: number;
  readonly scanned: number;
  readonly errors: number;
  readonly findings: ReadonlyArray<Finding>;
  readonly message?: string;
}

@Component({
  selector: 'cv-audit',
  standalone: true,
  imports: [DecimalPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Dependency audit</h1>
        <div class="cv-page-subtitle">
          OSV.dev vulnerability lookup for every Maven dependency in the graph
        </div>
      </div>
      <button class="btn btn-sm btn-primary"
              (click)="scan()"
              [disabled]="data()?.status === 'running'">
        @if (data()?.status === 'running') {
          <span class="spinner-border spinner-border-sm me-1"></span> Scanning…
        } @else {
          <i class="bi bi-shield-exclamation"></i> Run scan
        }
      </button>
    </div>

    @if (data(); as d) {
      <div class="cv-audit-banner cv-surface mb-3"
           [class.cv-audit-banner--ok]="d.status === 'done' && d.findings.length === 0"
           [class.cv-audit-banner--warn]="d.status === 'done' && d.findings.length > 0"
           [class.cv-audit-banner--err]="d.status === 'error'"
           [class.cv-audit-banner--run]="d.status === 'running'">
        <div class="d-flex align-items-center gap-3">
          <i class="bi" [class]="bannerIcon(d.status)" style="font-size: 2rem;"></i>
          <div class="flex-grow-1">
            <div class="fs-5 fw-semibold">{{ bannerTitle(d) }}</div>
            <div class="small text-secondary">
              @if (d.status === 'idle') {
                Click <em>Run scan</em> to query OSV for known vulnerabilities.
              } @else if (d.status === 'running') {
                Scanning {{ d.scanned | number }} of {{ d.dependenciesInGraph | number }} dependencies…
              } @else if (d.status === 'done') {
                Scanned {{ d.scanned | number }} of {{ d.dependenciesInGraph | number }} dependencies ·
                {{ d.findings.length | number }} finding(s)
                @if (d.errors > 0) { · {{ d.errors | number }} network error(s) }
                @if (d.finishedAt) { · finished {{ d.finishedAt | date: 'short' }} }
              } @else if (d.status === 'error') {
                {{ d.message }}
              }
            </div>
          </div>
        </div>
      </div>

      @if (d.status === 'done' && d.findings.length > 0) {
        <div class="cv-surface mb-3">
          <div class="row g-2 mb-2">
            @for (b of severityBuckets(); track b.name) {
              <div class="col">
                <div class="cv-audit-stat" [class]="b.css">
                  <div class="text-uppercase small fw-semibold">{{ b.name }}</div>
                  <div class="fs-4 fw-semibold">{{ b.count | number }}</div>
                </div>
              </div>
            }
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 small">
              <thead>
                <tr>
                  <th>Dependency</th>
                  <th style="width:11rem">Advisory</th>
                  <th>Summary</th>
                  <th style="width:6rem">Severity</th>
                </tr>
              </thead>
              <tbody>
                @for (f of sortedFindings(); track $index) {
                  <tr>
                    <td class="font-monospace small" style="word-break:break-all">{{ f.dependency }}</td>
                    <td>
                      <a [href]="osvLink(f.id)" target="_blank" rel="noopener"
                         class="font-monospace small cv-link">
                        {{ f.id }} <i class="bi bi-box-arrow-up-right"></i>
                      </a>
                    </td>
                    <td>{{ f.summary || '—' }}</td>
                    <td>
                      <span class="badge" [class]="severityClass(f.severityScore)">
                        {{ f.severity }}
                      </span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      } @else if (d.status === 'done' && d.findings.length === 0) {
        <div class="cv-surface text-secondary text-center py-4">
          No known vulnerabilities found.
        </div>
      }
    }
  `,
  styles: [`
    .cv-audit-banner {
      border-left: 4px solid var(--bs-border-color);
    }
    .cv-audit-banner--ok   { border-left-color: var(--bs-success, #198754); }
    .cv-audit-banner--ok i { color: var(--bs-success, #198754); }
    .cv-audit-banner--warn { border-left-color: var(--bs-danger,  #dc3545); }
    .cv-audit-banner--warn i { color: var(--bs-danger,  #dc3545); }
    .cv-audit-banner--err  { border-left-color: var(--bs-danger,  #dc3545); }
    .cv-audit-banner--err i  { color: var(--bs-danger,  #dc3545); }
    .cv-audit-banner--run  { border-left-color: var(--cv-accent, #e0592d); }
    .cv-audit-banner--run i  { color: var(--cv-accent, #e0592d); }
    .cv-audit-stat {
      border: 1px solid var(--bs-border-color);
      border-radius: 0.4rem;
      padding: 0.5rem 0.75rem;
      background: var(--bs-tertiary-bg);
      color: var(--bs-secondary-color);
    }
    .cv-audit-stat--critical { background: rgba(220,53,69,0.18);  color: var(--bs-body-color); }
    .cv-audit-stat--high     { background: rgba(220,53,69,0.10);  color: var(--bs-body-color); }
    .cv-audit-stat--medium   { background: rgba(255,193,7,0.18);  color: var(--bs-body-color); }
    .cv-audit-stat--low      { background: rgba(13,202,240,0.16); color: var(--bs-body-color); }
    .cv-link { color: var(--bs-body-color); text-decoration: none; }
    .cv-link:hover { color: var(--cv-accent, #e0592d); }
  `],
})
export class AuditComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = signal<AuditSnapshot | null>(null);

  readonly sortedFindings = computed(() => {
    const all = this.data()?.findings ?? [];
    return [...all].sort((a, b) => b.severityScore - a.severityScore || a.dependency.localeCompare(b.dependency));
  });

  readonly severityBuckets = computed(() => {
    const counts = { critical: 0, high: 0, medium: 0, low: 0 };
    for (const f of this.data()?.findings ?? []) {
      const b = this.bucketOf(f.severityScore);
      counts[b]++;
    }
    return [
      { name: 'Critical', count: counts.critical, css: 'cv-audit-stat--critical' },
      { name: 'High',     count: counts.high,     css: 'cv-audit-stat--high'     },
      { name: 'Medium',   count: counts.medium,   css: 'cv-audit-stat--medium'   },
      { name: 'Low',      count: counts.low,      css: 'cv-audit-stat--low'      },
    ];
  });

  ngOnInit(): void {
    this.refresh();
    visiblePoll(3000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.data()?.status === 'running') this.refresh();
      });
  }

  refresh(): void {
    this.http.get<AuditSnapshot>('/api/audit').subscribe({
      next: (d) => this.data.set(d),
    });
  }

  scan(): void {
    this.http.post<AuditSnapshot>('/api/audit/scan', {}).subscribe({
      next: (d) => this.data.set(d),
    });
  }

  bucketOf(score: number): 'critical' | 'high' | 'medium' | 'low' {
    if (score >= 9.0) return 'critical';
    if (score >= 7.0) return 'high';
    if (score >= 4.0) return 'medium';
    return 'low';
  }

  bannerIcon(status: string): string {
    return {
      idle: 'bi-shield',
      running: 'bi-arrow-clockwise',
      done: 'bi-shield-check',
      error: 'bi-x-octagon-fill',
    }[status] ?? 'bi-shield';
  }

  bannerTitle(d: AuditSnapshot): string {
    if (d.status === 'idle') return 'No scan run yet';
    if (d.status === 'running') return 'Audit in progress…';
    if (d.status === 'error') return 'Audit failed';
    return d.findings.length === 0 ? 'No vulnerabilities' : `${d.findings.length} finding(s)`;
  }

  severityClass(score: number): string {
    const b = this.bucketOf(score);
    return {
      critical: 'bg-danger',
      high: 'bg-danger',
      medium: 'bg-warning',
      low: 'bg-info',
    }[b];
  }

  osvLink(id: string): string {
    return `https://osv.dev/vulnerability/${encodeURIComponent(id)}`;
  }
}
