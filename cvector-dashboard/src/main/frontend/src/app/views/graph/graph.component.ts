import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import cytoscape, { Core, ElementDefinition } from 'cytoscape';

import { ThemeService } from '../../core/theme.service';

interface SliceNode {
  readonly id: string;
  readonly label: string;
  readonly name: string;
  readonly fqName: string;
  readonly isSeed?: boolean;
  /** Total outgoing CALLS edges in the DB — drives the badge inside each rendered node. */
  readonly calleeCount?: number;
}

interface BreadcrumbEntry {
  readonly symbol: string;
  readonly depth: '1' | '2' | '3' | '4';
  readonly direction: 'both' | 'in' | 'out';
  /** Short label shown on the breadcrumb chip — falls back to symbol when unset. */
  readonly name: string;
  readonly nodeLabel: string;
}

/**
 * Build the multi-line in-node label: "{count}\n{name}" when the node has at least one
 * callee, else just the name. Pre-computed so the cytoscape stylesheet can bind to a
 * single data field rather than running a function per render.
 */
function toDisplayLabel(n: SliceNode): string {
  const count = n.calleeCount ?? 0;
  const name = n.name ?? '';
  return count > 0 ? `${count}\n${name}` : name;
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
      <div class="ms-auto text-secondary small">
        <i class="bi bi-lightbulb"></i>
        Double-click any node to expand or collapse its children.
      </div>
    </div>

    @if (history().length > 0) {
      <nav class="cv-graph-breadcrumb mb-2" aria-label="Graph history">
        <i class="bi bi-clock-history me-2 text-secondary"></i>
        @for (entry of history(); track $index; let isLast = $last) {
          <button class="cv-breadcrumb-item"
                  [class.cv-breadcrumb-current]="isLast"
                  [disabled]="isLast"
                  (click)="navigateBack($index)"
                  [title]="entry.symbol">
            @if (entry.nodeLabel) {
              <span class="cv-breadcrumb-tag">{{ entry.nodeLabel }}</span>
            }
            <span class="font-monospace small">{{ entry.name || entry.symbol }}</span>
          </button>
          @if (!isLast) {
            <i class="bi bi-chevron-right cv-breadcrumb-sep"></i>
          }
        }
      </nav>
    }

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
          <button class="btn btn-sm btn-outline-primary mt-3 w-100"
                  (click)="toggleExpand(selected()!)">
            <i class="bi" [class]="isExpanded(selected()!.id) ? 'bi-arrows-collapse' : 'bi-arrows-angle-expand'"></i>
            {{ isExpanded(selected()!.id) ? 'Collapse children' : 'Expand children' }}
          </button>
          <button class="btn btn-sm btn-outline-secondary mt-2 w-100" (click)="recenterTo(selected()!)">
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
    /* Breadcrumb trail: each successful render / recenter pushes one chip; clicking a
       chip truncates the trail and reloads that slice. Flex-wrap so long trails wrap
       to multiple lines on narrow viewports rather than overflowing horizontally. */
    .cv-graph-breadcrumb {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.15rem;
      padding: 0.35rem 0.6rem;
      background: var(--bs-tertiary-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.4rem;
    }
    .cv-breadcrumb-item {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      border: none;
      background: transparent;
      color: var(--bs-body-color);
      padding: 0.2rem 0.5rem;
      border-radius: 0.3rem;
      cursor: pointer;
      max-width: 22rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .cv-breadcrumb-item:hover:not(:disabled) {
      background: var(--bs-body-bg);
      color: var(--cv-accent);
    }
    .cv-breadcrumb-item:disabled {
      cursor: default;
      opacity: 1;
    }
    .cv-breadcrumb-current {
      font-weight: 600;
      background: var(--bs-body-bg);
    }
    .cv-breadcrumb-tag {
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--bs-secondary-color);
      padding: 0.05rem 0.35rem;
      background: var(--bs-body-bg);
      border-radius: 0.25rem;
    }
    .cv-breadcrumb-current .cv-breadcrumb-tag {
      background: var(--bs-tertiary-bg);
    }
    .cv-breadcrumb-sep {
      color: var(--bs-secondary-color);
      font-size: 0.75rem;
      margin: 0 0.05rem;
    }
  `],
})
export class GraphComponent implements AfterViewInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly themeSvc = inject(ThemeService);
  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('cy');

  private cy?: Core;

  constructor() {
    // Cytoscape's canvas renderer doesn't resolve CSS `var()` -- it treats the string
    // as opaque and falls back to the literal hex default, so node labels rendered as
    // near-black regardless of the active Bootstrap theme. Re-apply the concrete-colour
    // stylesheet whenever the user flips themes. Mirrors the same fix in
    // {@code ExplorerComponent#schemaCy}.
    effect(() => {
      this.themeSvc.theme();
      if (this.cy) this.cy.style().fromJson(this.cyStyle() as any).update();
    });
  }

  /**
   * Concrete colour palette for cytoscape, keyed off the active Bootstrap theme. Hex
   * values come from the dashboard's {@code styles.scss}; light/dark variants of
   * {@code --cv-accent} are mirrored here so the seed-node halo matches the rest of the
   * UI's accent on both themes.
   */
  private cyStyle(): cytoscape.StylesheetJson {
    const dark = this.themeSvc.theme() === 'dark';
    const accent = dark ? '#5862e3' : '#333dcc';
    const labelColor = dark ? '#e9ecef' : '#212529';
    const bodyBg = dark ? '#212529' : '#ffffff';
    const borderColor = dark ? '#495057' : '#dee2e6';
    return [
      {
        selector: 'node',
        style: {
          'background-color': accent,
          // Pre-computed multi-line label: "{calleeCount}\n{name}" when the node has
          // callees, else just the name. Centred inside the larger node so the child
          // count reads as a drill-discovery badge at a glance.
          'label': 'data(displayLabel)',
          'color': labelColor,
          'font-size': 9,
          'font-weight': 'bold',
          'text-halign': 'center',
          'text-valign': 'center',
          'text-wrap': 'wrap',
          'text-max-width': '46px',
          'width': 50,
          'height': 50,
          'border-width': 1,
          'border-color': borderColor,
        },
      },
      {
        selector: 'node[isSeed]',
        style: {
          'width': 66,
          'height': 66,
          'border-width': 3,
          'border-color': accent,
          'background-color': bodyBg,
          'color': labelColor,
          'font-size': 10,
          'text-max-width': '60px',
        },
      },
      {
        selector: 'edge',
        style: {
          'width': 1.2,
          'line-color': borderColor,
          'curve-style': 'bezier',
          'target-arrow-shape': 'triangle',
          'target-arrow-color': borderColor,
          'opacity': 0.7,
        },
      },
      {
        selector: ':selected',
        style: {
          'border-width': 3,
          'border-color': accent,
        },
      },
    ];
  }

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

  /**
   * Navigation history for the recenter flow. Each successful {@link load} pushes one
   * entry; clicking a breadcrumb truncates the trail back to that index and re-loads
   * the corresponding slice. Empty until the first render so the breadcrumb bar can
   * stay hidden initially.
   */
  readonly history = signal<ReadonlyArray<BreadcrumbEntry>>([]);

  ngAfterViewInit(): void {
    this.cy = cytoscape({
      container: this.canvas().nativeElement,
      elements: [],
      style: this.cyStyle(),
    });

    this.cy.on('tap', 'node', (evt) => {
      const data = evt.target.data() as SliceNode;
      this.selected.set(data);
    });
    this.cy.on('tap', (evt) => {
      if (evt.target === this.cy) this.selected.set(null);
    });
    // Double-click toggles drill: first dbltap pulls the node's outgoing 1-hop neighbours
    // into the existing graph; second dbltap removes them (and any grandchildren the user
    // drilled in from them). Pairs with the "Expand children" button in the side panel.
    this.cy.on('dbltap', 'node', (evt) => {
      this.toggleExpand(evt.target.data() as SliceNode);
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

  load(skipHistoryPush: boolean = false): void {
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
          if (!skipHistoryPush) this.pushBreadcrumb(res.seed);
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

  /**
   * Click handler for the breadcrumb chips. Truncates the trail back to the clicked
   * level and re-renders that slice without pushing a new history entry (the entry
   * we navigated to is already on the stack).
   */
  navigateBack(index: number): void {
    const hist = this.history();
    if (index < 0 || index >= hist.length) return;
    const entry = hist[index];
    this.history.set(hist.slice(0, index + 1));
    this.symbol.set(entry.symbol);
    this.depth.set(entry.depth);
    this.direction.set(entry.direction);
    this.load(true);
  }

  private pushBreadcrumb(seed: SliceNode): void {
    const entry: BreadcrumbEntry = {
      symbol: seed.fqName || seed.name,
      depth: this.depth(),
      direction: this.direction(),
      name: seed.name || seed.fqName,
      nodeLabel: seed.label || '',
    };
    this.history.update((h) => {
      // De-dupe consecutive identical entries — Render-clicked twice with the same
      // symbol/depth/direction shouldn't grow the trail.
      const top = h[h.length - 1];
      if (top && top.symbol === entry.symbol && top.depth === entry.depth && top.direction === entry.direction) {
        return h;
      }
      return [...h, entry];
    });
  }

  /**
   * Drill toggle: first invocation pulls the node's outgoing 1-hop neighbours into the
   * graph and tags each new child with {@code data.drilledFrom = <parent id>}; a second
   * invocation walks those tags transitively and removes every descendant the user
   * drilled in from this node. Bound to both {@code dbltap} on the canvas and the side
   * panel's Expand/Collapse button so touch users (where dblclick is unreliable) still
   * have access.
   */
  toggleExpand(node: SliceNode): void {
    const cy = this.cy;
    if (!cy) return;
    const el = cy.getElementById(node.id);
    if (el.length === 0) return;
    if (el.data('expanded')) this.collapseNode(node.id);
    else this.expandNode(node);
  }

  isExpanded(nodeId: string): boolean {
    const cy = this.cy;
    if (!cy) return false;
    const el = cy.getElementById(nodeId);
    return el.length > 0 && !!el.data('expanded');
  }

  private expandNode(node: SliceNode): void {
    const cy = this.cy;
    if (!cy) return;
    const sym = node.fqName || node.name;
    if (!sym) return;
    this.http
      .get<SliceResponse>('/api/graph/slice', {
        // direction:'out' = callees / children. depth=1 keeps the user in control of how
        // deep they drill (they double-click further to go deeper). max=80 caps a single
        // hub from drowning the canvas in one expansion.
        params: { symbol: sym, depth: '1', direction: 'out', max: '80' },
      })
      .subscribe({
        next: (res) => {
          if (!res || !Array.isArray(res.nodes) || res.nodes.length === 0) return;
          const existingNodeIds = new Set<string>();
          cy.nodes().forEach((n) => { existingNodeIds.add(n.id()); });
          // Dedup edges by the (source, type, target) tuple — NOT by the response's
          // edge id. GraphSliceController hands out request-local edge ids (e1, e2, …),
          // so the second slice's "e1" collides with the first slice's "e1"; an id-based
          // filter would silently drop every new parent→child edge as "already present"
          // and the user would see the new nodes float in disconnected from the parent.
          const existingEdgeKeys = new Set<string>();
          cy.edges().forEach((e) => {
            existingEdgeKeys.add(`${e.data('source')}|${e.data('type')}|${e.data('target')}`);
          });
          const newNodeData = res.nodes.filter((n) => !existingNodeIds.has(n.id));
          const newEdgeData = res.edges.filter(
            (e) => !existingEdgeKeys.has(`${e.source}|${e.type}|${e.target}`)
          );
          if (newNodeData.length === 0 && newEdgeData.length === 0) return;
          // Seed each new child at the parent's coordinates with a small random jitter so
          // the cose force layout has a sensible starting point. Without this, Cytoscape
          // drops the new node at (0,0) and it shoots in from the canvas origin during
          // the animated layout.
          const parentPos = cy.getElementById(node.id).position();
          const stamp = Date.now();
          cy.add([
            ...newNodeData.map((n) => ({
              data: { ...n, drilledFrom: node.id, displayLabel: toDisplayLabel(n) },
              position: {
                x: parentPos.x + (Math.random() - 0.5) * 80,
                y: parentPos.y + (Math.random() - 0.5) * 80,
              },
            })),
            // Re-assign a client-unique edge id so it can't collide with whatever the
            // initial slice (or prior drill) already added under "e1", "e2", etc.
            ...newEdgeData.map((e, i) => ({
              data: { ...e, id: `e-${node.id}-${stamp}-${i}` },
            })),
          ]);
          cy.getElementById(node.id).data('expanded', true);
          this.refreshLabels();
          // cose is force-directed, so existing nodes are pushed apart to make room for
          // the new children — what the user expects when "drilling down". fit:false keeps
          // the camera anchored so the user's spatial context isn't reset.
          cy.layout(this.coseOptions(false)).run();
          this.nodeCount.update((n) => n + newNodeData.length);
          this.edgeCount.update((n) => n + newEdgeData.length);
          // Mirror the addition into lastSlice so the Hide-external / Hide-orphans
          // toggles operate on the full expanded set.
          const prev = this.lastSlice();
          if (prev) {
            this.lastSlice.set({
              seed: prev.seed,
              nodes: [...prev.nodes, ...newNodeData],
              edges: [...prev.edges, ...newEdgeData],
              truncated: prev.truncated,
            });
          }
        },
        error: () => { /* silent — drill is best-effort, the user can retry */ },
      });
  }

  private collapseNode(nodeId: string): void {
    const cy = this.cy;
    if (!cy) return;
    // BFS over the drilledFrom pointers to gather every descendant the user introduced by
    // drilling into this node — including grandchildren, great-grandchildren, etc. Cytoscape
    // auto-removes incident edges with each removed node, so we don't enumerate edges.
    const toRemove = new Set<string>();
    const queue = [nodeId];
    while (queue.length > 0) {
      const parent = queue.shift()!;
      cy.nodes().forEach((n) => {
        if (n.data('drilledFrom') === parent && !toRemove.has(n.id())) {
          toRemove.add(n.id());
          queue.push(n.id());
        }
        return;  // void return — Cytoscape forEach uses truthy to break iteration
      });
    }
    cy.getElementById(nodeId).data('expanded', false);
    if (toRemove.size === 0) return;
    toRemove.forEach((id) => cy.getElementById(id).remove());
    this.refreshLabels();
    cy.layout(this.coseOptions(false)).run();
    this.nodeCount.set(cy.nodes().length);
    this.edgeCount.set(cy.edges().length);
    const prev = this.lastSlice();
    if (prev) {
      this.lastSlice.set({
        seed: prev.seed,
        nodes: prev.nodes.filter((n) => !toRemove.has(n.id)),
        edges: prev.edges.filter((e) => !toRemove.has(e.source) && !toRemove.has(e.target)),
        truncated: prev.truncated,
      });
    }
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
      ...keptNodes.map((n) => ({ data: { ...n, displayLabel: toDisplayLabel(n) } })),
      ...keptEdges.map((e) => ({ data: { ...e } })),
    ];
    this.cy.elements().remove();
    this.cy.add(elements);
    this.refreshLabels();
    this.applyLayout();
    this.nodeCount.set(keptNodes.length);
    this.edgeCount.set(keptEdges.length);
  }

  applyLayout(): void {
    this.cy?.layout(this.layoutOptions(true)).run();
  }

  /**
   * Push a fresh {@code displayLabel} into every node based on its currently visible
   * degree. The badge inside each node thus matches what the user actually sees on the
   * canvas — the DB-side {@code calleeCount} reported only outgoing CALLS edges, which
   * undercounted the seed when its slice was loaded with {@code direction=both}
   * (callers were drawn but not counted). Re-run after every add / remove / filter
   * toggle so the count stays in sync as the user expands or collapses.
   */
  private refreshLabels(): void {
    const cy = this.cy;
    if (!cy) return;
    cy.nodes().forEach((n) => {
      const degree = n.connectedEdges().length;
      const name = (n.data('name') as string | undefined) ?? '';
      n.data('displayLabel', degree > 0 ? `${degree}\n${name}` : name);
    });
  }

  fit(): void {
    this.cy?.fit(undefined, 24);
  }

  /**
   * Layout options tuned for the 50×50 nodes the graph now renders. The cytoscape
   * defaults assume ~5px nodes, so without tuning the bigger nodes pile on top of each
   * other once the slice exceeds ~20 nodes. The cose branch raises repulsion + ideal
   * edge length + overlap penalty; the other layouts get {@code avoidOverlap: true}
   * which most of them support natively.
   */
  private layoutOptions(fit: boolean): any {
    const name = this.layout();
    if (name === 'cose') return this.coseOptions(fit);
    return {
      name,
      fit,
      padding: 24,
      animate: true,
      avoidOverlap: true,
      avoidOverlapPadding: 12,
    };
  }

  private coseOptions(fit: boolean): any {
    return {
      name: 'cose',
      fit,
      padding: 24,
      animate: true,
      // Force-model tuning for the 50×50 nodes. nodeRepulsion / idealEdgeLength scale
      // linearly with node diameter; the defaults (~2048 / ~10) were sized for the old
      // 18×18 dots and let the bigger nodes overlap. Higher numIter lets the simulation
      // settle even on dense ~100-node graphs.
      nodeRepulsion: 8000,
      idealEdgeLength: 80,
      nodeOverlap: 20,
      gravity: 0.25,
      numIter: 1500,
    };
  }
}
