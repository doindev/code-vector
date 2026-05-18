import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';
import { FolderPickerComponent } from '../../shared/folder-picker.component';

interface MonitoredPath {
  readonly id: string;
  readonly path: string;
  readonly enabled: boolean;
  readonly addedAt: string;
}

interface ProjectsResponse {
  readonly active?: {
    readonly projectId?: string;
    readonly name?: string;
    readonly rootPath?: string;
  };
}

interface WatcherStats {
  readonly filesProcessed: number;
  readonly deletes: number;
  readonly failures: number;
}

interface StatusPayload {
  readonly active: number;
  readonly watchers: Record<string, WatcherStats>;
}

@Component({
  selector: 'cv-monitors',
  standalone: true,
  imports: [FormsModule, DecimalPipe, FolderPickerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Monitored directories</h1>
        <div class="cv-page-subtitle">
          Directories cvector watches for changes
          @if (status(); as st) {
            <span class="badge bg-secondary ms-2">{{ st.active }} live watcher(s)</span>
          }
        </div>
      </div>
      <button class="btn cv-bg-accent" (click)="showAdd.set(!showAdd())">
        <i class="bi bi-plus-lg"></i> Add
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (showAdd()) {
      <div class="cv-surface mb-3">
        <div class="row g-2 align-items-end">
          <div class="col-md-9">
            <label class="form-label small text-secondary">
              Absolute path
              @if (projectRoot()) {
                <span class="ms-2 text-secondary">
                  · must be inside <code>{{ projectRoot() }}</code>
                </span>
              }
            </label>
            <div class="input-group">
              <input class="form-control"
                     name="newPath"
                     [ngModel]="newPath()"
                     (ngModelChange)="onPathTyped($event)"
                     (keydown.enter)="add()"
                     [placeholder]="projectRoot() || 'C:\\path\\to\\project'" />
              <button class="btn btn-outline-secondary" type="button"
                      (click)="pickerOpen.set(true)"
                      title="Browse for a folder">
                <i class="bi bi-folder2-open"></i>
              </button>
            </div>
            @if (newPath() && projectRoot() && !pathInsideProject()) {
              <div class="form-text text-warning">
                <i class="bi bi-exclamation-triangle"></i>
                This path is outside the active project — the monitor will be rejected.
              </div>
            }
            @if (pathCheck() === 'checking') {
              <div class="form-text text-secondary">
                <i class="bi bi-arrow-repeat"></i> verifying path…
              </div>
            } @else if (pathCheck() === 'invalid' && pathError()) {
              <div class="form-text text-danger">
                <i class="bi bi-x-circle"></i> {{ pathError() }}
              </div>
            } @else if (pathCheck() === 'valid' && newPath().trim()) {
              <div class="form-text text-success">
                <i class="bi bi-check-circle"></i> path exists on the server
              </div>
            }
          </div>
          <div class="col-md-3 d-flex gap-2">
            <button class="btn btn-primary flex-grow-1" (click)="add()"
                    [disabled]="!canAdd()">
              {{ saving() ? 'Saving…' : 'Add monitor' }}
            </button>
          </div>
        </div>
      </div>
    }

    <div class="cv-surface p-0">
      <table class="table table-hover mb-0">
        <thead>
          <tr>
            <th style="width:45%">Path</th>
            <th>Status</th>
            <th>Files processed</th>
            <th>Deletes</th>
            <th>Added</th>
            <th style="width:6rem"></th>
          </tr>
        </thead>
        <tbody>
          @for (m of monitors(); track m.id) {
            <tr>
              <td class="font-monospace small">{{ m.path }}</td>
              <td>
                @if (m.enabled) {
                  @if (statsFor(m.id); as s) {
                    <span class="badge cv-bg-accent">
                      <i class="bi bi-broadcast"></i> Running
                    </span>
                  } @else {
                    <span class="badge bg-secondary">
                      Enabled (offline)
                    </span>
                  }
                } @else {
                  <span class="badge bg-secondary">Paused</span>
                }
              </td>
              <td class="font-monospace small">
                @if (statsFor(m.id); as s) {
                  {{ s.filesProcessed | number }}
                  @if (s.failures > 0) {
                    <span class="text-warning ms-1" [title]="s.failures + ' transient failures'">
                      <i class="bi bi-exclamation-triangle-fill"></i>
                    </span>
                  }
                } @else {
                  <span class="text-secondary">—</span>
                }
              </td>
              <td class="font-monospace small">
                @if (statsFor(m.id); as s) {
                  {{ s.deletes | number }}
                } @else {
                  <span class="text-secondary">—</span>
                }
              </td>
              <td class="small text-secondary">{{ formatDate(m.addedAt) }}</td>
              <td class="text-end text-nowrap">
                <button class="btn btn-sm btn-outline-secondary me-1" (click)="toggle(m.id)"
                        title="Pause / resume">
                  <i class="bi" [class]="m.enabled ? 'bi-pause' : 'bi-play'"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger" (click)="remove(m.id)" title="Remove">
                  <i class="bi bi-trash"></i>
                </button>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="text-secondary text-center py-4">
                @if (loading()) { Loading… } @else { No monitors configured yet. }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>

    <p class="small text-secondary mt-3 mb-0">
      <i class="bi bi-info-circle"></i>
      Persisted at <code>~/.cvector/dashboard.json</code>. Counters update every 3 seconds.
    </p>

    <cv-folder-picker [open]="pickerOpen()"
                      [initialPath]="newPath() || projectRoot()"
                      (selected)="onPickerSelect($event)"
                      (cancel)="pickerOpen.set(false)" />
  `,
})
export class MonitorsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly monitors = signal<ReadonlyArray<MonitoredPath>>([]);
  readonly showAdd = signal(false);
  readonly newPath = signal('');
  readonly pickerOpen = signal(false);
  /** Active project root — gates which paths the backend will accept as monitor targets. */
  readonly projectRoot = signal<string>('');
  /** True when the typed path is the project root or a descendant. Pure visual prefix check. */
  readonly pathInsideProject = computed(() => {
    const root = this.projectRoot();
    const path = this.newPath().trim();
    if (!root || !path) return true;  // nothing to flag yet
    const sep = root.includes('\\') ? '\\' : '/';
    const normRoot = root.replace(/[\\/]+$/, '');
    return path === normRoot || path.startsWith(normRoot + sep);
  });
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  /** Latest /status response. Used to render per-row file-event counters + live badges. */
  readonly status = signal<StatusPayload | null>(null);

  /**
   * Live path-existence state for the inline input. {@code idle} = no input or not yet
   * checked; {@code checking} = a debounced /fs/list probe is in flight; {@code valid} =
   * backend confirmed the path exists and is a directory; {@code invalid} = path doesn't
   * exist or isn't a directory (see {@link #pathError} for the server's reason). Used to
   * render the inline feedback under the input and to gate the Add button.
   */
  readonly pathCheck = signal<'idle' | 'checking' | 'valid' | 'invalid'>('idle');
  readonly pathError = signal<string>('');
  /** Cancels in-flight check timer so the latest typing wins. */
  private pathCheckTimer: ReturnType<typeof setTimeout> | null = null;
  /** Tracks which input value the in-flight check corresponds to so a stale response can't overwrite a newer one. */
  private pathCheckTarget = '';

  /** Add button gating: non-empty + done checking + valid (+ if a project root is known, inside it). */
  readonly canAdd = computed(() => {
    if (this.saving()) return false;
    const p = this.newPath().trim();
    if (!p) return false;
    if (this.pathCheck() !== 'valid') return false;
    if (this.projectRoot() && !this.pathInsideProject()) return false;
    return true;
  });

  ngOnInit(): void {
    this.refresh();
    this.refreshStatus();
    this.fetchProjectRoot();
    // 3-second polling for live counters. Component-scoped via takeUntilDestroyed -- no
    // explicit unsubscribe needed; Angular tears down with the view.
    visiblePoll(3000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshStatus());
  }

  private fetchProjectRoot(): void {
    this.http.get<ProjectsResponse>('/api/projects').subscribe({
      next: (r) => this.projectRoot.set(r?.active?.rootPath ?? ''),
      error: () => {},  // non-fatal — backend still gates the path
    });
  }

  refresh(): void {
    this.loading.set(true);
    this.http.get<ReadonlyArray<MonitoredPath>>('/api/dashboard/monitors').subscribe({
      next: (list) => { this.monitors.set(list ?? []); this.loading.set(false); },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load monitors');
        this.loading.set(false);
      },
    });
  }

  refreshStatus(): void {
    this.http.get<StatusPayload>('/api/dashboard/monitors/status').subscribe({
      next: (s) => this.status.set(s),
      // 404 means dashboard module not built; silent
      error: () => {},
    });
  }

  /** Returns null when the watcher isn't running (paused, errored at start, or path missing). */
  statsFor(id: string): WatcherStats | null {
    const s = this.status();
    if (!s) return null;
    return s.watchers[id] ?? null;
  }

  add(): void {
    const path = this.newPath().trim();
    if (!path) return;
    this.saving.set(true);
    this.http.post<MonitoredPath>('/api/dashboard/monitors', { path }).subscribe({
      next: (m) => {
        this.monitors.update((list) => [...list, m]);
        this.newPath.set('');
        this.showAdd.set(false);
        this.saving.set(false);
        this.refreshStatus();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to add monitor');
        this.saving.set(false);
      },
    });
  }

  toggle(id: string): void {
    this.http.post<MonitoredPath>(`/api/dashboard/monitors/${id}/toggle`, {}).subscribe({
      next: (updated) => {
        this.monitors.update((list) => list.map((m) => (m.id === id ? updated : m)));
        this.refreshStatus();
      },
    });
  }

  remove(id: string): void {
    this.http.delete(`/api/dashboard/monitors/${id}`).subscribe({
      next: () => {
        this.monitors.update((list) => list.filter((m) => m.id !== id));
        this.refreshStatus();
      },
    });
  }

  formatDate(iso: string): string {
    try { return new Date(iso).toLocaleDateString(); } catch { return iso; }
  }

  onPickerSelect(path: string): void {
    this.newPath.set(path);
    this.pickerOpen.set(false);
    // Picker already navigated successfully to this path, so we know it exists. Skip the
    // round-trip and mark valid directly — the operator gets immediate feedback.
    this.pathCheck.set('valid');
    this.pathError.set('');
  }

  /**
   * Two-way binding handler for the path input. Updates the signal immediately so the
   * displayed value tracks typing 1:1, then debounces a backend validation probe so we
   * don't hammer {@code /fs/list} on every keystroke. The probe resolves to either
   * {@code valid} (path exists + is a directory) or {@code invalid} (404 / wrong type /
   * permission denied) with the server's message surfaced inline. The button can't be
   * clicked until the probe lands a {@code valid} verdict, so the operator can't submit
   * a typo and get an opaque rejection from the monitor-add endpoint downstream.
   */
  onPathTyped(value: string): void {
    this.newPath.set(value);
    this.error.set('');  // clear any "Failed to add monitor" leftover
    const trimmed = value.trim();
    if (this.pathCheckTimer) clearTimeout(this.pathCheckTimer);
    if (!trimmed) {
      this.pathCheck.set('idle');
      this.pathError.set('');
      return;
    }
    this.pathCheck.set('checking');
    this.pathCheckTarget = trimmed;
    // 350 ms debounce — quick enough to feel reactive, slow enough that a fast typist
    // doesn't trigger a probe per character.
    this.pathCheckTimer = setTimeout(() => this.verifyPath(trimmed), 350);
  }

  private verifyPath(path: string): void {
    const params = new URLSearchParams({ path });
    this.http.get<{ path: string; parent: string | null; items: unknown[] }>(
      `/api/dashboard/fs/list?${params.toString()}`,
    ).subscribe({
      next: () => {
        // Guard against stale responses overwriting a newer probe's result.
        if (this.pathCheckTarget !== path) return;
        this.pathCheck.set('valid');
        this.pathError.set('');
      },
      error: (err) => {
        if (this.pathCheckTarget !== path) return;
        const msg = err?.error?.error
            ?? err?.error?.message
            ?? err?.message
            ?? 'path could not be verified';
        this.pathCheck.set('invalid');
        this.pathError.set(msg);
      },
    });
  }
}
