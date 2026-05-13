import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
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
  readonly lineStart?: number;
  readonly lineEnd?: number;
}

interface FileRef {
  readonly id?: string;
  readonly path?: string;
  readonly language?: string;
}

interface SearchResponse {
  readonly query: string;
  readonly count: number;
  readonly results: ReadonlyArray<SymbolHit>;
}

interface ExplainResponse {
  readonly query: string;
  readonly found: boolean;
  readonly symbol?: SymbolHit;
  readonly file?: FileRef;
  readonly callers?: ReadonlyArray<SymbolHit>;
  readonly callees?: ReadonlyArray<SymbolHit>;
}

@Component({
  selector: 'cv-explain',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Explain symbol</h1>
        <div class="cv-page-subtitle">
          Definition, file, and call relationships for any symbol in the graph
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <div class="d-flex gap-2 align-items-center">
        <i class="bi bi-search text-secondary"></i>
        <input class="form-control form-control-sm"
               type="text"
               placeholder="Search by name, fqName, or partial match (Enter to search)"
               [(ngModel)]="queryDraft"
               (keydown.enter)="search()" />
        <button class="btn btn-sm btn-primary" (click)="search()" [disabled]="loading()">
          @if (loading()) { Searching… } @else { Search }
        </button>
      </div>
      @if (error()) {
        <div class="alert alert-warning small mt-2 mb-0">{{ error() }}</div>
      }
    </div>

    @if (searchResults(); as r) {
      @if (r.count > 1) {
        <div class="cv-surface mb-3">
          <div class="text-secondary text-uppercase small fw-semibold mb-2">
            {{ r.count | number }} match(es) for &ldquo;{{ r.query }}&rdquo;
          </div>
          <div class="cv-explain-matches">
            @for (m of r.results; track m.id) {
              <button class="cv-explain-match"
                      [class.cv-explain-match--active]="active()?.id === m.id"
                      (click)="pick(m)">
                <span class="badge bg-secondary me-2">{{ m.label }}</span>
                <span class="font-monospace small">{{ m.fqName || m.name || m.id }}</span>
              </button>
            }
          </div>
        </div>
      }
    }

    @if (explain(); as e) {
      @if (e.found && e.symbol; as sym) {
        <div class="cv-surface mb-3">
          <div class="d-flex justify-content-between align-items-start gap-3">
            <div class="flex-grow-1">
              <div class="d-flex align-items-center gap-2 mb-1">
                <span class="badge bg-secondary">{{ sym.label }}</span>
                <h2 class="fs-5 fw-semibold m-0 font-monospace">{{ sym.fqName || sym.name }}</h2>
              </div>
              @if (e.file?.path) {
                <div class="small text-secondary">
                  <i class="bi bi-file-earmark-code"></i>
                  <code>{{ e.file?.path }}</code>
                  @if (sym.lineStart) { <span class="ms-1">:{{ sym.lineStart }}@if (sym.lineEnd) {-{{ sym.lineEnd }}}</span> }
                  @if (e.file?.language) { <span class="ms-2">[{{ e.file?.language }}]</span> }
                </div>
              }
            </div>
            <div class="btn-group btn-group-sm">
              <a class="btn btn-outline-secondary"
                 [routerLink]="['/impact']"
                 [queryParams]="{ symbol: sym.fqName || sym.name }">
                <i class="bi bi-bullseye"></i> Impact
              </a>
              <a class="btn btn-outline-secondary"
                 [routerLink]="['/graph']"
                 [queryParams]="{ symbol: sym.fqName || sym.name }">
                <i class="bi bi-share"></i> Graph
              </a>
              <a class="btn btn-outline-secondary"
                 [routerLink]="['/query']"
                 [queryParams]="{ cypher: cypherForSymbol(sym) }">
                <i class="bi bi-terminal"></i> Query
              </a>
            </div>
          </div>
        </div>

        <div class="row g-3">
          <div class="col-md-6">
            <div class="cv-surface h-100">
              <div class="d-flex justify-content-between align-items-center mb-2">
                <h3 class="fs-6 fw-semibold m-0">
                  <i class="bi bi-arrow-left-circle text-secondary"></i> Callers
                </h3>
                <span class="badge bg-secondary">{{ (e.callers?.length ?? 0) | number }}</span>
              </div>
              @if ((e.callers?.length ?? 0) === 0) {
                <p class="text-secondary small mb-0">No callers found.</p>
              } @else {
                <ul class="cv-explain-list">
                  @for (c of e.callers; track c.id) {
                    <li>
                      <button class="cv-explain-link" (click)="pick(c)">
                        <span class="badge bg-secondary me-1">{{ c.label }}</span>
                        <span class="font-monospace small">{{ c.fqName || c.name }}</span>
                      </button>
                    </li>
                  }
                </ul>
              }
            </div>
          </div>
          <div class="col-md-6">
            <div class="cv-surface h-100">
              <div class="d-flex justify-content-between align-items-center mb-2">
                <h3 class="fs-6 fw-semibold m-0">
                  <i class="bi bi-arrow-right-circle text-secondary"></i> Callees
                </h3>
                <span class="badge bg-secondary">{{ (e.callees?.length ?? 0) | number }}</span>
              </div>
              @if ((e.callees?.length ?? 0) === 0) {
                <p class="text-secondary small mb-0">No callees found.</p>
              } @else {
                <ul class="cv-explain-list">
                  @for (c of e.callees; track c.id) {
                    <li>
                      <button class="cv-explain-link" (click)="pick(c)">
                        <span class="badge bg-secondary me-1">{{ c.label }}</span>
                        <span class="font-monospace small">{{ c.fqName || c.name }}</span>
                      </button>
                    </li>
                  }
                </ul>
              }
            </div>
          </div>
        </div>
      } @else if (queryDraft()) {
        <div class="cv-surface">
          <div class="text-secondary text-center py-4">
            No symbol found for &ldquo;{{ explain()?.query }}&rdquo;.
          </div>
        </div>
      }
    } @else if (!queryDraft() && !loading()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">
          Search for a symbol above, or follow an &ldquo;Explain&rdquo; link from another view.
        </div>
      </div>
    }
  `,
  styles: [`
    .cv-explain-matches {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.35rem;
      max-height: 14rem;
      overflow-y: auto;
    }
    @media (max-width: 768px) {
      .cv-explain-matches { grid-template-columns: 1fr; }
    }
    .cv-explain-match {
      display: flex;
      align-items: center;
      padding: 0.35rem 0.5rem;
      background: var(--bs-tertiary-bg);
      border: 1px solid transparent;
      border-radius: 0.35rem;
      text-align: left;
      cursor: pointer;
      width: 100%;
    }
    .cv-explain-match:hover { border-color: var(--cv-accent, #e0592d); }
    .cv-explain-match--active {
      border-color: var(--cv-accent, #e0592d);
      background: var(--bs-body-bg);
    }
    .cv-explain-list {
      list-style: none;
      padding: 0;
      margin: 0;
      max-height: 18rem;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }
    .cv-explain-link {
      display: inline-block;
      padding: 0.25rem 0.4rem;
      background: none;
      border: 1px solid transparent;
      border-radius: 0.25rem;
      cursor: pointer;
      width: 100%;
      text-align: left;
    }
    .cv-explain-link:hover {
      background: var(--bs-tertiary-bg);
      border-color: var(--bs-border-color);
    }
  `],
})
export class ExplainComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly queryDraft = signal('');
  readonly loading = signal(false);
  readonly error = signal('');
  readonly searchResults = signal<SearchResponse | null>(null);
  readonly explain = signal<ExplainResponse | null>(null);
  readonly active = signal<SymbolHit | null>(null);

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      const symbol = params.get('symbol');
      if (symbol && symbol !== this.queryDraft()) {
        this.queryDraft.set(symbol);
        this.search();
      }
    });
  }

  search(): void {
    const q = this.queryDraft().trim();
    if (!q) return;
    this.loading.set(true);
    this.error.set('');
    const params = new HttpParams().set('q', q);
    this.http.get<SearchResponse>('/api/search', { params }).subscribe({
      next: (r) => {
        this.searchResults.set(r);
        if (r.count > 0) {
          this.pick(r.results[0]);
        } else {
          this.explain.set({ query: q, found: false });
          this.active.set(null);
        }
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Search failed');
        this.loading.set(false);
      },
    });
  }

  pick(hit: SymbolHit): void {
    this.active.set(hit);
    const probe = hit.fqName || hit.name || hit.id;
    const params = new HttpParams().set('symbol', probe);
    this.http.get<ExplainResponse>('/api/explain', { params }).subscribe({
      next: (e) => this.explain.set(e),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Explain failed'),
    });
    // Update URL so back/forward and copy/paste deep-link work.
    this.router.navigate([], {
      queryParams: { symbol: probe },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  cypherForSymbol(sym: SymbolHit): string {
    const label = sym.label || 'Method';
    const fq = (sym.fqName || sym.name || '').replace(/'/g, "\\'");
    return `MATCH (n:${label}) WHERE n.fqName = '${fq}' RETURN n LIMIT 10`;
  }
}
