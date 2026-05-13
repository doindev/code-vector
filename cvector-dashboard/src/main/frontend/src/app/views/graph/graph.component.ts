import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import cytoscape, { Core, ElementDefinition } from 'cytoscape';

interface SliceNode {
  readonly id: string;
  readonly label: string;
  readonly name: string;
  readonly fqName: string;
  readonly isSeed?: boolean;
}

interface SliceEdge {
  readonly id: string;
  readonly source: string;
  readonly target: string;
  readonly type: string;
}

interface SliceResponse {
  readonly seed: SliceNode | null;
  readonly nodes: ReadonlyArray<SliceNode>;
  readonly edges: ReadonlyArray<SliceEdge>;
  readonly truncated: boolean;
}

@Component({
  selector: 'cv-graph',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Graph</h1>
        <div class="cv-page-subtitle">Visual exploration of the cvector graph</div>
      </div>
      <div class="d-flex gap-2 align-items-end">
        <div>
          <label class="form-label small text-secondary mb-1">Symbol</label>
          <input class="form-control form-control-sm font-monospace"
                 style="width:22rem"
                 placeholder="Class.method or fully-qualified name"
                 [(ngModel)]="symbol"
                 (keydown.enter)="load()" />
        </div>
        <div>
          <label class="form-label small text-secondary mb-1">Depth</label>
          <select class="form-select form-select-sm" style="width:5rem"
                  [(ngModel)]="depth">
            <option value="1">1</option>
            <option value="2">2</option>
            <option value="3">3</option>
            <option value="4">4</option>
          </select>
        </div>
        <div>
          <label class="form-label small text-secondary mb-1">Direction</label>
          <select class="form-select form-select-sm" style="width:7rem"
                  [(ngModel)]="direction">
            <option value="both">both</option>
            <option value="in">callers</option>
            <option value="out">callees</option>
          </select>
        </div>
        <div>
          <label class="form-label small text-secondary mb-1">Layout</label>
          <select class="form-select form-select-sm" style="width:9rem"
                  [(ngModel)]="layout"
                  (change)="applyLayout()">
            <option value="cose">cose</option>
            <option value="grid">grid</option>
            <option value="circle">circle</option>
            <option value="concentric">concentric</option>
            <option value="breadthfirst">breadthfirst</option>
          </select>
        </div>
        <button class="btn btn-sm cv-bg-accent" (click)="load()" [disabled]="loading()">
          <i class="bi" [class]="loading() ? 'bi-arrow-repeat' : 'bi-play-fill'"></i>
          {{ loading() ? 'Loading…' : 'Render' }}
        </button>
        <button class="btn btn-sm btn-outline-secondary" (click)="fit()">
          <i class="bi bi-arrows-fullscreen"></i>
        </button>
      </div>
    </div>

    <div class="d-flex gap-3 align-items-center mb-3 small">
      <div class="form-check">
        <input class="form-check-input" type="checkbox" id="hideExternal"
               [checked]="hideExternal()" (change)="toggleHideExternal()" />
        <label class="form-check-label" for="hideExternal">
          Hide external / unresolved
          @if (lastSlice() && hideExternal() && filteredCount() > 0) {
            <span class="badge bg-secondary ms-1">{{ filteredCount() }} hidden</span>
          }
        </label>
      </div>
      <div class="form-check">
        <input class="form-check-input" type="checkbox" id="hideOrphans"
               [checked]="hideOrphans()" (change)="toggleHideOrphans()" />
        <label class="form-check-label" for="hideOrphans">
          Hide orphan nodes
        </label>
      </div>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    <div class="cv-surface p-0" style="height:calc(100vh - 220px); position:relative;">
      <div #cy class="cv-graph-canvas"></div>
      @if (nodeCount() === 0 && !loading()) {
        <div class="cv-graph-empty text-secondary text-center">
          <i class="bi bi-share fs-1 d-block mb-2"></i>
          Enter a symbol above and click <strong>Render</strong>.
          <div class="small mt-2">Try a Class fqName, a Method name, or any node fqName from the search.</div>
        </div>
      }
      @if (selected()) {
        <aside class="cv-graph-side">
          <div class="d-flex justify-content-between align-items-start">
            <div>
              <div class="text-secondary text-uppercase small">{{ selected()!.label }}</div>
              <div class="fs-6 fw-semibold">{{ selected()!.name }}</div>
            </div>
            <button class="btn-close" (click)="selected.set(null)"></button>
          </div>
          <div class="font-monospace small text-secondary mt-2" style="word-break:break-all;">
            {{ selected()!.fqName }}
          </div>
          <button class="btn btn-sm btn-outline-primary mt-3 w-100" (click)="recenterTo(selected()!)">
            <i class="bi bi-bullseye"></i> Center subgraph on this node
          </button>
        </aside>
      }
    </div>

    @if (truncated()) {
      <div class="small text-warning mt-2">
        <i class="bi bi-exclamation-triangle"></i>
        Result truncated to the {{ nodeCount() }}-node cap. Use a smaller depth or call out the callers/callees direction.
      </div>
    } @else if (nodeCount() > 0) {
      <div class="small text-secondary mt-2">{{ nodeCount() }} nodes · {{ edgeCount() }} edges</div>
    }
  `,
  styles: [`
    .cv-graph-canvas { width: 100%; height: 100%; }
    .cv-graph-empty  {
      position: absolute; inset: 0;
      display: flex; flex-direction: column;
      align-items: center; justify-content: center;
      pointer-events: none;
    }
    .cv-graph-side {
      position: absolute; top: 0.75rem; right: 0.75rem;
      width: 18rem;
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.4rem;
      padding: 0.75rem 0.9rem;
      box-shadow: 0 4px 12px rgba(0,0,0,0.08);
    }
  `],
})
export class GraphComponent implements AfterViewInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('cy');

  private cy?: Core;

  readonly symbol = signal('');
  readonly depth = signal<'1' | '2' | '3' | '4'>('1');
  readonly direction = signal<'both' | 'in' | 'out'>('both');
  readonly layout = signal<'cose' | 'grid' | 'circle' | 'concentric' | 'breadthfirst'>('cose');

  readonly loading = signal(false);
  readonly nodeCount = signal(0);
  readonly edgeCount = signal(0);
  readonly truncated = signal(false);
  readonly error = signal('');
  readonly selected = signal<SliceNode | null>(null);
  readonly hideExternal = signal(true);
  readonly hideOrphans = signal(false);
  readonly filteredCount = signal(0);
  /** Cache of the last slice response so filter toggles can re-render without re-fetching. */
  readonly lastSlice = signal<SliceResponse | null>(null);

  ngAfterViewInit(): void {
    this.cy = cytoscape({
      container: this.canvas().nativeElement,
      elements: [],
      style: [
        {
          selector: 'node',
          style: {
            'background-color': 'var(--cv-accent, #e0592d)',
            'label': 'data(name)',
            'color': 'var(--bs-body-color, #1a1a1a)',
            'font-size': 10,
            'text-margin-y': -6,
            'text-halign': 'center',
            'text-valign': 'top',
            'width': 18,
            'height': 18,
            'border-width': 1,
            'border-color': 'var(--bs-border-color, #888)',
          },
        },
        {
          selector: 'node[isSeed]',
          style: {
            'width': 32,
            'height': 32,
            'border-width': 3,
            'border-color': 'var(--cv-accent, #e0592d)',
            'background-color': 'var(--bs-body-bg, #fff)',
            'font-weight': 'bold',
            'font-size': 12,
          },
        },
        {
          selector: 'edge',
          style: {
            'width': 1.2,
            'line-color': 'var(--bs-border-color, #888)',
            'curve-style': 'bezier',
            'target-arrow-shape': 'triangle',
            'target-arrow-color': 'var(--bs-border-color, #888)',
            'opacity': 0.7,
          },
        },
        {
          selector: ':selected',
          style: {
            'border-width': 3,
            'border-color': 'var(--cv-accent, #e0592d)',
          },
        },
      ],
    });

    this.cy.on('tap', 'node', (evt) => {
      const data = evt.target.data() as SliceNode;
      this.selected.set(data);
    });
    this.cy.on('tap', (evt) => {
      if (evt.target === this.cy) this.selected.set(null);
    });

    // Deep-link from Explorer search rows / external links: ?symbol=Foo loads automatically.
    // Query-param subscription stays alive for the component's lifetime so subsequent
    // navigations to /graph?symbol=... re-render without a manual button press.
    this.route.queryParamMap.subscribe((q) => {
      const sym = q.get('symbol');
      if (sym && sym !== this.symbol()) {
        this.symbol.set(sym);
        this.load();
      }
    });
  }

  ngOnDestroy(): void {
    this.cy?.destroy();
  }

  load(): void {
    const sym = this.symbol().trim();
    if (!sym || !this.cy) return;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<SliceResponse>('/api/graph/slice', {
        params: { symbol: sym, depth: this.depth(), direction: this.direction(), max: '250' },
      })
      .subscribe({
        next: (res) => {
          if (!res.seed || res.nodes.length === 0) {
            this.error.set(`No node found for "${sym}".`);
            this.cy?.elements().remove();
            this.lastSlice.set(null);
            this.nodeCount.set(0);
            this.edgeCount.set(0);
            this.truncated.set(false);
            this.loading.set(false);
            return;
          }
          this.lastSlice.set(res);
          this.renderSlice();
          this.truncated.set(res.truncated);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load graph slice.');
          this.loading.set(false);
        },
      });
  }

  recenterTo(node: SliceNode): void {
    this.symbol.set(node.fqName || node.name);
    this.load();
  }

  toggleHideExternal(): void {
    this.hideExternal.update((v) => !v);
    this.renderSlice();
  }

  toggleHideOrphans(): void {
    this.hideOrphans.update((v) => !v);
    this.renderSlice();
  }

  /**
   * Apply the current filter toggles to the last-loaded slice and push the result into
   * Cytoscape. Runs entirely client-side -- no network round-trip when a toggle flips.
   * `unresolved.*` fqNames are the parser's placeholder for cross-file calls that don't
   * resolve to a same-project Method (Java stdlib / npm package calls); they're useful
   * data but noisy in viz, so they're hidden by default.
   */
  private renderSlice(): void {
    const slice = this.lastSlice();
    if (!slice || !this.cy) return;
    const seedId = slice.seed?.id;
    const hideExt = this.hideExternal();
    const keptNodeIds = new Set<string>();
    let keptNodes: SliceNode[] = [];
    for (const n of slice.nodes) {
      if (hideExt && n.id !== seedId && n.fqName?.startsWith('unresolved.')) continue;
      keptNodeIds.add(n.id);
      keptNodes.push(n);
    }
    let keptEdges = slice.edges.filter(
      (e) => keptNodeIds.has(e.source) && keptNodeIds.has(e.target)
    );
    if (this.hideOrphans() && seedId) {
      const connected = new Set<string>([seedId]);
      for (const e of keptEdges) { connected.add(e.source); connected.add(e.target); }
      keptNodes = keptNodes.filter((n) => connected.has(n.id));
      const visibleIds = new Set(keptNodes.map((n) => n.id));
      keptEdges = keptEdges.filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target));
    }
    this.filteredCount.set(slice.nodes.length - keptNodes.length);

    const elements: ElementDefinition[] = [
      ...keptNodes.map((n) => ({ data: { ...n } })),
      ...keptEdges.map((e) => ({ data: { ...e } })),
    ];
    this.cy.elements().remove();
    this.cy.add(elements);
    this.applyLayout();
    this.nodeCount.set(keptNodes.length);
    this.edgeCount.set(keptEdges.length);
  }

  applyLayout(): void {
    this.cy?.layout({ name: this.layout(), fit: true, padding: 24 } as any).run();
  }

  fit(): void {
    this.cy?.fit(undefined, 24);
  }
}
