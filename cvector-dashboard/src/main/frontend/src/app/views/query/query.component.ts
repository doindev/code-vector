import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';

import { QueryCardComponent } from './query-card.component';
import { CypherRefService } from '../../core/cypher-ref.service';

interface QueryRecord {
  readonly id: string;
  readonly cypher: string;
  readonly ranAt: string;
  readonly ok: boolean;
  readonly rowCount: number | null;
  readonly errorMessage: string | null;
}

interface Card {
  /** Local-only id so @for tracking is stable across re-orderings. */
  readonly id: string;
  readonly cypher: string;
}

const DEFAULT_CYPHER =
  'MATCH (n) RETURN n.label AS label, count(*) AS count ORDER BY count DESC LIMIT 25';

/**
 * Query view shell. Top-of-page input bar drives the creation of {@link QueryCardComponent}
 * instances below — each card is one run and is independently re-runnable. The bar's
 * ellipsis menu surfaces save/history/fullscreen/format actions; the inline play button
 * appends a new card. The historical "history side panel" is gone — past queries are now
 * reachable via the History search modal in the ellipsis menu.
 */
@Component({
  selector: 'cv-query',
  standalone: true,
  imports: [FormsModule, QueryCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>
          Query
          <button class="btn btn-sm btn-link text-secondary cv-qref-toggle"
                  (click)="cypherRef.toggle()"
                  [class.cv-qref-toggle--active]="cypherRef.visible()"
                  [title]="cypherRef.visible() ? 'Hide Cypher reference' : 'Show Cypher reference'">
            <i class="bi bi-book"></i>
          </button>
        </h1>
        <div class="cv-page-subtitle">Run raw Cypher against the active project graph</div>
      </div>
    </div>

    <!-- Top input bar: drives card creation. Sits above all result cards. -->
    <div class="cv-qbar">
      <div class="cv-qbar-input-wrap">
        <textarea #topTextarea
                  class="form-control font-monospace cv-qbar-input"
                  rows="1"
                  spellcheck="false"
                  [ngModel]="topCypher()"
                  (ngModelChange)="topCypher.set($event)"
                  (keydown.control.enter)="runTop()"
                  (keydown.meta.enter)="runTop()"
                  [placeholder]="defaultCypher"></textarea>
        <button class="cv-qbar-ellipsis"
                (click)="toggleMenu($event)"
                [class.cv-qbar-ellipsis--open]="menuOpen()"
                aria-label="More actions"
                title="More actions">
          <i class="bi bi-three-dots-vertical"></i>
        </button>
        <button class="cv-qbar-play"
                (click)="runTop()"
                [disabled]="!topCypher().trim()"
                title="Run (creates a new result card)">
          <i class="bi bi-play-fill"></i>
        </button>
        @if (menuOpen()) {
          <div class="cv-qbar-menu" (click)="$event.stopPropagation()">
            <button class="cv-qbar-menu-item" (click)="saveTopCypher(); closeMenu()">
              <i class="bi bi-bookmark"></i> Save cypher
            </button>
            <button class="cv-qbar-menu-item" (click)="openHistorySearch(); closeMenu()">
              <i class="bi bi-clock-history"></i> History search
            </button>
            <button class="cv-qbar-menu-item" (click)="openFullscreen(); closeMenu()">
              <i class="bi bi-arrows-fullscreen"></i> Full screen
            </button>
            <button class="cv-qbar-menu-item" (click)="formatTopCypher(); closeMenu()">
              <i class="bi bi-text-indent-left"></i> Format query
            </button>
          </div>
        }
      </div>

      <!-- Inline history panel: expands directly below the textarea when the user picks
           "History search" from the ellipsis menu. Always-rendered (vs. @if) so the CSS
           transition can run on both expand and collapse; the class toggle drives the
           max-height / opacity animation. Clicking a row populates the textarea with the
           full cypher (newlines preserved) and collapses the panel. -->
      <div class="cv-qbar-history"
           [class.cv-qbar-history--open]="historySearchOpen()"
           [attr.aria-hidden]="!historySearchOpen()"
           (click)="$event.stopPropagation()">
        <div class="cv-qbar-history-search">
          <i class="bi bi-search"></i>
          <input #historyInput
                 class="form-control form-control-sm"
                 type="text"
                 [ngModel]="historyFilter()"
                 (ngModelChange)="historyFilter.set($event)"
                 placeholder="Filter past queries…" />
        </div>
        <div class="cv-qbar-history-list">
          @if (filteredHistory().length === 0) {
            <div class="text-secondary small p-2">No matching past queries.</div>
          } @else {
            @for (h of filteredHistory(); track h.id) {
              <button class="cv-qbar-history-row"
                      [attr.tabindex]="historySearchOpen() ? 0 : -1"
                      (click)="pickFromHistory(h)">
                <span class="cv-qbar-history-cypher font-monospace small">{{ singleLine(h.cypher) }}</span>
              </button>
            }
          }
        </div>
      </div>
    </div>

    <div class="cv-qbar-hint small text-secondary">
      Use <code>$pid</code> for the active project id. <kbd>Enter</kbd> adds a new line; <kbd>Ctrl/⌘ + Enter</kbd> to run.
    </div>

    <!-- Result cards (newest first). Each one auto-runs on mount. -->
    @if (cards().length === 0) {
      <div class="cv-qcards-empty text-secondary small mt-3">
        Type a Cypher query above and press <kbd>Enter</kbd> (or click <i class="bi bi-play-fill"></i>)
        to add your first result.
      </div>
    } @else {
      <div class="cv-qcards mt-3">
        @for (card of cards(); track card.id) {
          <cv-query-card [initialCypher]="card.cypher"
                         (remove)="removeCard(card.id)"
                         (saveCypher)="onCardSaveCypher($event)" />
        }
      </div>
    }

    <!-- Fullscreen editor modal: large textarea bound to topCypher. -->
    @if (fullscreenOpen()) {
      <div class="cv-qmodal-backdrop" (click)="fullscreenOpen.set(false)"></div>
      <div class="cv-qmodal" role="dialog" aria-label="Cypher editor">
        <div class="cv-qmodal-header">
          <div class="cv-qmodal-title">
            <i class="bi bi-arrows-fullscreen me-2"></i>Cypher editor
          </div>
          <button class="btn btn-sm btn-outline-secondary" (click)="fullscreenOpen.set(false)">
            <i class="bi bi-x-lg"></i>
          </button>
        </div>
        <textarea class="form-control font-monospace cv-qmodal-textarea"
                  rows="14"
                  spellcheck="false"
                  [ngModel]="topCypher()"
                  (ngModelChange)="topCypher.set($event)"
                  (keydown.control.enter)="runTopAndCloseModal()"
                  (keydown.meta.enter)="runTopAndCloseModal()"></textarea>
        <div class="cv-qmodal-footer">
          <span class="small text-secondary">
            <kbd>Ctrl/⌘ + Enter</kbd> to run and close.
          </span>
          <div class="d-flex gap-2">
            <button class="btn btn-sm btn-outline-secondary" (click)="formatTopCypher()">
              <i class="bi bi-text-indent-left"></i> Format
            </button>
            <button class="btn btn-sm btn-outline-secondary" (click)="fullscreenOpen.set(false)">
              Close
            </button>
            <button class="btn btn-sm cv-bg-accent" (click)="runTopAndCloseModal()"
                    [disabled]="!topCypher().trim()">
              <i class="bi bi-play-fill"></i> Run
            </button>
          </div>
        </div>
      </div>
    }

  `,
  styles: [`
    /* Fill the .cv-content area as a flex column. The page header + qbar + hint stay
       fixed in their natural position; .cv-qcards takes the remaining vertical space
       and scrolls internally. This keeps .cv-content from scrolling so the top input
       never moves out of view. */
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
    }
    .cv-page-header,
    .cv-qbar,
    .cv-qbar-hint,
    .cv-qcards-empty {
      flex: 0 0 auto;
    }
    .cv-qbar {
      margin-top: 0.25rem;
      /* Right padding restores the 24px inset that cv-content drops on the /query route.
         Keeps the textarea + buttons visually aligned with the rest of the page while
         the scrolling .cv-qcards below extends flush to the right edge. */
      padding-right: 24px;
    }
    .cv-qref-toggle {
      vertical-align: middle;
      padding: 0.2rem 0.45rem;
      margin-left: 0.5rem;
      font-size: 1rem;
    }
    .cv-qref-toggle--active {
      color: var(--cv-accent);
    }
    .cv-qcards {
      flex: 1 1 auto;
      overflow-y: auto;
      min-height: 0;  /* required for flex children to actually shrink + scroll */
      /* Match the qbar's right inset so the cards visually line up with the textarea
         above. The scrollbar still rides the right edge of the page (this container
         is the scroll context) — only the card content is padded. */
      padding-right: 24px;
    }
    .cv-qbar-input-wrap {
      position: relative;
    }
    .cv-qbar-input {
      padding-right: 4.9rem;
      resize: none;
      line-height: 1.5;
      /* JS keeps the height === scrollHeight, so the textarea never needs its own scroll. */
      overflow-y: hidden;
    }
    /* Both top-bar inset buttons share the same anchored-to-top-right shape. z-index
       lifts them above the textarea's own stacking context. */
    .cv-qbar-ellipsis,
    .cv-qbar-play {
      position: absolute;
      top: 0.3rem;
      width: 2rem;
      height: 2rem;
      border: none;
      border-radius: 0.3rem;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      z-index: 2;
    }
    .cv-qbar-ellipsis {
      right: 2.35rem;
      background: transparent;
      color: var(--bs-secondary-color);
    }
    .cv-qbar-ellipsis:hover { background: var(--bs-tertiary-bg); color: var(--cv-accent); }
    .cv-qbar-ellipsis--open { background: var(--bs-tertiary-bg); color: var(--cv-accent); }
    .cv-qbar-play {
      right: 0.3rem;
      background: transparent;
      color: var(--cv-accent);
    }
    .cv-qbar-play:hover { background: var(--bs-tertiary-bg); color: var(--cv-accent-hover); }
    .cv-qbar-play:disabled { opacity: 0.55; cursor: default; background: transparent; }
    .cv-qbar-menu {
      position: absolute;
      top: calc(100% + 0.25rem);
      right: 0;
      min-width: 12rem;
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.45rem;
      padding: 0.25rem;
      box-shadow: 0 8px 24px rgba(0,0,0,0.18);
      z-index: 50;
      display: flex;
      flex-direction: column;
    }
    .cv-qbar-menu-item {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.45rem 0.65rem;
      border: none;
      background: transparent;
      color: var(--bs-body-color);
      text-align: left;
      border-radius: 0.3rem;
      font-size: 0.9rem;
      cursor: pointer;
    }
    .cv-qbar-menu-item:hover { background: var(--bs-tertiary-bg); color: var(--cv-accent); }
    .cv-qbar-hint { margin-top: 0.35rem; }

    /* Inline history panel: lives directly below the textarea container. Always rendered
       so the expand/collapse animation runs both ways; the open-state class drives the
       max-height / opacity / margin / border transition. Search input at the top;
       scrollable list of past queries below. Each row is single-line; horizontal scroll
       kicks in for long queries, vertical scroll kicks in past ~10 rows. */
    .cv-qbar-history {
      max-height: 0;
      opacity: 0;
      margin-top: 0;
      background: var(--bs-body-bg);
      border: 1px solid transparent;
      border-radius: 0.45rem;
      overflow: hidden;
      transition:
        max-height 220ms ease,
        opacity 150ms ease-out,
        margin-top 220ms ease,
        border-color 220ms ease;
      pointer-events: none;  /* unclickable when collapsed */
    }
    .cv-qbar-history--open {
      max-height: 23rem;     /* fits search input + ~10 list rows */
      opacity: 1;
      margin-top: 0.4rem;
      border-color: var(--bs-border-color);
      pointer-events: auto;
    }
    .cv-qbar-history-search {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.4rem 0.5rem;
      border-bottom: 1px solid var(--bs-border-color);
    }
    .cv-qbar-history-search > .bi-search { color: var(--bs-secondary-color); padding-left: 0.2rem; }
    .cv-qbar-history-search > input { flex: 1 1 auto; }
    .cv-qbar-history-list {
      max-height: 20rem;   /* ~10 rows; vertical scroll past that */
      overflow: auto;       /* both axes */
    }
    .cv-qbar-history-row {
      display: block;
      width: max-content;
      min-width: 100%;     /* full-width hover on short rows; long rows extend container */
      padding: 0.4rem 0.7rem;
      border: none;
      border-bottom: 1px solid var(--bs-border-color);
      background: transparent;
      color: var(--bs-body-color);
      text-align: left;
      cursor: pointer;
      white-space: nowrap;  /* single line per row */
    }
    .cv-qbar-history-row:last-child { border-bottom: none; }
    .cv-qbar-history-row:hover { background: var(--bs-tertiary-bg); color: var(--cv-accent); }
    .cv-qbar-history-cypher { display: inline-block; }
    .cv-qcards-empty {
      border: 1px dashed var(--bs-border-color);
      border-radius: 0.5rem;
      padding: 1.2rem;
      text-align: center;
      margin-right: 24px;
    }

    .cv-qmodal-backdrop {
      position: fixed; inset: 0;
      background: rgba(0,0,0,0.45);
      z-index: 1000;
    }
    .cv-qmodal {
      position: fixed;
      top: 50%; left: 50%;
      transform: translate(-50%, -50%);
      width: min(820px, 94vw);
      max-height: 86vh;
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.6rem;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
      z-index: 1001;
      display: flex; flex-direction: column;
    }
    .cv-qmodal-header {
      padding: 0.75rem 1rem;
      display: flex; align-items: center; justify-content: space-between;
      border-bottom: 1px solid var(--bs-border-color);
    }
    .cv-qmodal-title { font-weight: 600; }
    .cv-qmodal-textarea {
      flex: 1 1 auto;
      margin: 0.75rem 1rem;
      min-height: 14rem;
      resize: vertical;
    }
    .cv-qmodal-footer {
      padding: 0.6rem 1rem;
      display: flex; align-items: center; justify-content: space-between;
      gap: 0.75rem;
      border-top: 1px solid var(--bs-border-color);
    }
  `],
})
export class QueryComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly cypherRef = inject(CypherRefService);
  /** Search input inside the always-mounted history panel — focused when the panel opens. */
  private readonly historyInput = viewChild<ElementRef<HTMLInputElement>>('historyInput');

  readonly defaultCypher = DEFAULT_CYPHER;
  readonly topCypher = signal(DEFAULT_CYPHER);
  readonly cards = signal<ReadonlyArray<Card>>([]);

  private readonly topTextarea = viewChild<ElementRef<HTMLTextAreaElement>>('topTextarea');

  constructor() {
    /* Auto-size the top textarea to its content. Reset height to 'auto' so the new
       scrollHeight reads correctly even when content shrinks (otherwise scrollHeight
       stays at the previous larger value). rAF defers measurement until after the DOM
       update flushes, so the value, the panel toggle, and window resize all converge
       on a fresh scrollHeight. */
    effect(() => {
      this.topCypher();
      this.cypherRef.visible();
      const ref = this.topTextarea();
      if (!ref) return;
      const el = ref.nativeElement;
      requestAnimationFrame(() => {
        el.style.height = 'auto';
        el.style.height = `${el.scrollHeight}px`;
      });
    });
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    const el = this.topTextarea()?.nativeElement;
    if (!el) return;
    requestAnimationFrame(() => {
      el.style.height = 'auto';
      el.style.height = `${el.scrollHeight}px`;
    });
  }

  readonly menuOpen = signal(false);
  readonly fullscreenOpen = signal(false);
  readonly historySearchOpen = signal(false);

  readonly history = signal<ReadonlyArray<QueryRecord>>([]);
  readonly historyFilter = signal('');
  readonly filteredHistory = computed(() => {
    const f = this.historyFilter().trim().toLowerCase();
    const filtered = !f
      ? this.history()
      : this.history().filter((h) => h.cypher.toLowerCase().includes(f));
    // Dedupe by cypher. The history list is newest-first (entries are prepended on
    // every run / save), so keeping the first occurrence preserves the most recent
    // run for each unique query.
    const seen = new Set<string>();
    return filtered.filter((h) => {
      if (seen.has(h.cypher)) return false;
      seen.add(h.cypher);
      return true;
    });
  });

  ngOnInit(): void {
    this.refreshHistory();
    // Deep-link from the schema-graph drill-down: ?cypher=... pre-populates the top input.
    // We don't auto-run; the user clicks play to spawn a card.
    this.route.queryParamMap.subscribe((q) => {
      const c = q.get('cypher');
      if (c) this.topCypher.set(c);
    });
  }

  refreshHistory(): void {
    this.http.get<ReadonlyArray<QueryRecord>>('/api/dashboard/queries').subscribe({
      next: (list) => this.history.set(list ?? []),
      error: () => {},  // 404 when dashboard module not built; silent
    });
  }

  /**
   * Toggle menu open/closed; stop propagation so the document-click handler doesn't close
   * it on this same click. Also collapse the history panel — the ellipsis click is
   * conceptually "outside" the panel, but its stopPropagation would otherwise prevent
   * the document handler from firing.
   */
  toggleMenu(evt: Event): void {
    evt.stopPropagation();
    if (this.historySearchOpen()) {
      this.historySearchOpen.set(false);
      this.historyFilter.set('');
    }
    this.menuOpen.update((v) => !v);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  /**
   * Any click outside the ellipsis menu closes it. The inline history panel uses the same
   * outside-click pattern: its root stops propagation, so clicks landing here are by
   * definition outside the panel and should collapse it.
   */
  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.menuOpen()) this.menuOpen.set(false);
    if (this.historySearchOpen()) {
      this.historySearchOpen.set(false);
      this.historyFilter.set('');
    }
  }

  runTop(): void {
    const cypher = this.topCypher().trim();
    if (!cypher) return;
    // Each click of Run spawns a new card. Newest cards on top so the user sees the
    // most recent run without scrolling.
    const id = `c-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    this.cards.update((list) => [{ id, cypher }, ...list]);
  }

  runTopAndCloseModal(): void {
    this.runTop();
    this.fullscreenOpen.set(false);
  }

  removeCard(id: string): void {
    this.cards.update((list) => list.filter((c) => c.id !== id));
  }

  /** Save the current top-bar cypher without running it. Persists via /api/dashboard/queries. */
  saveTopCypher(): void {
    const cypher = this.topCypher().trim();
    if (!cypher) return;
    this.postSave(cypher);
  }

  /** Card emitted a save — same persistence path. */
  onCardSaveCypher(cypher: string): void {
    const trimmed = (cypher ?? '').trim();
    if (!trimmed) return;
    this.postSave(trimmed);
  }

  private postSave(cypher: string): void {
    this.http.post<QueryRecord>('/api/dashboard/queries', {
      cypher, ok: true, rowCount: 0,
    }).subscribe({
      next: (rec) => this.history.update((list) => [rec, ...list]),
      error: () => {},
    });
  }

  openHistorySearch(): void {
    this.refreshHistory();  // pick up any queries persisted since mount
    this.historyFilter.set('');
    this.historySearchOpen.set(true);
    // Defer to a microtask so the panel's expand-class is applied and the input becomes
    // focusable (pointer-events transitions from 'none' to 'auto') before we try to focus.
    queueMicrotask(() => this.historyInput()?.nativeElement.focus());
  }

  openFullscreen(): void {
    this.fullscreenOpen.set(true);
  }

  loadFromHistory(h: QueryRecord): void {
    this.topCypher.set(h.cypher);
  }

  /** Click handler for an inline-history row — populates the textarea and collapses the panel. */
  pickFromHistory(h: QueryRecord): void {
    this.topCypher.set(h.cypher);
    this.historySearchOpen.set(false);
    this.historyFilter.set('');
  }

  /** Render a multi-line cypher as a single line for the history list. */
  singleLine(cypher: string): string {
    return cypher.replace(/\s+/g, ' ').trim();
  }

  clearHistory(): void {
    this.http.delete('/api/dashboard/queries').subscribe({
      next: () => this.history.set([]),
      error: () => {},
    });
  }

  /**
   * Minimal Cypher formatter — normalizes whitespace and uppercases the major clause
   * keywords so a hand-typed query reads consistently. Stays on one line; for true
   * multi-line layout, the user can open the Full screen editor.
   */
  formatTopCypher(): void {
    const src = this.topCypher();
    if (!src.trim()) return;
    const keywords = [
      'MATCH', 'OPTIONAL MATCH', 'WHERE', 'WITH', 'RETURN', 'ORDER BY', 'LIMIT', 'SKIP',
      'CREATE', 'MERGE', 'DELETE', 'DETACH DELETE', 'SET', 'REMOVE', 'UNWIND',
      'CALL', 'YIELD', 'FOREACH', 'UNION', 'AS', 'AND', 'OR', 'NOT',
    ];
    let out = src.replace(/\s+/g, ' ').trim();
    for (const kw of keywords) {
      // Word-boundary, case-insensitive replace — uppercases the keyword in place
      // without touching identifiers that happen to contain the same letters.
      const re = new RegExp(`\\b${kw.replace(/ /g, '\\s+')}\\b`, 'gi');
      out = out.replace(re, kw);
    }
    this.topCypher.set(out);
  }
}
