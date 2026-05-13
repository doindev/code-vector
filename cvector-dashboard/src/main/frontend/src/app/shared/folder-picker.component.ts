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
import { HttpClient } from '@angular/common/http';

interface DirEntry {
  readonly name: string;
  readonly path: string;
  readonly hidden: boolean;
}

interface ListResponse {
  readonly path: string;
  readonly parent: string | null;
  readonly items: ReadonlyArray<DirEntry>;
}

interface RootsResponse {
  readonly home: string;
  readonly roots: ReadonlyArray<{ readonly name: string; readonly path: string }>;
}

/**
 * Modal dialog that walks the server-side filesystem one directory at a time and emits the
 * absolute path of the user's pick. Browsers don't expose real paths from `<input type="file">`,
 * so a server-side picker is the only way to populate the monitor / scan path fields.
 *
 * <p>Usage: drop {@code <cv-folder-picker [open]="signal" (selected)="..." (cancel)="..."/>}
 * into the parent template. The parent controls open/close via the {@code open} input.
 */
@Component({
  selector: 'cv-folder-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open) {
      <div class="cv-fp-backdrop" (click)="onCancel()"></div>
      <div class="cv-fp-modal" role="dialog" aria-label="Pick a folder">
        <div class="cv-fp-header">
          <div class="cv-fp-title">
            <i class="bi bi-folder2-open me-2"></i>Pick a folder
          </div>
          <button class="btn btn-sm btn-outline-secondary" (click)="onCancel()"
                  aria-label="Close">
            <i class="bi bi-x-lg"></i>
          </button>
        </div>

        <div class="cv-fp-toolbar">
          <button class="btn btn-sm btn-outline-secondary me-2" (click)="goUp()"
                  [disabled]="!canGoUp()" title="Up one level">
            <i class="bi bi-arrow-up"></i>
          </button>
          <button class="btn btn-sm btn-outline-secondary me-2" (click)="goHome()"
                  [title]="'Home: ' + (home() || '~')">
            <i class="bi bi-house"></i>
          </button>
          <input class="form-control form-control-sm font-monospace cv-fp-pathbox"
                 [value]="currentPath()"
                 (keydown.enter)="navigateTo(pathInput.value)"
                 (input)="onPathInput($event)"
                 #pathInput
                 placeholder="Type a path and press Enter" />
        </div>

        @if (showRoots()) {
          <div class="cv-fp-roots">
            <div class="cv-fp-section-title">Drives</div>
            @for (r of roots(); track r.path) {
              <button class="cv-fp-row" (click)="navigateTo(r.path)">
                <i class="bi bi-hdd"></i><span>{{ r.name }}</span>
              </button>
            }
          </div>
        }

        <div class="cv-fp-list">
          @if (loading()) {
            <div class="cv-fp-status">Loading…</div>
          } @else if (error()) {
            <div class="cv-fp-status text-warning">
              <i class="bi bi-exclamation-triangle me-1"></i>{{ error() }}
            </div>
          } @else if (entries().length === 0) {
            <div class="cv-fp-status text-secondary">(no subfolders)</div>
          } @else {
            @for (e of entries(); track e.path) {
              <button class="cv-fp-row" [class.cv-fp-row--hidden]="e.hidden"
                      (click)="navigateTo(e.path)"
                      (dblclick)="navigateTo(e.path)">
                <i class="bi bi-folder-fill"></i><span>{{ e.name }}</span>
              </button>
            }
          }
        </div>

        <div class="cv-fp-footer">
          <div class="small text-secondary cv-fp-current font-monospace">
            {{ currentPath() || '—' }}
          </div>
          <div class="d-flex gap-2">
            <button class="btn btn-sm btn-outline-secondary" (click)="onCancel()">Cancel</button>
            <button class="btn btn-sm btn-primary" (click)="onSelect()"
                    [disabled]="!currentPath()">
              <i class="bi bi-check2"></i> Select this folder
            </button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .cv-fp-backdrop {
      position: fixed; inset: 0;
      background: rgba(0, 0, 0, 0.45);
      z-index: 1000;
    }
    .cv-fp-modal {
      position: fixed;
      top: 50%; left: 50%;
      transform: translate(-50%, -50%);
      width: min(720px, 92vw);
      max-height: 78vh;
      background: var(--bs-body-bg);
      border: 1px solid var(--cv-sidebar-border);
      border-radius: 0.6rem;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
      z-index: 1001;
      display: flex; flex-direction: column;
    }
    .cv-fp-header {
      padding: 0.75rem 1rem;
      display: flex; align-items: center; justify-content: space-between;
      border-bottom: 1px solid var(--cv-sidebar-border);
    }
    .cv-fp-title { font-weight: 600; }
    .cv-fp-toolbar {
      padding: 0.6rem 1rem;
      display: flex; align-items: center;
      border-bottom: 1px solid var(--cv-sidebar-border);
    }
    .cv-fp-pathbox { flex: 1 1 auto; }
    .cv-fp-roots {
      padding: 0.5rem 0.6rem 0;
      max-height: 7.5rem;
      overflow-y: auto;
      border-bottom: 1px dashed var(--cv-sidebar-border);
    }
    .cv-fp-section-title {
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--bs-secondary-color);
      padding: 0.2rem 0.4rem 0.3rem;
    }
    .cv-fp-list {
      flex: 1 1 auto;
      overflow-y: auto;
      padding: 0.4rem 0.6rem;
    }
    .cv-fp-status { padding: 1rem; text-align: center; }
    .cv-fp-row {
      display: flex; align-items: center; gap: 0.6rem;
      width: 100%;
      padding: 0.4rem 0.6rem;
      border: none;
      background: transparent;
      color: var(--bs-body-color);
      text-align: left;
      border-radius: 0.35rem;
      cursor: pointer;
      font-size: 0.9rem;
    }
    .cv-fp-row i { color: var(--cv-accent); }
    .cv-fp-row:hover { background: var(--bs-tertiary-bg); }
    .cv-fp-row--hidden { opacity: 0.55; }
    .cv-fp-footer {
      padding: 0.6rem 1rem;
      display: flex; align-items: center; justify-content: space-between;
      gap: 0.75rem;
      border-top: 1px solid var(--cv-sidebar-border);
    }
    .cv-fp-current {
      flex: 1 1 auto;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `],
})
export class FolderPickerComponent implements OnInit {
  /** Controls visibility. Bind from parent and flip true to open the modal. */
  @Input() open = false;
  /** Optional initial path. If empty, opens at the user's home directory. */
  @Input() initialPath = '';

  @Output() selected = new EventEmitter<string>();
  @Output() cancel = new EventEmitter<void>();

  private readonly http = inject(HttpClient);

  readonly currentPath = signal<string>('');
  readonly entries = signal<ReadonlyArray<DirEntry>>([]);
  readonly parent = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly roots = signal<ReadonlyArray<{ name: string; path: string }>>([]);
  readonly home = signal<string>('');
  /** True when there's no current path yet, so we show the drive list as the entry point. */
  readonly showRoots = signal(true);

  ngOnInit(): void {
    // Fetch drives once. /list with the start path then loads contents.
    this.http.get<RootsResponse>('/api/dashboard/fs/roots').subscribe({
      next: (r) => {
        this.roots.set([...(r.roots ?? [])]);
        this.home.set(r.home ?? '');
        // Open at the configured initialPath if provided; otherwise jump straight to home so
        // the user sees content immediately instead of an empty roots-only screen.
        const start = this.initialPath?.trim() || r.home;
        if (start) this.navigateTo(start);
      },
      error: () => this.error.set('Failed to load drives'),
    });
  }

  canGoUp(): boolean {
    return !!this.parent();
  }

  goUp(): void {
    const p = this.parent();
    if (p) this.navigateTo(p);
  }

  goHome(): void {
    const h = this.home();
    if (h) this.navigateTo(h);
  }

  onPathInput(_evt: Event): void {
    // No-op; the input is the displayed currentPath, but we only navigate on Enter so the
    // user can type freely without firing requests on every keystroke.
  }

  navigateTo(path: string): void {
    const trimmed = (path ?? '').trim();
    if (!trimmed) return;
    this.loading.set(true);
    this.error.set('');
    this.showRoots.set(false);
    const params = new URLSearchParams({ path: trimmed });
    this.http.get<ListResponse>(`/api/dashboard/fs/list?${params.toString()}`).subscribe({
      next: (r) => {
        this.currentPath.set(r.path);
        this.parent.set(r.parent);
        this.entries.set([...(r.items ?? [])]);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.error?.error ?? err?.message ?? 'Failed to list directory');
        this.loading.set(false);
      },
    });
  }

  onSelect(): void {
    const p = this.currentPath();
    if (!p) return;
    this.selected.emit(p);
  }

  onCancel(): void {
    this.cancel.emit();
  }
}
