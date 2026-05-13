import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';

interface QueryRecord {
  readonly id: string;
  readonly cypher: string;
  readonly ranAt: string;
  readonly ok: boolean;
  readonly rowCount: number | null;
  readonly errorMessage: string | null;
}

@Component({
  selector: 'cv-query',
  standalone: true,
  imports: [FormsModule, DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Query</h1>
        <div class="cv-page-subtitle">Run raw Cypher against the active project graph</div>
      </div>
      <button class="btn cv-bg-accent" (click)="run()" [disabled]="loading()">
        <i class="bi" [class]="loading() ? 'bi-arrow-repeat' : 'bi-play-fill'"></i>
        {{ loading() ? 'Running…' : 'Run' }}
      </button>
    </div>

    <div class="cv-query-layout">
      <div class="cv-query-main">
        <div class="cv-surface mb-3">
          <textarea class="form-control font-monospace"
                    rows="8"
                    spellcheck="false"
                    [ngModel]="cypher()"
                    (ngModelChange)="cypher.set($event)"
                    (keydown.control.enter)="run()"
                    (keydown.meta.enter)="run()"
                    placeholder="MATCH (n) RETURN n LIMIT 25"></textarea>
          <div class="form-text mt-2 d-flex justify-content-between">
            <span>Use <code>$pid</code> for the active project id. <kbd>Ctrl/⌘ + Enter</kbd> to run.</span>
          </div>
        </div>

        @if (error()) {
          <div class="alert alert-danger small font-monospace">{{ error() }}</div>
        }
        @if (rows().length > 0) {
          <div class="cv-surface p-0">
            <table class="table table-sm table-hover mb-0">
              <thead>
                <tr>
                  @for (col of columns(); track col) {
                    <th class="small text-secondary text-uppercase">{{ col }}</th>
                  }
                  @if (graphColumn() !== null) { <th></th> }
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track $index) {
                  <tr>
                    @for (col of columns(); track col) {
                      <td class="font-monospace small">{{ format(row[col]) }}</td>
                    }
                    @if (graphColumn(); as gc) {
                      <td class="text-end" style="width:3rem">
                        @if (graphSymbolFor(row, gc); as sym) {
                          <a class="btn btn-sm btn-link p-0 text-secondary"
                             [routerLink]="['/graph']"
                             [queryParams]="{ symbol: sym }"
                             title="View 1-hop graph slice">
                            <i class="bi bi-diagram-3"></i>
                          </a>
                        }
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="small text-secondary mt-2">{{ rows().length }} row(s)</div>
        }
      </div>

      <aside class="cv-query-history">
        <div class="d-flex justify-content-between align-items-center mb-2">
          <h2 class="fs-6 fw-semibold mb-0">History</h2>
          @if (history().length > 0) {
            <button class="btn btn-sm btn-link text-secondary p-0" (click)="clearHistory()">
              Clear
            </button>
          }
        </div>
        @if (history().length === 0) {
          <p class="text-secondary small mb-0">
            Queries you run will appear here, newest first.
          </p>
        } @else {
          <ul class="list-unstyled mb-0">
            @for (h of history(); track h.id) {
              <li class="cv-history-item">
                <button class="cv-history-cypher btn btn-link p-0 text-start font-monospace small"
                        (click)="loadFromHistory(h)" [title]="h.cypher">
                  {{ h.cypher.length > 90 ? (h.cypher.slice(0, 90) + '…') : h.cypher }}
                </button>
                <div class="cv-history-meta small text-secondary">
                  <span [class]="h.ok ? 'cv-accent' : 'text-danger'">
                    <i class="bi" [class]="h.ok ? 'bi-check-circle' : 'bi-x-circle'"></i>
                    @if (h.ok && h.rowCount !== null) {
                      {{ h.rowCount }} row(s)
                    } @else if (!h.ok) {
                      error
                    }
                  </span>
                  <span>·</span>
                  <span>{{ h.ranAt | date: 'short' }}</span>
                  <button class="btn btn-sm btn-link p-0 ms-1 text-secondary"
                          (click)="removeFromHistory(h.id, $event)"
                          title="Remove">
                    <i class="bi bi-x"></i>
                  </button>
                </div>
              </li>
            }
          </ul>
        }
      </aside>
    </div>
  `,
  styles: [`
    .cv-query-layout {
      display: grid;
      grid-template-columns: 1fr 22rem;
      gap: 1rem;
      align-items: start;
    }
    @media (max-width: 992px) {
      .cv-query-layout { grid-template-columns: 1fr; }
    }
    .cv-query-history {
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.5rem;
      padding: 0.875rem 1rem;
      max-height: calc(100vh - 220px);
      overflow-y: auto;
    }
    .cv-history-item {
      border-bottom: 1px solid var(--bs-border-color);
      padding: 0.5rem 0;
    }
    .cv-history-item:last-child { border-bottom: none; }
    .cv-history-cypher {
      display: block;
      width: 100%;
      color: var(--bs-body-color);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .cv-history-cypher:hover { color: var(--cv-accent); }
    .cv-history-meta {
      display: flex;
      gap: 0.35rem;
      align-items: center;
      margin-top: 0.15rem;
    }
  `],
})
export class QueryComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  readonly cypher = signal(
    'MATCH (n) RETURN n.label AS label, count(*) AS count ORDER BY count DESC LIMIT 25'
  );
  readonly loading = signal(false);
  readonly rows = signal<ReadonlyArray<Record<string, unknown>>>([]);
  readonly columns = signal<ReadonlyArray<string>>([]);
  readonly error = signal('');
  readonly history = signal<ReadonlyArray<QueryRecord>>([]);

  ngOnInit(): void {
    this.refreshHistory();
    // Deep-link from the schema-graph drill-down: ?cypher=... pre-populates the editor.
    // Doesn't auto-run -- user gets a chance to tweak the LIMIT or RETURN clause first.
    this.route.queryParamMap.subscribe((q) => {
      const c = q.get('cypher');
      if (c) this.cypher.set(c);
    });
  }

  refreshHistory(): void {
    this.http.get<ReadonlyArray<QueryRecord>>('/api/dashboard/queries').subscribe({
      next: (list) => this.history.set(list ?? []),
      // 404 is expected when the dashboard module isn't built; ignore silently
      error: () => {},
    });
  }

  run(): void {
    const cypher = this.cypher().trim();
    if (!cypher) return;
    this.loading.set(true);
    this.error.set('');
    this.http
      .post<ReadonlyArray<Record<string, unknown>>>('/api/query', { cypher })
      .subscribe({
        next: (rows) => {
          const list = rows ?? [];
          this.rows.set(list);
          this.columns.set(list.length > 0 ? Object.keys(list[0]) : []);
          this.loading.set(false);
          this.recordHistory(cypher, true, list.length, null);
        },
        error: (err) => {
          const msg = err?.error?.message ?? err?.message ?? 'Query failed';
          this.error.set(msg);
          this.rows.set([]);
          this.columns.set([]);
          this.loading.set(false);
          this.recordHistory(cypher, false, null, msg);
        },
      });
  }

  loadFromHistory(h: QueryRecord): void {
    this.cypher.set(h.cypher);
  }

  removeFromHistory(id: string, evt: Event): void {
    evt.stopPropagation();
    this.http.delete(`/api/dashboard/queries/${id}`).subscribe({
      next: () => this.history.update((list) => list.filter((q) => q.id !== id)),
      error: () => {},
    });
  }

  clearHistory(): void {
    this.http.delete('/api/dashboard/queries').subscribe({
      next: () => this.history.set([]),
      error: () => {},
    });
  }

  private recordHistory(cypher: string, ok: boolean, rowCount: number | null, errorMessage: string | null): void {
    const body: Record<string, unknown> = { cypher, ok };
    if (rowCount !== null) body['rowCount'] = rowCount;
    if (errorMessage) body['errorMessage'] = errorMessage;
    this.http.post<QueryRecord>('/api/dashboard/queries', body).subscribe({
      next: (record) => this.history.update((list) => [record, ...list]),
      // If the dashboard module isn't built, history isn't available -- not fatal.
      error: () => {},
    });
  }

  format(v: unknown): string {
    if (v === null || v === undefined) return '';
    if (typeof v === 'string') return v;
    return JSON.stringify(v);
  }

  /**
   * Best column to use as a symbol for the "graph this row" deep-link. Prefers obvious
   * column names in priority order, then falls back to anything ending in 'fqName'.
   * Returns null when nothing graph-able is in the result -- the action column hides.
   */
  graphColumn(): string | null {
    const cols = this.columns();
    const exact = ['fqName', 'symbol', 'handler', 'caller', 'target'];
    for (const e of exact) if (cols.includes(e)) return e;
    for (const c of cols) if (c.toLowerCase().endsWith('fqname')) return c;
    return null;
  }

  graphSymbolFor(row: Record<string, unknown>, col: string): string | null {
    const v = row[col];
    if (typeof v !== 'string' || v.length === 0) return null;
    // Skip unresolved placeholders -- they aren't real seeds for the graph slice.
    if (v.startsWith('unresolved.')) return null;
    return v;
  }
}
