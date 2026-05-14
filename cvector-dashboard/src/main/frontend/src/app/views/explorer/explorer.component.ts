import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnDestroy,
  OnInit,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import cytoscape, { Core, ElementDefinition } from 'cytoscape';

import { ApiService } from '../../core/api.service';
import { ThemeService } from '../../core/theme.service';

interface LabelRow { readonly name: string; readonly count: number; }
interface ConnRow {
  readonly from: string;
  readonly to: string;
  readonly type: string;
  readonly count: number;
}

interface SchemaResponse {
  readonly project: string;
  readonly projectId: string;
  readonly labels: ReadonlyArray<LabelRow>;
  readonly relTypes: ReadonlyArray<LabelRow>;
  readonly connectivity: ReadonlyArray<ConnRow>;
  readonly totals: {
    readonly labels: number;
    readonly relTypes: number;
    readonly nodes: number;
    readonly edges: number;
  };
}

interface SearchRow { readonly label: string; readonly name: string; readonly fqName: string; }

type Tab = 'tables' | 'graph';

/**
 * Deterministic per-label colour. Hashes the label name with a tiny FNV-1a then maps the
 * resulting 32-bit value to an HSL hue. Saturation / lightness picked so colours stay
 * readable in both dark and light Bootstrap themes against the white-or-near-black labels.
 */
function labelColor(label: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < label.length; i++) {
    h ^= label.charCodeAt(i);
    h = (h * 0x01000193) >>> 0;
  }
  const hue = h % 360;
  return `hsl(${hue}, 62%, 55%)`;
}

@Component({
  selector: 'cv-explorer',
  standalone: true,
  imports: [FormsModule, DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Explorer</h1>
        <div class="cv-page-subtitle">Schema, label counts and quick search</div>
      </div>
      <div class="d-flex gap-2">
        <input class="form-control"
               style="width:18rem"
               placeholder="Search nodes by name…"
               [ngModel]="searchTerm()"
               (ngModelChange)="searchTerm.set($event)"
               (keydown.enter)="search()" />
        <button class="btn cv-bg-accent" (click)="search()">
          <i class="bi bi-search"></i>
        </button>
        <button class="btn btn-outline-secondary" (click)="reloadSchema()" title="Reload schema">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
      </div>
    </div>

    @if (schema(); as s) {
      <div class="row g-3 mb-3">
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Node labels</div>
            <div class="fs-4 fw-semibold">{{ s.totals.labels }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Total nodes</div>
            <div class="fs-4 fw-semibold">{{ s.totals.nodes | number }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Edge types</div>
            <div class="fs-4 fw-semibold">{{ s.totals.relTypes }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Total edges</div>
            <div class="fs-4 fw-semibold">{{ s.totals.edges | number }}</div>
          </div>
        </div>
      </div>
    }

    <ul class="nav nav-tabs mb-3">
      <li class="nav-item">
        <button class="nav-link" [class.active]="tab() === 'tables'" (click)="tab.set('tables')">
          <i class="bi bi-bar-chart-line me-1"></i>Tables
        </button>
      </li>
      <li class="nav-item">
        <button class="nav-link" [class.active]="tab() === 'graph'" (click)="tab.set('graph')">
          <i class="bi bi-diagram-3 me-1"></i>Schema graph
        </button>
      </li>
    </ul>

    @if (tab() === 'tables') {
      <div class="row g-3">
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">Node labels</h2>
            <div class="cv-rollup">
              @for (row of schema()?.labels ?? []; track row.name) {
                <div class="cv-rollup-row">
                  <span class="cv-rollup-name">{{ row.name }}</span>
                  <span class="cv-rollup-bar">
                    <span class="cv-rollup-fill"
                          [style.width.%]="percentOfMax(row.count, schema()?.labels ?? [])"></span>
                  </span>
                  <span class="cv-rollup-count font-monospace small">{{ row.count | number }}</span>
                </div>
              } @empty {
                <div class="text-secondary small">No data yet. Run a scan to populate the graph.</div>
              }
            </div>
          </div>
        </div>

        <div class="col-md-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">Edge types</h2>
            <div class="cv-rollup">
              @for (row of schema()?.relTypes ?? []; track row.name) {
                <div class="cv-rollup-row">
                  <span class="cv-rollup-name">{{ row.name }}</span>
                  <span class="cv-rollup-bar">
                    <span class="cv-rollup-fill"
                          [style.width.%]="percentOfMax(row.count, schema()?.relTypes ?? [])"></span>
                  </span>
                  <span class="cv-rollup-count font-monospace small">{{ row.count | number }}</span>
                </div>
              } @empty {
                <div class="text-secondary small">—</div>
              }
            </div>
          </div>
        </div>
      </div>
    } @else {
      <div class="cv-surface p-0" style="height:calc(100vh - 360px); min-height:30rem; position:relative;">
        <div #schemaCy class="cv-schema-canvas"></div>
        @if ((schema()?.connectivity?.length ?? 0) === 0) {
          <div class="cv-graph-empty text-secondary text-center">
            <i class="bi bi-diagram-3 fs-1 d-block mb-2"></i>
            No connectivity data. Run a scan to populate.
          </div>
        }
      </div>
      <div class="small text-secondary mt-2">
        Each node is a label. Edge label = relationship type with total count.
        Edge thickness scales with count (log).
      </div>
    }

    @if (results().length > 0) {
      <div class="cv-surface mt-3">
        <h2 class="fs-6 fw-semibold mb-2">
          Search results
          <span class="text-secondary fw-normal small">{{ results().length }} match(es)</span>
        </h2>
        <table class="table table-sm table-hover mb-0">
          <thead>
            <tr>
              <th style="width:8rem">Label</th>
              <th>Name</th>
              <th>fqName</th>
              <th style="width:5rem"></th>
            </tr>
          </thead>
          <tbody>
            @for (row of results(); track $index) {
              <tr>
                <td><span class="badge bg-secondary">{{ row.label }}</span></td>
                <td>{{ row.name }}</td>
                <td class="font-monospace small text-secondary" style="word-break:break-all;">{{ row.fqName }}</td>
                <td class="text-end">
                  <a class="btn btn-sm btn-outline-secondary"
                     [routerLink]="['/graph']"
                     [queryParams]="{ symbol: row.fqName || row.name }"
                     title="Open in graph view">
                    <i class="bi bi-diagram-3"></i>
                  </a>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }

    @if (error()) {
      <div class="alert alert-warning small mt-3 mb-0">{{ error() }}</div>
    }
  `,
  styles: [`
    .cv-rollup {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      max-height: 22rem;
      overflow-y: auto;
    }
    .cv-rollup-row {
      display: grid;
      grid-template-columns: 11rem 1fr 5rem;
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
    .cv-schema-canvas { width: 100%; height: 100%; }
    .cv-graph-empty {
      position: absolute; inset: 0;
      display: flex; flex-direction: column;
      align-items: center; justify-content: center;
      pointer-events: none;
    }
  `],
})
export class ExplorerComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly themeSvc = inject(ThemeService);
  private readonly schemaCanvas = viewChild<ElementRef<HTMLDivElement>>('schemaCy');
  private schemaCy?: Core;

  readonly tab = signal<Tab>('tables');
  readonly searchTerm = signal('');
  readonly results = signal<ReadonlyArray<SearchRow>>([]);
  readonly schema = signal<SchemaResponse | null>(null);
  readonly error = signal('');

  constructor() {
    // Re-render the schema-graph tab whenever the schema data changes OR the user switches
    // to the graph tab. Mount happens once in ngAfterViewInit; this effect runs the data
    // update.
    effect(() => {
      const s = this.schema();
      const t = this.tab();
      if (t === 'graph' && s && this.schemaCy) {
        this.renderMetaGraph(s.connectivity);
      }
    });
    // Cytoscape doesn't resolve CSS variables at draw time — its renderer treats them
    // as opaque strings — so edge / node labels read as near-black regardless of the
    // active Bootstrap theme. Reapply concrete colours whenever the theme flips.
    effect(() => {
      this.themeSvc.theme();  // dependency
      if (this.schemaCy) this.schemaCy.style().fromJson(this.cyStyle()).update();
    });
  }

  /** Concrete colour palette for cytoscape, keyed off the active Bootstrap theme. */
  private cyStyle(): cytoscape.StylesheetJson {
    const dark = this.themeSvc.theme() === 'dark';
    const labelColor = dark ? '#e9ecef' : '#212529';
    const edgeColor = dark ? '#cbd1d7' : '#495057';
    const lineColor = dark ? '#5b6470' : '#ced4da';
    const bodyBg = dark ? '#212529' : '#ffffff';
    const borderColor = dark ? '#495057' : '#dee2e6';
    return [
      {
        selector: 'node',
        style: {
          'background-color': 'data(color)',
          'label': 'data(label)',
          'color': labelColor,
          /* Font size tracks node weight so heavier-weighted labels read larger; range
             is tuned to fit the 28-70px diameter mapping below. */
          'font-size': 'mapData(weight, 1, 5000, 9, 18)',
          'font-weight': 700,
          'text-halign': 'center',
          'text-valign': 'center',
          'text-wrap': 'wrap',
          'text-max-width': 'mapData(weight, 1, 5000, 26, 66)',
          'text-outline-color': bodyBg,
          'text-outline-width': 1.5,
          'width': 'mapData(weight, 1, 5000, 28, 70)',
          'height': 'mapData(weight, 1, 5000, 28, 70)',
          'border-width': 2,
          'border-color': borderColor,
        },
      },
      {
        selector: 'edge',
        style: {
          'width': 'mapData(weight, 1, 10000, 1, 8)',
          'line-color': lineColor,
          'curve-style': 'bezier',
          'target-arrow-shape': 'triangle',
          'target-arrow-color': lineColor,
          'label': 'data(label)',
          'font-size': 9,
          'color': edgeColor,
          'text-rotation': 'autorotate',
          'text-background-color': bodyBg,
          'text-background-opacity': 0.85,
          'text-background-padding': '2',
          'opacity': 0.95,
        },
      },
    ];
  }

  ngOnInit(): void {
    this.reloadSchema();
  }

  ngAfterViewInit(): void {
    // Lazy-init cytoscape: only when the canvas is in the DOM (i.e. user clicked the
    // Schema graph tab at least once). We initialise on first availability.
    queueMicrotask(() => this.ensureCy());
    // Also re-check when the tab flips to 'graph'. Pass an explicit injector since
    // ngAfterViewInit is not an injection context (would throw NG0203 otherwise).
    effect(() => {
      if (this.tab() === 'graph') this.ensureCy();
    }, { allowSignalWrites: true, injector: this.injector });
  }

  ngOnDestroy(): void {
    this.schemaCy?.destroy();
  }

  private ensureCy(): void {
    if (this.schemaCy) return;
    const el = this.schemaCanvas()?.nativeElement;
    if (!el) return;
    this.schemaCy = cytoscape({
      container: el,
      elements: [],
      style: this.cyStyle(),
    });
    // Tap-to-drill: clicking a label node in the schema graph hops to the Query view with
    // a "show me 25 nodes of this label" Cypher pre-loaded. Lets the user go from "what
    // labels exist?" to "what do they look like?" without leaving the dashboard.
    this.schemaCy.on('tap', 'node', (evt) => {
      const label = String(evt.target.data('label') ?? '');
      if (!label) return;
      const cypher = `MATCH (n:Node) WHERE n.label = '${label}' RETURN n.fqName AS fqName, n.name AS name LIMIT 25`;
      this.router.navigate(['/query'], { queryParams: { cypher } });
    });
    // Render now if data is already loaded.
    const s = this.schema();
    if (s) this.renderMetaGraph(s.connectivity);
  }

  reloadSchema(): void {
    this.error.set('');
    this.http.get<SchemaResponse>('/api/graph/schema').subscribe({
      next: (s) => this.schema.set(s),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load schema'),
    });
  }

  search(): void {
    const q = this.searchTerm().trim();
    if (!q) return;
    this.api.search(q, 50).subscribe({
      next: (res) => {
        const list = Array.isArray(res)
          ? (res as ReadonlyArray<SearchRow>)
          : ((res as { results?: ReadonlyArray<SearchRow> })?.results ?? []);
        this.results.set(list);
      },
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Search failed'),
    });
  }

  percentOfMax(count: number, rows: ReadonlyArray<LabelRow>): number {
    if (rows.length === 0) return 0;
    const max = rows.reduce((m, r) => Math.max(m, r.count), 0);
    return max === 0 ? 0 : Math.max(2, Math.round((count / max) * 100));
  }

  /**
   * Build a Cytoscape graph where every node = a label, every edge = a (label,label,edgeType)
   * triple. Node weight is "rows the label appears in"; edge weight is the count. The
   * weights drive the node-size and edge-width mappers in the style block above.
   */
  private renderMetaGraph(conn: ReadonlyArray<ConnRow>): void {
    if (!this.schemaCy) return;
    if (conn.length === 0) {
      this.schemaCy.elements().remove();
      return;
    }
    const nodeWeights = new Map<string, number>();
    const elements: ElementDefinition[] = [];
    for (const c of conn) {
      nodeWeights.set(c.from, (nodeWeights.get(c.from) ?? 0) + c.count);
      nodeWeights.set(c.to, (nodeWeights.get(c.to) ?? 0) + c.count);
    }
    nodeWeights.forEach((weight, label) => {
      elements.push({ data: { id: label, label, weight, color: labelColor(label) } });
    });
    for (const c of conn) {
      elements.push({
        data: {
          id: `${c.from}|${c.type}|${c.to}`,
          source: c.from,
          target: c.to,
          label: `${c.type} · ${c.count.toLocaleString()}`,
          type: c.type,
          weight: c.count,
        },
      });
    }
    this.schemaCy.elements().remove();
    this.schemaCy.add(elements);
    // cose runs the force-directed layout; fits and animates by default. For dense schemas
    // (lots of self-loops or fan-out from Method/File) the result is usable; users can pan
    // and zoom from there.
    this.schemaCy.layout({ name: 'cose', animate: true, fit: true, padding: 30 }).run();
  }
}
