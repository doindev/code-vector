import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';

type Tab = 'table' | 'raw' | 'graph';

type QueryRow = Record<string, unknown>;

/**
 * One result card in the query view's card list. Self-contained: holds its own cypher,
 * loading state, rows, error, and active tab. Auto-runs once on mount so the user only
 * has to click Run on the top bar; the card "comes alive" the moment it appears.
 *
 * <p>Subsequent runs are driven by the card's own play button — they refresh THIS card's
 * results in place rather than spawning a new card. The favorite button persists the
 * current cypher to the dashboard's query store (which doubles as a saved-queries list).
 */
@Component({
  selector: 'cv-query-card',
  standalone: true,
  imports: [FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-qc" [class.cv-qc--fullscreen]="fullscreen()">
      <div class="cv-qc-bar">
        <div class="cv-qc-input-wrap">
          <input class="form-control font-monospace cv-qc-input"
                 type="text"
                 spellcheck="false"
                 [ngModel]="cypher()"
                 (ngModelChange)="cypher.set($event)"
                 (keydown.enter)="run()"
                 placeholder="MATCH (n) RETURN n LIMIT 25" />
          <button class="cv-qc-icon-btn" (click)="onSave()"
                  [class.cv-qc-icon-btn--active]="saved()"
                  [title]="saved() ? 'Saved' : 'Save to favorites'">
            <i class="bi" [class]="saved() ? 'bi-star-fill' : 'bi-star'"></i>
          </button>
          <button class="cv-qc-icon-btn" (click)="run()" [disabled]="loading()"
                  title="Run query (refresh this card)">
            <i class="bi" [class]="loading() ? 'bi-arrow-repeat' : 'bi-play-fill'"></i>
          </button>
        </div>
        <button class="btn btn-sm btn-link text-secondary cv-qc-mini"
                (click)="expanded.set(!expanded())"
                [title]="expanded() ? 'Collapse' : 'Expand'">
          <i class="bi" [class]="expanded() ? 'bi-chevron-up' : 'bi-chevron-down'"></i>
        </button>
        <button class="btn btn-sm btn-link text-secondary cv-qc-mini"
                (click)="toggleFullscreen()"
                [title]="fullscreen() ? 'Exit full screen' : 'Full screen'">
          <i class="bi" [class]="fullscreen() ? 'bi-arrows-angle-contract' : 'bi-arrows-fullscreen'"></i>
        </button>
        <button class="btn btn-sm btn-link text-secondary cv-qc-mini"
                (click)="onRemove()" title="Remove card">
          <i class="bi bi-x-lg"></i>
        </button>
      </div>

      @if (expanded()) {
      <div class="cv-qc-tabs">
        <div class="btn-group btn-group-sm" role="group" aria-label="View">
          <button class="btn"
                  [class]="activeTab() === 'table' ? 'cv-bg-accent' : 'btn-outline-secondary'"
                  (click)="activeTab.set('table')">Table</button>
          <button class="btn"
                  [class]="activeTab() === 'raw' ? 'cv-bg-accent' : 'btn-outline-secondary'"
                  (click)="activeTab.set('raw')">Raw</button>
          <button class="btn"
                  [class]="activeTab() === 'graph' ? 'cv-bg-accent' : 'btn-outline-secondary'"
                  (click)="activeTab.set('graph')">Graph</button>
        </div>
        <span class="ms-auto small text-secondary">
          @if (loading()) {
            <i class="bi bi-arrow-repeat"></i> Running…
          } @else if (error()) {
            <span class="text-danger"><i class="bi bi-x-circle"></i> error</span>
          } @else {
            {{ rows().length }} row(s)
          }
        </span>
      </div>

      <div class="cv-qc-body">
        @if (error()) {
          <div class="alert alert-danger small font-monospace mb-0">{{ error() }}</div>
        } @else if (activeTab() === 'table') {
          @if (rows().length === 0 && !loading()) {
            <div class="text-secondary small">No rows.</div>
          } @else {
            <div class="cv-qc-table-wrap">
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
          }
        } @else if (activeTab() === 'raw') {
          <pre class="cv-qc-raw font-monospace small mb-0">{{ rawJson() }}</pre>
        } @else if (activeTab() === 'graph') {
          @if (graphColumn(); as gc) {
            <div class="small text-secondary mb-2">
              Click a symbol to open its 1-hop graph slice.
            </div>
            <ul class="list-unstyled mb-0 cv-qc-graph-list">
              @for (row of rows(); track $index) {
                @if (graphSymbolFor(row, gc); as sym) {
                  <li>
                    <a class="cv-qc-graph-link"
                       [routerLink]="['/graph']"
                       [queryParams]="{ symbol: sym }">
                      <i class="bi bi-diagram-3"></i>
                      <span class="font-monospace small">{{ sym }}</span>
                    </a>
                  </li>
                }
              }
            </ul>
          } @else {
            <div class="text-secondary small">
              No graph-shaped column in this result. Try aliasing a node id as
              <code>fqName</code>, <code>symbol</code>, <code>handler</code>,
              <code>caller</code>, or <code>target</code>.
            </div>
          }
        }
      </div>
      }
    </div>
  `,
  styles: [`
    .cv-qc {
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.55rem;
      padding: 0.75rem;
      margin-bottom: 0.85rem;
    }
    .cv-qc-bar {
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    .cv-qc-input-wrap {
      position: relative;
      flex: 1 1 auto;
    }
    .cv-qc-input {
      padding-right: 4.6rem;
    }
    .cv-qc-icon-btn {
      position: absolute;
      top: 50%;
      transform: translateY(-50%);
      border: none;
      background: transparent;
      color: var(--bs-secondary-color);
      width: 2rem;
      height: 2rem;
      border-radius: 0.3rem;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .cv-qc-icon-btn:hover { background: var(--bs-tertiary-bg); color: var(--cv-accent); }
    .cv-qc-icon-btn:disabled { opacity: 0.55; cursor: default; }
    .cv-qc-icon-btn--active { color: var(--cv-accent); }
    /* Favorite on the left, play anchored to the right edge of the input. */
    .cv-qc-icon-btn:nth-of-type(2) { right: 0.25rem; }
    .cv-qc-icon-btn:nth-of-type(1) { right: 2.35rem; }
    .cv-qc-mini { padding: 0.25rem 0.4rem; }
    /* Fullscreen mode lifts the card to a fixed-position viewport overlay so the user can
       drill into a single result without scrolling the whole page. The body uses the full
       remaining height with its own scroll. */
    .cv-qc--fullscreen {
      position: fixed;
      inset: 1rem;
      z-index: 1000;
      margin: 0;
      display: flex;
      flex-direction: column;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
    }
    .cv-qc--fullscreen .cv-qc-body { flex: 1 1 auto; overflow: hidden; }
    .cv-qc--fullscreen .cv-qc-table-wrap,
    .cv-qc--fullscreen .cv-qc-raw,
    .cv-qc--fullscreen .cv-qc-graph-list { max-height: none; height: 100%; }
    .cv-qc-tabs {
      display: flex;
      align-items: center;
      margin-top: 0.7rem;
      gap: 0.5rem;
    }
    .cv-qc-body { margin-top: 0.6rem; }
    .cv-qc-table-wrap {
      max-height: 24rem;
      overflow: auto;
      border: 1px solid var(--bs-border-color);
      border-radius: 0.35rem;
    }
    .cv-qc-raw {
      max-height: 24rem;
      overflow: auto;
      background: var(--bs-tertiary-bg);
      padding: 0.6rem 0.75rem;
      border-radius: 0.35rem;
    }
    .cv-qc-graph-list {
      max-height: 24rem;
      overflow: auto;
    }
    .cv-qc-graph-link {
      display: inline-flex;
      align-items: center;
      gap: 0.45rem;
      padding: 0.3rem 0.55rem;
      border-radius: 0.3rem;
      color: var(--bs-body-color);
      text-decoration: none;
    }
    .cv-qc-graph-link:hover { background: var(--bs-tertiary-bg); color: var(--cv-accent); }
  `],
})
export class QueryCardComponent implements OnInit {
  @Input({ required: true }) initialCypher!: string;
  @Output() remove = new EventEmitter<void>();
  /** Emits the cypher the user asked to save. Parent persists it. */
  @Output() saveCypher = new EventEmitter<string>();

  private readonly http = inject(HttpClient);

  readonly cypher = signal('');
  readonly loading = signal(false);
  readonly rows = signal<ReadonlyArray<QueryRow>>([]);
  readonly columns = signal<ReadonlyArray<string>>([]);
  readonly error = signal('');
  readonly activeTab = signal<Tab>('table');
  /** Flips true after the user clicks the favorite icon — gives the icon a "saved" look. */
  readonly saved = signal(false);
  /** When false, the tabs + result body are hidden; only the input bar stays visible. */
  readonly expanded = signal(true);
  /** Lifts the card to a viewport-filling overlay. Auto-expands the body if collapsed. */
  readonly fullscreen = signal(false);

  ngOnInit(): void {
    this.cypher.set(this.initialCypher);
    // Defer auto-run by a microtask so the card is mounted in the DOM before the spinner
    // shows up; otherwise OnPush sometimes paints the un-loaded state for a frame.
    queueMicrotask(() => this.run());
  }

  run(): void {
    const c = this.cypher().trim();
    if (!c) return;
    this.loading.set(true);
    this.error.set('');
    this.http.post<ReadonlyArray<QueryRow>>('/api/query', { cypher: c }).subscribe({
      next: (list) => {
        const rows = list ?? [];
        this.rows.set(rows);
        this.columns.set(rows.length > 0 ? Object.keys(rows[0]) : []);
        this.loading.set(false);
        this.recordHistory(c, true, rows.length, null);
      },
      error: (err) => {
        const msg = err?.error?.message ?? err?.message ?? 'Query failed';
        this.error.set(msg);
        this.rows.set([]);
        this.columns.set([]);
        this.loading.set(false);
        this.recordHistory(c, false, null, msg);
      },
    });
  }

  onSave(): void {
    this.saved.set(true);
    this.saveCypher.emit(this.cypher());
  }

  onRemove(): void {
    this.remove.emit();
  }

  /** Fullscreen always implies expanded — a collapsed full-screen card has nothing useful. */
  toggleFullscreen(): void {
    this.fullscreen.update((v) => !v);
    if (this.fullscreen()) this.expanded.set(true);
  }

  format(v: unknown): string {
    if (v === null || v === undefined) return '';
    if (typeof v === 'string') return v;
    return JSON.stringify(v);
  }

  /**
   * Best column to use as a symbol for the "graph this row" deep-link. Prefers obvious
   * column names, then falls back to anything ending in 'fqName'. Null hides the column.
   */
  graphColumn(): string | null {
    const cols = this.columns();
    for (const e of ['fqName', 'symbol', 'handler', 'caller', 'target']) {
      if (cols.includes(e)) return e;
    }
    for (const c of cols) if (c.toLowerCase().endsWith('fqname')) return c;
    return null;
  }

  graphSymbolFor(row: QueryRow, col: string): string | null {
    const v = row[col];
    if (typeof v !== 'string' || v.length === 0) return null;
    if (v.startsWith('unresolved.')) return null;
    return v;
  }

  rawJson(): string {
    return JSON.stringify(this.rows(), null, 2);
  }

  private recordHistory(cypher: string, ok: boolean, rowCount: number | null, errorMessage: string | null): void {
    const body: Record<string, unknown> = { cypher, ok };
    if (rowCount !== null) body['rowCount'] = rowCount;
    if (errorMessage) body['errorMessage'] = errorMessage;
    // History persistence is best-effort; failures (404 when dashboard module isn't built)
    // shouldn't disrupt the user's flow.
    this.http.post('/api/dashboard/queries', body).subscribe({ error: () => {} });
  }
}
