import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

interface SymbolHit {
  readonly id: string;
  readonly label: string;
  readonly name?: string;
  readonly fqName?: string;
  readonly fileId?: string;
}

interface ImpactedNode {
  readonly id: string;
  readonly label: string;
  readonly name?: string;
  readonly fqName?: string;
}

interface TestReachRow {
  readonly test?: string;
  readonly fqName?: string;
  readonly fileId?: string;
  readonly depth: number;
}

interface ImpactResponse {
  readonly found: boolean;
  readonly query?: string;
  readonly symbol?: SymbolHit;
  readonly depth?: number;
  readonly impacted?: ReadonlyArray<ImpactedNode>;
}

interface TestImpactResponse {
  readonly found: boolean;
  readonly query?: string;
  readonly symbol?: SymbolHit;
  readonly tests?: ReadonlyArray<TestReachRow>;
}

type Tab = 'downstream' | 'tests';

@Component({
  selector: 'cv-impact',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Impact analysis</h1>
        <div class="cv-page-subtitle">
          Downstream reach + test coverage for a symbol &mdash; what breaks if you change it
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <div class="d-flex gap-2 align-items-center flex-wrap">
        <i class="bi bi-bullseye text-secondary"></i>
        <input class="form-control form-control-sm flex-grow-1"
               style="min-width:18rem"
               type="text"
               placeholder="Symbol name, fqName, or partial match"
               [(ngModel)]="queryDraft"
               (keydown.enter)="run()" />
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small text-secondary mb-0">Depth</label>
          <select class="form-select form-select-sm" style="width:5rem"
                  [(ngModel)]="depth">
            @for (d of depths; track d) {
              <option [ngValue]="d">{{ d }}</option>
            }
          </select>
        </div>
        <button class="btn btn-sm btn-primary" (click)="run()" [disabled]="loading()">
          @if (loading()) { Computing… } @else { Analyze }
        </button>
      </div>
      @if (error()) {
        <div class="alert alert-warning small mt-2 mb-0">{{ error() }}</div>
      }
    </div>

    @if (resolvedSymbol(); as sym) {
      <div class="cv-surface mb-3">
        <div class="d-flex justify-content-between align-items-start gap-3">
          <div>
            <div class="d-flex align-items-center gap-2 mb-1">
              <span class="badge bg-secondary">{{ sym.label }}</span>
              <h2 class="fs-5 fw-semibold m-0 font-monospace">{{ sym.fqName || sym.name }}</h2>
            </div>
            <div class="small text-secondary">depth {{ depth() }} &middot;
              {{ (impactResp()?.impacted?.length ?? 0) | number }} impacted node(s) &middot;
              {{ (testResp()?.tests?.length ?? 0) | number }} test path(s)
            </div>
          </div>
          <div class="btn-group btn-group-sm">
            <a class="btn btn-outline-secondary"
               [routerLink]="['/explain']"
               [queryParams]="{ symbol: sym.fqName || sym.name }">
              <i class="bi bi-info-circle"></i> Explain
            </a>
            <a class="btn btn-outline-secondary"
               [routerLink]="['/graph']"
               [queryParams]="{ symbol: sym.fqName || sym.name }">
              <i class="bi bi-share"></i> Graph
            </a>
          </div>
        </div>
      </div>

      <ul class="nav nav-tabs mb-3">
        <li class="nav-item">
          <button class="nav-link"
                  [class.active]="tab() === 'downstream'"
                  (click)="setTab('downstream')">
            <i class="bi bi-arrow-down-right-circle"></i> Downstream impact
            <span class="badge bg-secondary ms-1">{{ (impactResp()?.impacted?.length ?? 0) | number }}</span>
          </button>
        </li>
        <li class="nav-item">
          <button class="nav-link"
                  [class.active]="tab() === 'tests'"
                  (click)="setTab('tests')">
            <i class="bi bi-check2-square"></i> Test reach
            <span class="badge bg-secondary ms-1">{{ (testResp()?.tests?.length ?? 0) | number }}</span>
          </button>
        </li>
      </ul>

      @if (tab() === 'downstream') {
        @if ((impactResp()?.impacted?.length ?? 0) === 0) {
          <div class="cv-surface text-secondary text-center py-4">
            No downstream impact at depth {{ depth() }}.
          </div>
        } @else {
          <div class="cv-surface p-0">
            <table class="table table-hover mb-0 small">
              <thead>
                <tr>
                  <th style="width:8rem">Label</th>
                  <th>fqName</th>
                  <th style="width:6rem"></th>
                </tr>
              </thead>
              <tbody>
                @for (row of impactResp()!.impacted!; track row.id) {
                  <tr>
                    <td><span class="badge bg-secondary">{{ row.label }}</span></td>
                    <td class="font-monospace small" style="word-break:break-all;">{{ row.fqName || row.name }}</td>
                    <td class="text-end">
                      <a class="btn btn-sm btn-link p-0 text-secondary"
                         [routerLink]="['/explain']"
                         [queryParams]="{ symbol: row.fqName || row.name }"
                         title="Explain">
                        <i class="bi bi-info-circle"></i>
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else {
        @if ((testResp()?.tests?.length ?? 0) === 0) {
          <div class="cv-surface text-secondary text-center py-4">
            No tests reach this symbol within {{ depth() }} hops.
          </div>
        } @else {
          <div class="cv-surface p-0">
            <table class="table table-hover mb-0 small">
              <thead>
                <tr>
                  <th style="width:4rem">Depth</th>
                  <th>Test</th>
                  <th style="width:6rem"></th>
                </tr>
              </thead>
              <tbody>
                @for (row of testResp()!.tests!; track $index) {
                  <tr>
                    <td><span class="badge bg-secondary">{{ row.depth }}</span></td>
                    <td class="font-monospace small" style="word-break:break-all;">
                      {{ row.test || row.fqName }}
                    </td>
                    <td class="text-end">
                      <a class="btn btn-sm btn-link p-0 text-secondary"
                         [routerLink]="['/explain']"
                         [queryParams]="{ symbol: row.test || row.fqName }"
                         title="Explain">
                        <i class="bi bi-info-circle"></i>
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    } @else if (!loading() && queryDraft()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">
          No symbol found for &ldquo;{{ queryDraft() }}&rdquo;.
        </div>
      </div>
    } @else if (!queryDraft()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">
          Enter a symbol to analyse, or follow an &ldquo;Impact&rdquo; link from another view.
        </div>
      </div>
    }
  `,
  styles: [`
    .nav-tabs .nav-link {
      color: var(--bs-body-color);
      background: transparent;
      border: none;
      border-bottom: 2px solid transparent;
    }
    .nav-tabs .nav-link.active {
      border-bottom-color: var(--cv-accent, #e0592d);
      color: var(--cv-accent, #e0592d);
      font-weight: 600;
    }
  `],
})
export class ImpactComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly depths: ReadonlyArray<number> = [1, 2, 3, 4, 5, 6];

  readonly queryDraft = signal('');
  readonly depth = signal(3);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly impactResp = signal<ImpactResponse | null>(null);
  readonly testResp = signal<TestImpactResponse | null>(null);
  readonly tab = signal<Tab>('downstream');

  readonly resolvedSymbol = computed<SymbolHit | null>(() => {
    return this.impactResp()?.symbol ?? this.testResp()?.symbol ?? null;
  });

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      const symbol = params.get('symbol');
      const depth = params.get('depth');
      if (depth) {
        const n = Number(depth);
        if (Number.isFinite(n) && n > 0) this.depth.set(n);
      }
      if (symbol && symbol !== this.queryDraft()) {
        this.queryDraft.set(symbol);
        this.run();
      }
    });
  }

  setTab(t: Tab): void { this.tab.set(t); }

  run(): void {
    const q = this.queryDraft().trim();
    if (!q) return;
    this.loading.set(true);
    this.error.set('');
    const params = new HttpParams().set('symbol', q).set('depth', String(this.depth()));
    // Issue both requests in parallel via separate subscriptions; first to finish updates
    // its signal independently. Test-reach can run at a larger default depth, but matching
    // the downstream depth keeps the UI consistent for users tuning the slider.
    this.http.get<ImpactResponse>('/api/impact', { params }).subscribe({
      next: (r) => { this.impactResp.set(r); this.loading.set(false); this.syncUrl(); },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Impact lookup failed');
        this.loading.set(false);
      },
    });
    this.http.get<TestImpactResponse>('/api/test-impact', { params }).subscribe({
      next: (r) => this.testResp.set(r),
      error: () => { /* surfaced via impact error */ },
    });
  }

  private syncUrl(): void {
    this.router.navigate([], {
      queryParams: { symbol: this.queryDraft(), depth: this.depth() },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
