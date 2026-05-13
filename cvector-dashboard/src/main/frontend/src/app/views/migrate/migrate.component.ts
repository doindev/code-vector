import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe, KeyValuePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

interface FileMeta { path?: string; language?: string; }
interface CallerRow { fqName: string; callSiteLine?: number; }
interface RefRow { label: string; fqName: string; }
interface FileRow { path: string; }
interface TestRow { test: string; depth: number; fileId?: string; }

interface MigrateResponse {
  project: { projectId: string; name: string };
  query: string;
  to: string | null;
  depth: number;
  found: boolean;
  symbol?: { label?: string; fqName?: string; name?: string; startLine?: number };
  file?: FileMeta;
  risk?: number;
  riskLabel?: 'LOW' | 'MEDIUM' | 'HIGH';
  counts?: { callers: number; references: number; importers: number; tests: number; modules: number };
  byModule?: Record<string, number>;
  sequencing?: ReadonlyArray<string>;
  callers?: ReadonlyArray<CallerRow>;
  references?: ReadonlyArray<RefRow>;
  importers?: ReadonlyArray<FileRow>;
  tests?: ReadonlyArray<TestRow>;
}

@Component({
  selector: 'cv-migrate',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe, KeyValuePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Migration plan</h1>
        <div class="cv-page-subtitle">
          Inventory, sequencing, and risk score for replacing a symbol
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <form class="row g-2 align-items-end" (ngSubmit)="run()">
        <div class="col-md-5">
          <label class="form-label small text-secondary mb-1">Symbol to migrate</label>
          <input class="form-control form-control-sm font-monospace"
                 [(ngModel)]="symbol" name="symbol"
                 placeholder="e.g. com.example.LegacyService" required />
        </div>
        <div class="col-md-4">
          <label class="form-label small text-secondary mb-1">Replacement (optional)</label>
          <input class="form-control form-control-sm font-monospace"
                 [(ngModel)]="to" name="to"
                 placeholder="e.g. com.example.NewService" />
        </div>
        <div class="col-md-1">
          <label class="form-label small text-secondary mb-1">Depth</label>
          <input class="form-control form-control-sm" type="number" min="1" max="12"
                 [(ngModel)]="depth" name="depth" />
        </div>
        <div class="col-md-2">
          <button class="btn btn-sm cv-bg-accent w-100" type="submit" [disabled]="loading()">
            <i class="bi" [class.bi-search]="!loading()" [class.bi-arrow-repeat]="loading()"></i>
            Plan
          </button>
        </div>
      </form>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      @if (!d.found) {
        <div class="cv-surface text-center py-5 text-secondary">
          No symbol matching <code>{{ d.query }}</code>.
        </div>
      } @else {
        <div class="row g-3 mb-3">
          <div class="col-md-6">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Symbol</div>
              <div class="fw-semibold font-monospace small">{{ d.symbol?.fqName }}</div>
              <div class="small text-secondary mt-1">
                <span class="badge bg-secondary">{{ d.symbol?.label }}</span>
                @if (d.file?.path) {
                  · <code>{{ d.file?.path }}</code>
                  @if (d.symbol?.startLine) { :{{ d.symbol?.startLine }} }
                }
              </div>
              @if (d.to) {
                <div class="small text-secondary mt-1">
                  → <code>{{ d.to }}</code>
                </div>
              }
            </div>
          </div>
          <div class="col-md-6">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Risk</div>
              <div class="d-flex align-items-baseline gap-2">
                <div class="fs-3 fw-semibold"
                     [class.text-danger]="d.riskLabel === 'HIGH'"
                     [class.text-warning]="d.riskLabel === 'MEDIUM'"
                     [class.text-success]="d.riskLabel === 'LOW'">
                  {{ d.risk }}
                </div>
                <div class="fw-semibold"
                     [class.text-danger]="d.riskLabel === 'HIGH'"
                     [class.text-warning]="d.riskLabel === 'MEDIUM'"
                     [class.text-success]="d.riskLabel === 'LOW'">
                  / 100 — {{ d.riskLabel }}
                </div>
              </div>
              <div class="small text-secondary mt-1">
                Heuristic: callers × 6 (cap 60) + references × 0.4 (cap 20) +
                <span [class.fw-semibold]="(d.counts?.tests ?? 0) === 0">{{
                  (d.counts?.tests ?? 0) > 0 ? '0' : '20'
                }}</span> for missing test coverage.
              </div>
            </div>
          </div>
        </div>

        <div class="row g-3 mb-3">
          @for (k of ['callers', 'references', 'importers', 'tests', 'modules']; track k) {
            <div class="col-md">
              <div class="cv-surface h-100 text-center">
                <div class="text-secondary text-uppercase small">{{ k }}</div>
                <div class="fs-3 fw-semibold cv-accent">
                  {{ countOf(d, k) | number }}
                </div>
              </div>
            </div>
          }
        </div>

        @if (d.sequencing && d.sequencing.length > 0) {
          <div class="cv-surface mb-3">
            <h2 class="fs-6 fw-semibold mb-2">Suggested sequencing</h2>
            <ol class="mb-0 small">
              @for (step of d.sequencing; track $index) {
                <li class="mb-1">{{ step }}</li>
              }
            </ol>
          </div>
        }

        <div class="row g-3">
          <div class="col-lg-6">
            <div class="cv-surface p-0">
              <div class="px-3 py-2 border-bottom">
                <h2 class="fs-6 fw-semibold mb-0">Callers by module ({{ d.counts?.modules ?? 0 }})</h2>
              </div>
              @if (!d.byModule || moduleEntries(d.byModule).length === 0) {
                <div class="text-secondary text-center py-4 small">No callers found.</div>
              } @else {
                <table class="table table-sm table-hover mb-0">
                  <thead><tr><th>Module</th><th class="text-end">Callers</th></tr></thead>
                  <tbody>
                    @for (entry of d.byModule | keyvalue; track entry.key) {
                      <tr>
                        <td class="font-monospace small">{{ entry.key }}</td>
                        <td class="text-end font-monospace small">{{ entry.value | number }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </div>
          </div>
          <div class="col-lg-6">
            <div class="cv-surface p-0">
              <div class="px-3 py-2 border-bottom">
                <h2 class="fs-6 fw-semibold mb-0">Covering tests ({{ d.counts?.tests ?? 0 }})</h2>
              </div>
              @if ((d.tests?.length ?? 0) === 0) {
                <div class="text-warning-emphasis text-center py-4 small">
                  <i class="bi bi-exclamation-triangle"></i>
                  No tests reach this symbol — add coverage before migrating.
                </div>
              } @else {
                <table class="table table-sm table-hover mb-0">
                  <tbody>
                    @for (t of d.tests ?? []; track $index) {
                      <tr>
                        <td class="font-monospace small" style="word-break:break-all;">{{ t.test }}</td>
                        <td class="text-end small text-secondary" style="width:5rem">depth {{ t.depth }}</td>
                        <td class="text-end" style="width:4rem">
                          <a class="btn btn-sm btn-link p-0 text-secondary"
                             [routerLink]="['/explain']"
                             [queryParams]="{ symbol: t.test }">
                            <i class="bi bi-info-circle"></i>
                          </a>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </div>
          </div>
        </div>
      }
    }
  `,
})
export class MigrateComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  symbol = '';
  to = '';
  depth = 5;
  readonly data = signal<MigrateResponse | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        this.symbol = params.get('symbol') ?? '';
        this.to = params.get('to') ?? '';
        const d = Number(params.get('depth') ?? '0');
        if (d > 0) this.depth = d;
        if (this.symbol) this.fetch();
      });
  }

  countOf(d: MigrateResponse, k: string): number {
    const c = d.counts;
    if (!c) return 0;
    const key = k as keyof typeof c;
    return c[key] ?? 0;
  }

  moduleEntries(map: Record<string, number>): ReadonlyArray<[string, number]> {
    return Object.entries(map);
  }

  run(): void {
    if (!this.symbol) return;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { symbol: this.symbol, to: this.to || null, depth: this.depth },
      queryParamsHandling: 'merge',
    });
    this.fetch();
  }

  fetch(): void {
    this.loading.set(true);
    this.error.set('');
    let params = new HttpParams().set('symbol', this.symbol).set('depth', String(this.depth));
    if (this.to) params = params.set('to', this.to);
    this.http.get<MigrateResponse>('/api/migrate', { params }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load migration plan');
        this.loading.set(false);
      },
    });
  }
}
