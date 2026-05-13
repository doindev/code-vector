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
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';

interface GuardResponse {
  readonly project: { projectId: string; name: string };
  readonly pass?: boolean;
  readonly errors?: number;
  readonly warnings?: number;
  /** Per-rule breakdown: name -> count. Backend shapes this freely. */
  readonly breakdown?: Record<string, number>;
}

interface RuleRow { readonly name: string; readonly count: number; }

@Component({
  selector: 'cv-guard',
  standalone: true,
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Quality gate</h1>
        <div class="cv-page-subtitle">Architecture-rule violations across the active project graph</div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    @if (data(); as d) {
      <div class="cv-guard-banner cv-surface mb-3"
           [class.cv-guard-banner--pass]="d.pass"
           [class.cv-guard-banner--fail]="!d.pass">
        <div class="d-flex align-items-center gap-3">
          <i class="bi" [class]="d.pass ? 'bi-check-circle-fill' : 'bi-x-octagon-fill'"
             style="font-size:2rem;"></i>
          <div>
            <div class="fs-5 fw-semibold">
              {{ d.pass ? 'Gate passing' : 'Gate failing' }}
            </div>
            <div class="small text-secondary">
              {{ d.errors ?? 0 | number }} error(s) · {{ d.warnings ?? 0 | number }} warning(s)
            </div>
          </div>
        </div>
      </div>

      <div class="row g-3">
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Errors</div>
            <div class="fs-3 fw-semibold" [class.text-danger]="(d.errors ?? 0) > 0">
              {{ d.errors ?? 0 | number }}
            </div>
          </div>
        </div>
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Warnings</div>
            <div class="fs-3 fw-semibold" [class.text-warning]="(d.warnings ?? 0) > 0">
              {{ d.warnings ?? 0 | number }}
            </div>
          </div>
        </div>
      </div>

      <div class="cv-surface mt-3">
        <h2 class="fs-6 fw-semibold mb-2">Breakdown by rule</h2>
        @if (ruleRows().length === 0) {
          <p class="text-secondary small mb-0">No rule violations recorded.</p>
        } @else {
          <div class="cv-rollup">
            @for (row of ruleRows(); track row.name) {
              <div class="cv-rollup-row">
                <span class="cv-rollup-name">{{ row.name }}</span>
                <span class="cv-rollup-bar">
                  <span class="cv-rollup-fill"
                        [style.width.%]="percentOfMax(row.count)"></span>
                </span>
                <span class="cv-rollup-count font-monospace small">{{ row.count | number }}</span>
              </div>
            }
          </div>
        }
      </div>

      <p class="small text-secondary mt-3 mb-0">
        <i class="bi bi-info-circle"></i>
        Configure rule thresholds and custom Cypher rules in <code>.cvector/rules.yml</code>.
        Run <code>cvector rules --init</code> to scaffold a template.
      </p>
    } @else {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">
          {{ error() || 'Loading quality gate…' }}
        </div>
      </div>
    }
  `,
  styles: [`
    .cv-guard-banner {
      border-left-width: 4px;
      border-left-style: solid;
    }
    .cv-guard-banner--pass { border-left-color: var(--bs-success, #198754); }
    .cv-guard-banner--fail { border-left-color: var(--bs-danger,  #dc3545); }
    .cv-guard-banner--pass i { color: var(--bs-success, #198754); }
    .cv-guard-banner--fail i { color: var(--bs-danger,  #dc3545); }
    .cv-rollup {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      max-height: 26rem;
      overflow-y: auto;
    }
    .cv-rollup-row {
      display: grid;
      grid-template-columns: 18rem 1fr 4rem;
      gap: 0.5rem;
      align-items: center;
    }
    .cv-rollup-name {
      font-weight: 500;
      font-size: 0.875rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .cv-rollup-bar {
      height: 0.5rem;
      background: var(--bs-tertiary-bg);
      border-radius: 999px;
      overflow: hidden;
    }
    .cv-rollup-fill {
      display: block;
      height: 100%;
      background: var(--cv-accent, #e0592d);
      border-radius: inherit;
      transition: width 0.2s ease;
    }
    .cv-rollup-count {
      text-align: right;
      color: var(--bs-secondary-color);
    }
  `],
})
export class GuardComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = signal<GuardResponse | null>(null);
  readonly error = signal('');

  ngOnInit(): void {
    this.refresh();
    // 30s refresh: the underlying scan rate is the upper bound on how often this can
    // change, so we don't poll aggressively. The user can hit the reload button for
    // instant updates after a manual rerun of `cvector scan` or `cvector rules`.
    visiblePoll(30_000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  refresh(): void {
    this.error.set('');
    this.http.get<GuardResponse>('/api/guard').subscribe({
      next: (d) => this.data.set(d),
      error: (err) => this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load quality gate'),
    });
  }

  ruleRows(): ReadonlyArray<RuleRow> {
    const b = this.data()?.breakdown ?? {};
    return Object.entries(b)
      .map(([name, count]) => ({ name, count: Number(count) }))
      .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
  }

  percentOfMax(count: number): number {
    const rows = this.ruleRows();
    if (rows.length === 0) return 0;
    const max = rows[0].count;
    return max === 0 ? 0 : Math.max(2, Math.round((count / max) * 100));
  }
}
