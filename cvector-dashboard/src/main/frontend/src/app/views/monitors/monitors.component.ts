import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
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
            <label class="form-label small text-secondary">Absolute path</label>
            <div class="input-group">
              <input class="form-control"
                     [ngModel]="newPath()"
                     (ngModelChange)="newPath.set($event)"
                     (keydown.enter)="add()"
                     placeholder="C:\\path\\to\\project" />
              <button class="btn btn-outline-secondary" type="button"
                      (click)="pickerOpen.set(true)"
                      title="Browse for a folder">
                <i class="bi bi-folder2-open"></i>
              </button>
            </div>
          </div>
          <div class="col-md-3 d-flex gap-2">
            <button class="btn btn-primary flex-grow-1" (click)="add()"
                    [disabled]="!newPath().trim() || saving()">
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

    <cv-folder-picker [open]="pickerOpen()" [initialPath]="newPath()"
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
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  /** Latest /status response. Used to render per-row file-event counters + live badges. */
  readonly status = signal<StatusPayload | null>(null);

  ngOnInit(): void {
    this.refresh();
    this.refreshStatus();
    // 3-second polling for live counters. Component-scoped via takeUntilDestroyed -- no
    // explicit unsubscribe needed; Angular tears down with the view.
    visiblePoll(3000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshStatus());
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
  }
}
