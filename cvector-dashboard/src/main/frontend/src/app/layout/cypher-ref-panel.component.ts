import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnInit,
  ViewEncapsulation,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

import { CypherRefService } from '../core/cypher-ref.service';

/**
 * Slide-out side panel that hosts the Cypher language reference. The HTML is a static
 * asset (originally Neo4j's reference doc, ~1.7 MB) that uses native &lt;details&gt;/
 * &lt;summary&gt; elements for the accordion behaviour, so once injected via [innerHTML]
 * the browser handles expand/collapse on its own.
 *
 * <p>The asset references third-party CSS classes we don't ship, so the visual polish
 * is approximated here with element-level rules that keep the structure readable: section
 * headings, code blocks, lists, links.
 */
@Component({
  selector: 'cv-cypher-ref-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  /* Emulated encapsulation rewrites every selector to require the component's
     [_ngcontent] attribute on both the ancestor and descendant — but content injected
     via [innerHTML] never gets that attribute, so scoped rules don't reach the
     Cypher-reference markup. Encapsulation.None turns scoping off; we keep leakage
     in check by prefixing every selector with the component's host tag. */
  encapsulation: ViewEncapsulation.None,
  template: `
    <div class="cv-crp-header">
      <span class="cv-crp-title">
        <i class="bi bi-book me-1"></i> Cypher Reference
      </span>
      @if (allExpanded()) {
        <button class="btn btn-sm btn-link text-secondary cv-crp-btn"
                (click)="collapseAll()"
                title="Collapse all sections">
          <i class="bi bi-chevron-double-up"></i>
        </button>
      } @else {
        <button class="btn btn-sm btn-link text-secondary cv-crp-btn"
                (click)="expandAll()"
                title="Expand all sections">
          <i class="bi bi-chevron-double-down"></i>
        </button>
      }
      <button class="btn btn-sm btn-link text-secondary cv-crp-btn cv-crp-close"
              (click)="svc.close()"
              title="Close">
        <i class="bi bi-x-lg"></i>
      </button>
    </div>
    <div class="cv-crp-search">
      <i class="bi bi-search"></i>
      <input class="form-control form-control-sm"
             type="search"
             placeholder="Filter sections…"
             [value]="filter()"
             (input)="onFilterInput($event)" />
    </div>
    <div class="cv-crp-body" #body>
      @if (loading()) {
        <div class="text-secondary small p-3">Loading…</div>
      } @else if (error()) {
        <div class="alert alert-warning small m-2">{{ error() }}</div>
      } @else {
        <div [innerHTML]="content()"></div>
      }
    </div>
  `,
  styles: [`
    cv-cypher-ref-panel {
      display: flex;
      flex-direction: column;
      height: 100%;
      width: 320px;
      background: var(--bs-body-bg);
      border-right: 1px solid var(--cv-sidebar-border);
      overflow: hidden;
    }
    cv-cypher-ref-panel .cv-crp-header {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.5rem 0.6rem;
      border-bottom: 1px solid var(--cv-sidebar-border);
    }
    cv-cypher-ref-panel .cv-crp-title {
      flex: 1 1 auto;
      font-weight: 600;
      font-size: 0.95rem;
    }
    cv-cypher-ref-panel .cv-crp-btn { padding: 0.25rem 0.4rem; }
    cv-cypher-ref-panel .cv-crp-close { margin-left: 0.25rem; }
    cv-cypher-ref-panel .cv-crp-search {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.4rem 0.6rem;
      border-bottom: 1px solid var(--cv-sidebar-border);
    }
    cv-cypher-ref-panel .cv-crp-search > .bi-search { color: var(--bs-secondary-color); padding-left: 0.15rem; }
    cv-cypher-ref-panel .cv-crp-search > input { flex: 1 1 auto; }
    cv-cypher-ref-panel .cv-crp-body {
      flex: 1 1 auto;
      overflow-y: auto;
      /* CSS resolves overflow-y:auto + overflow-x:visible to auto on both axes, which
         produced an unwanted horizontal scrollbar. Hide horizontal overflow here and
         force the children that would overflow (pre / code) to wrap below. */
      overflow-x: hidden;
      word-break: break-word;
      overflow-wrap: anywhere;
      padding: 0.5rem 0.6rem;
      font-size: 0.88rem;
      line-height: 1.45;
    }
    /* Minimal styling for the injected Neo4j reference markup — the source uses
       custom utility classes we don't ship, so we paint baseline structure here. */
    cv-cypher-ref-panel .cv-crp-body h3 {
      font-size: 0.95rem;
      font-weight: 600;
      margin: 0.6rem 0 0.3rem;
      color: var(--cv-accent);
    }
    cv-cypher-ref-panel .cv-crp-body details {
      margin: 0.1rem 0;
    }
    cv-cypher-ref-panel .cv-crp-body summary {
      cursor: pointer;
      padding: 0.3rem 0.4rem 0.3rem 0;
      border-radius: 0.3rem;
      list-style: none;
      /* Flex with space-between pushes the ::after caret to the right edge while the
         summary text (a <p>) stays at the left. Hidden SVG / button children inside
         the source markup are display:none and so don't occupy flex slots. */
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.4rem;
    }
    cv-cypher-ref-panel .cv-crp-body summary::-webkit-details-marker { display: none; }
    cv-cypher-ref-panel .cv-crp-body summary::after {
      content: '\\25B6';
      display: inline-block;
      flex: 0 0 auto;
      margin-left: auto;
      font-size: 0.65rem;
      transition: transform 120ms ease;
      color: var(--bs-secondary-color);
    }
    cv-cypher-ref-panel .cv-crp-body details[open] > summary::after {
      /* 90deg rotates ▶ to ▼ */
      transform: rotate(90deg);
    }
    cv-cypher-ref-panel .cv-crp-body summary:hover {
      background: var(--bs-tertiary-bg);
    }
    cv-cypher-ref-panel .cv-crp-body summary p {
      display: inline;
      margin: 0;
    }
    cv-cypher-ref-panel .cv-crp-body details[open] > summary {
      font-weight: 600;
    }
    cv-cypher-ref-panel .cv-crp-body details details {
      margin-left: 0.8rem;
      padding-left: 0.5rem;
      border-left: 1px solid var(--cv-sidebar-border);
    }
    cv-cypher-ref-panel .cv-crp-body pre {
      background: var(--bs-tertiary-bg);
      padding: 0.4rem 0.6rem;
      border-radius: 0.3rem;
      /* Wrap long code lines instead of overflowing to a scrollbar — keeps the panel
         scrollbar-free horizontally while every byte of the snippet stays visible. */
      white-space: pre-wrap;
      word-break: break-word;
      overflow-wrap: anywhere;
      overflow-x: visible;
      font-size: 0.78rem;
      margin: 0.3rem 0;
    }
    cv-cypher-ref-panel .cv-crp-body code {
      background: var(--bs-tertiary-bg);
      padding: 0.05em 0.3em;
      border-radius: 0.25em;
      font-size: 0.85em;
    }
    cv-cypher-ref-panel .cv-crp-body pre code {
      background: transparent;
      padding: 0;
    }
    cv-cypher-ref-panel .cv-crp-body ul {
      margin: 0.3rem 0 0.4rem;
      padding-left: 1.2rem;
    }
    cv-cypher-ref-panel .cv-crp-body li { margin: 0.1rem 0; }
    cv-cypher-ref-panel .cv-crp-body a { color: var(--cv-accent); text-decoration: none; }
    cv-cypher-ref-panel .cv-crp-body a:hover { text-decoration: underline; }
    cv-cypher-ref-panel .cv-crp-body p { margin: 0.3rem 0; }
    /* Inline SVGs ship in the source with no width/height attribute, so the browser
       falls back to a default 300×150 viewport — huge. Hide them; we don't need them. */
    cv-cypher-ref-panel .cv-crp-body svg {
      display: none !important;
    }
    /* Source's inline copy / expand-all buttons — hide them; our header has its own. */
    cv-cypher-ref-panel .cv-crp-body button {
      display: none !important;
    }
    cv-cypher-ref-panel .cv-crp-body .section-header { font-size: 0.88rem; }
  `],
})
export class CypherRefPanelComponent implements OnInit {
  readonly svc = inject(CypherRefService);
  private readonly http = inject(HttpClient);
  private readonly sanitizer = inject(DomSanitizer);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly content = signal<SafeHtml>('');
  readonly filter = signal('');
  /** True after the user clicks "expand all" — controls which header button is shown. */
  readonly allExpanded = signal(false);

  private readonly bodyRef = viewChild<ElementRef<HTMLDivElement>>('body');

  ngOnInit(): void {
    this.http.get('assets/cypher-reference.html', { responseType: 'text' }).subscribe({
      next: (html) => {
        this.content.set(this.sanitizer.bypassSecurityTrustHtml(this.stripSourceChrome(html)));
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load Cypher reference.');
        this.loading.set(false);
      },
    });
  }

  /**
   * The source asset embeds Neo4j's own header (title + expand-all + search input). We
   * render our own equivalent, so this strips that block before the content reaches the
   * DOM. DOMParser handles nested-tag matching that a regex would butcher.
   */
  private stripSourceChrome(html: string): string {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const node = doc.querySelector('div.px-4.pb-2.pt-3\\.5');
    if (node) node.remove();
    return doc.body.innerHTML;
  }

  expandAll(): void {
    this.eachDetails((d) => (d.open = true));
    this.allExpanded.set(true);
  }

  collapseAll(): void {
    this.eachDetails((d) => (d.open = false));
    this.allExpanded.set(false);
  }

  onFilterInput(evt: Event): void {
    const v = (evt.target as HTMLInputElement).value;
    this.filter.set(v);
    this.applyFilter(v);
  }

  /**
   * Substring-matches against each &lt;details&gt;' text content. Matches are revealed
   * and expanded; non-matches are hidden. Clearing the filter restores everything.
   */
  private applyFilter(q: string): void {
    const root = this.bodyRef()?.nativeElement;
    if (!root) return;
    const needle = q.trim().toLowerCase();
    const detailsList = root.querySelectorAll<HTMLDetailsElement>('details');
    if (!needle) {
      detailsList.forEach((d) => {
        d.style.display = '';
        d.open = false;
      });
      return;
    }
    detailsList.forEach((d) => {
      const text = (d.textContent ?? '').toLowerCase();
      const hit = text.includes(needle);
      d.style.display = hit ? '' : 'none';
      if (hit) d.open = true;
    });
  }

  private eachDetails(fn: (d: HTMLDetailsElement) => void): void {
    const root = this.bodyRef()?.nativeElement;
    if (!root) return;
    root.querySelectorAll<HTMLDetailsElement>('details').forEach(fn);
  }

  /** Block runaway clicks from collapsing the panel via the shell's main-area handler. */
  @HostListener('click', ['$event'])
  onClick(evt: Event): void {
    evt.stopPropagation();
  }
}
