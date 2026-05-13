import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Router } from '@angular/router';

interface NavCommand {
  readonly kind: 'nav';
  readonly label: string;
  readonly path: string;
  readonly icon: string;
  readonly hint: string;
}

interface SymbolCommand {
  readonly kind: 'symbol';
  readonly id: string;
  readonly label: string;     // graph label (Method, Class, ...)
  readonly fqName: string;
  readonly name?: string;
}

type Command = NavCommand | SymbolCommand;

const NAV_COMMANDS: ReadonlyArray<NavCommand> = [
  { kind: 'nav', label: 'Overview',    path: '/',            icon: 'bi-grid-1x2',          hint: 'home' },
  { kind: 'nav', label: 'Projects',    path: '/projects',    icon: 'bi-collection',        hint: 'workspace + switch' },
  { kind: 'nav', label: 'Monitors',    path: '/monitors',    icon: 'bi-eye',               hint: 'live watchers' },
  { kind: 'nav', label: 'Schedules',   path: '/schedules',   icon: 'bi-clock-history',     hint: 'cron jobs' },
  { kind: 'nav', label: 'Recent',      path: '/recent',      icon: 'bi-activity',          hint: 'latest ingest' },
  { kind: 'nav', label: 'Changelog',   path: '/changelog',   icon: 'bi-journal-arrow-up',  hint: 'grouped recent' },
  { kind: 'nav', label: 'Guard',       path: '/guard',       icon: 'bi-shield-check',      hint: 'rule gate' },
  { kind: 'nav', label: 'Rules',       path: '/rules',       icon: 'bi-clipboard-check',   hint: 'arch rules' },
  { kind: 'nav', label: 'Health',      path: '/health',      icon: 'bi-heart-pulse',       hint: 'complexity' },
  { kind: 'nav', label: 'Services',    path: '/services',    icon: 'bi-hdd-network',       hint: 'cross-service edges' },
  { kind: 'nav', label: 'Flows',       path: '/flows',       icon: 'bi-signpost-split',    hint: 'entry traces' },
  { kind: 'nav', label: 'Audit',       path: '/audit',       icon: 'bi-shield-exclamation',hint: 'OSV vulns' },
  { kind: 'nav', label: 'Communities', path: '/communities', icon: 'bi-diagram-2',         hint: 'method clusters' },
  { kind: 'nav', label: 'Duplicates',  path: '/duplicates',  icon: 'bi-files',             hint: 'same-shape methods' },
  { kind: 'nav', label: 'Wiki',        path: '/wiki',        icon: 'bi-journal-text',      hint: 'project doc' },
  { kind: 'nav', label: 'Explorer',    path: '/explorer',    icon: 'bi-diagram-3',         hint: 'schema graph' },
  { kind: 'nav', label: 'Query',       path: '/query',       icon: 'bi-terminal',          hint: 'cypher' },
  { kind: 'nav', label: 'Graph',       path: '/graph',       icon: 'bi-share',             hint: 'symbol slice' },
  { kind: 'nav', label: 'Explain',     path: '/explain',     icon: 'bi-info-circle',       hint: 'drill into symbol' },
  { kind: 'nav', label: 'Impact',      path: '/impact',      icon: 'bi-bullseye',          hint: 'downstream reach' },
  { kind: 'nav', label: 'Trace',       path: '/trace',       icon: 'bi-signpost-2',        hint: 'shortest path A→B' },
  { kind: 'nav', label: 'DB impact',   path: '/db-impact',   icon: 'bi-database',          hint: 'table/column readers' },
  { kind: 'nav', label: 'Rename',      path: '/rename',      icon: 'bi-input-cursor-text', hint: 'blast radius' },
  { kind: 'nav', label: 'PR impact',   path: '/pr-impact',   icon: 'bi-git',               hint: 'branch diff impact' },
  { kind: 'nav', label: 'Migrate',     path: '/migrate',     icon: 'bi-arrow-right-square',hint: 'migration plan' },
  { kind: 'nav', label: 'Diff',        path: '/diff',        icon: 'bi-arrow-left-right',  hint: 'commit-to-commit drift' },
  { kind: 'nav', label: 'Doctor',      path: '/doctor',      icon: 'bi-clipboard-pulse',   hint: 'health checks' },
  { kind: 'nav', label: 'Settings',    path: '/settings',    icon: 'bi-gear',              hint: '' },
];

@Component({
  selector: 'cv-command-palette',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div class="cv-palette-backdrop" (click)="close()">
        <div class="cv-palette" (click)="$event.stopPropagation()">
          <div class="cv-palette-input">
            <i class="bi bi-command text-secondary"></i>
            <input #input
                   type="text"
                   class="form-control form-control-lg border-0 shadow-none"
                   placeholder="Type a command or symbol…"
                   [(ngModel)]="query"
                   (ngModelChange)="onQueryChange($event)"
                   (keydown)="onKeydown($event)" />
            <kbd class="cv-palette-hint">esc</kbd>
          </div>
          <div class="cv-palette-results">
            @if (sections().nav.length > 0) {
              <div class="cv-palette-section">
                <div class="cv-palette-section-title">Navigate</div>
                @for (c of sections().nav; track c.path) {
                  <button class="cv-palette-result"
                          [class.cv-palette-result--active]="activeIndex() === indexOf(c)"
                          (mouseenter)="setActive(c)"
                          (click)="pick(c)">
                    <i class="bi" [class]="c.icon"></i>
                    <span class="fw-semibold">{{ c.label }}</span>
                    <span class="text-secondary small ms-1">{{ c.hint }}</span>
                    <span class="text-secondary small ms-auto">{{ c.path }}</span>
                  </button>
                }
              </div>
            }
            @if (sections().symbols.length > 0) {
              <div class="cv-palette-section">
                <div class="cv-palette-section-title">Symbols</div>
                @for (s of sections().symbols; track s.id) {
                  <button class="cv-palette-result"
                          [class.cv-palette-result--active]="activeIndex() === indexOf(s)"
                          (mouseenter)="setActive(s)"
                          (click)="pick(s)">
                    <i class="bi bi-braces"></i>
                    <span class="badge bg-secondary me-1">{{ s.label }}</span>
                    <span class="font-monospace small">{{ s.fqName || s.name }}</span>
                    <span class="text-secondary small ms-auto">Explain</span>
                  </button>
                }
              </div>
            }
            @if (sections().nav.length === 0 && sections().symbols.length === 0) {
              <div class="cv-palette-empty text-secondary small text-center py-4">
                @if (loading()) { Searching… } @else { No matches. }
              </div>
            }
          </div>
          <div class="cv-palette-footer text-secondary small">
            <span><kbd>↑ ↓</kbd> navigate</span>
            <span><kbd>↵</kbd> open</span>
            <span><kbd>esc</kbd> close</span>
            <span class="ms-auto">Open anytime with <kbd>{{ shortcut }}</kbd></span>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .cv-palette-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.45);
      display: flex;
      align-items: flex-start;
      justify-content: center;
      padding-top: 10vh;
      z-index: 2000;
    }
    .cv-palette {
      width: min(640px, 92vw);
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.5rem;
      box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
      overflow: hidden;
      display: flex;
      flex-direction: column;
      max-height: 70vh;
    }
    .cv-palette-input {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      border-bottom: 1px solid var(--bs-border-color);
    }
    .cv-palette-input input {
      flex: 1 1 auto;
      background: transparent;
    }
    .cv-palette-input input:focus { outline: none; }
    .cv-palette-hint {
      background: var(--bs-tertiary-bg);
      border-radius: 0.25rem;
      padding: 0.1rem 0.4rem;
      font-size: 0.7rem;
      color: var(--bs-secondary-color);
    }
    .cv-palette-results {
      overflow-y: auto;
      flex: 1 1 auto;
      padding: 0.25rem 0;
    }
    .cv-palette-section-title {
      padding: 0.4rem 0.75rem 0.15rem;
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--bs-secondary-color);
      font-weight: 600;
    }
    .cv-palette-result {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      width: 100%;
      padding: 0.45rem 0.75rem;
      background: transparent;
      border: none;
      color: var(--bs-body-color);
      text-align: left;
      cursor: pointer;
    }
    .cv-palette-result i.bi { width: 1.25rem; text-align: center; opacity: 0.8; }
    .cv-palette-result:hover, .cv-palette-result--active {
      background: var(--bs-tertiary-bg);
    }
    .cv-palette-result--active {
      box-shadow: inset 3px 0 0 var(--cv-accent, #e0592d);
    }
    .cv-palette-footer {
      display: flex;
      gap: 1rem;
      padding: 0.4rem 0.75rem;
      border-top: 1px solid var(--bs-border-color);
      background: var(--bs-tertiary-bg);
    }
    .cv-palette-footer kbd {
      background: var(--bs-body-bg);
      border: 1px solid var(--bs-border-color);
      border-radius: 0.25rem;
      padding: 0 0.3rem;
      font-size: 0.7rem;
    }
  `],
})
export class CommandPaletteComponent {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  @ViewChild('input') private inputEl?: ElementRef<HTMLInputElement>;

  readonly open = signal(false);
  readonly query = signal('');
  readonly loading = signal(false);
  readonly symbolResults = signal<ReadonlyArray<SymbolCommand>>([]);
  readonly activeIndex = signal(0);

  // Mac users expect ⌘K; everyone else gets Ctrl+K. We detect via platform once.
  readonly shortcut = /Mac|iP(hone|ad|od)/.test(navigator.platform) ? '⌘ K' : 'Ctrl K';

  readonly sections = computed(() => {
    const q = this.query().trim().toLowerCase();
    const nav = q
      ? NAV_COMMANDS.filter((c) =>
          c.label.toLowerCase().includes(q) ||
          c.path.includes(q) ||
          c.hint.toLowerCase().includes(q))
      : [...NAV_COMMANDS];
    return { nav, symbols: this.symbolResults() };
  });

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  @HostListener('document:keydown', ['$event'])
  onGlobalKeydown(e: KeyboardEvent): void {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      this.toggle();
    } else if (e.key === 'Escape' && this.open()) {
      e.preventDefault();
      this.close();
    }
  }

  toggle(): void {
    if (this.open()) { this.close(); }
    else { this.openPalette(); }
  }

  openPalette(): void {
    this.open.set(true);
    this.query.set('');
    this.symbolResults.set([]);
    this.activeIndex.set(0);
    // Focus after Angular paints. setTimeout(0) is enough; using requestAnimationFrame
    // would also work but adds 16ms latency.
    setTimeout(() => this.inputEl?.nativeElement.focus(), 0);
  }

  close(): void {
    this.open.set(false);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
  }

  onQueryChange(q: string): void {
    this.activeIndex.set(0);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (q.trim().length < 2) {
      this.symbolResults.set([]);
      this.loading.set(false);
      return;
    }
    this.searchTimer = setTimeout(() => this.runSymbolSearch(q.trim()), 180);
  }

  private runSymbolSearch(q: string): void {
    this.loading.set(true);
    const params = new HttpParams().set('q', q);
    this.http.get<{ results: ReadonlyArray<SymbolCommand> }>('/api/search', { params }).subscribe({
      next: (r) => {
        const hits = (r.results ?? []).slice(0, 12).map((s) => ({ ...s, kind: 'symbol' as const }));
        this.symbolResults.set(hits);
        this.loading.set(false);
      },
      error: () => {
        this.symbolResults.set([]);
        this.loading.set(false);
      },
    });
  }

  onKeydown(e: KeyboardEvent): void {
    const all = this.flat();
    if (all.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.activeIndex.set((this.activeIndex() + 1) % all.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.activeIndex.set((this.activeIndex() - 1 + all.length) % all.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      this.pick(all[this.activeIndex()]);
    }
  }

  private flat(): ReadonlyArray<Command> {
    return [...this.sections().nav, ...this.sections().symbols];
  }

  indexOf(c: Command): number {
    return this.flat().indexOf(c);
  }

  setActive(c: Command): void {
    const idx = this.indexOf(c);
    if (idx >= 0) this.activeIndex.set(idx);
  }

  pick(c: Command): void {
    if (c.kind === 'nav') {
      this.router.navigateByUrl(c.path);
    } else {
      this.router.navigate(['/explain'], { queryParams: { symbol: c.fqName || c.name } });
    }
    this.close();
  }
}
