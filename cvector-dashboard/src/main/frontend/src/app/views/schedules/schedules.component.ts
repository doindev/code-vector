import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';
import { EventStreamService } from '../../core/event-stream.service';

interface Schedule {
  readonly id: string;
  readonly name: string;
  readonly cron: string;
  readonly action: string;
  readonly enabled: boolean;
}

interface ScheduleRuntime {
  readonly registered: boolean;
  readonly running: boolean;
  readonly pid?: number;
  readonly exitCode?: number;
}

interface SchedulesStatusPayload {
  readonly activeTriggers: number;
  readonly lastFireStartedAt: string | null;
  readonly schedules: Record<string, ScheduleRuntime>;
}

@Component({
  selector: 'cv-schedules',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Schedules</h1>
        <div class="cv-page-subtitle">Cron-driven scans and diff checks</div>
      </div>
      <button class="btn cv-bg-accent" (click)="showAdd.set(!showAdd())">
        <i class="bi bi-plus-lg"></i> Add schedule
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (showAdd()) {
      <div class="cv-surface mb-3">
        <div class="row g-2">
          <div class="col-md-4">
            <label class="form-label small text-secondary">Name</label>
            <input class="form-control"
                   [ngModel]="newName()"
                   (ngModelChange)="newName.set($event)"
                   placeholder="Nightly scan" />
          </div>
          <div class="col-md-3">
            <label class="form-label small text-secondary">Cron</label>
            <input class="form-control font-monospace"
                   [ngModel]="newCron()"
                   (ngModelChange)="newCron.set($event)"
                   placeholder="0 2 * * *" />
          </div>
          <div class="col-md-3">
            <label class="form-label small text-secondary">Action</label>
            <select class="form-select"
                    [ngModel]="newAction()"
                    (ngModelChange)="newAction.set($event)">
              <option value="scan">scan</option>
              <option value="scan-incremental">scan:incremental</option>
              <option value="diff">diff</option>
            </select>
          </div>
          <div class="col-md-2 d-flex align-items-end">
            <button class="btn btn-primary w-100" (click)="add()"
                    [disabled]="!newName().trim() || !newCron().trim() || saving()">
              {{ saving() ? 'Saving…' : 'Save' }}
            </button>
          </div>
        </div>
        <div class="form-text mt-2">
          Cron syntax: 5-field standard (minute, hour, day-of-month, month, day-of-week).
        </div>
      </div>
    }

    <div class="cv-surface p-0">
      <table class="table table-hover mb-0">
        <thead>
          <tr>
            <th>Name</th>
            <th>Cron</th>
            <th>Action</th>
            <th>Status</th>
            <th style="width:6rem"></th>
          </tr>
        </thead>
        <tbody>
          @for (s of schedules(); track s.id) {
            <tr>
              <td>{{ s.name }}</td>
              <td class="font-monospace small">{{ s.cron }}</td>
              <td><span class="badge bg-secondary">{{ s.action }}</span></td>
              <td>
                @if (runtimeFor(s.id); as rt) {
                  @if (rt.running) {
                    <span class="badge cv-bg-accent">
                      <i class="bi bi-arrow-repeat"></i> Running ({{ rt.pid }})
                    </span>
                  } @else if (s.enabled && rt.registered) {
                    <span class="badge cv-bg-accent">
                      <i class="bi bi-clock"></i> Enabled
                    </span>
                  } @else {
                    <span class="badge bg-secondary">Paused</span>
                  }
                } @else {
                  <span class="badge" [class]="s.enabled ? 'cv-bg-accent' : 'bg-secondary'">
                    {{ s.enabled ? 'Enabled' : 'Paused' }}
                  </span>
                }
              </td>
              <td class="text-end text-nowrap">
                <button class="btn btn-sm btn-outline-primary me-1" (click)="runNow(s.id)"
                        [disabled]="firingId() === s.id"
                        title="Run now">
                  <i class="bi" [class]="firingId() === s.id ? 'bi-arrow-repeat' : 'bi-lightning-fill'"></i>
                </button>
                <button class="btn btn-sm btn-outline-secondary me-1" (click)="toggle(s.id)">
                  <i class="bi" [class]="s.enabled ? 'bi-pause' : 'bi-play'"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger" (click)="remove(s.id)">
                  <i class="bi bi-trash"></i>
                </button>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="text-secondary text-center py-4">
                @if (loading()) { Loading… } @else { No schedules configured. }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>

    <p class="small text-secondary mt-3 mb-0">
      <i class="bi bi-info-circle"></i>
      Persisted at <code>~/.cvector/dashboard.json</code>. Cron execution (firing scans on the recorded schedule) is the next iteration.
    </p>
  `,
})
export class SchedulesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stream = inject(EventStreamService);

  readonly schedules = signal<ReadonlyArray<Schedule>>([]);
  readonly showAdd = signal(false);
  readonly newName = signal('');
  readonly newCron = signal('0 2 * * *');
  readonly newAction = signal('scan-incremental');
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  /** ID of a schedule that's currently mid-fire so we can render a spinner. */
  readonly firingId = signal<string | null>(null);
  /** Latest scheduler status. Used to render "running" + last-fire indicators. */
  readonly status = signal<SchedulesStatusPayload | null>(null);

  ngOnInit(): void {
    this.refresh();
    this.refreshStatus();
    // SSE: refresh schedule runner status the instant any scan-status event fires —
    // both manual ScanRunner triggers AND schedule-fired subprocesses publish these,
    // so the "running/pid" indicators update without polling.
    this.stream.on('scan-status')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshStatus());
    // Fallback poll at a slower cadence than the original 3 s — only catches gaps
    // where the EventSource connection isn't healthy.
    visiblePoll(20000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshStatus());
  }

  refreshStatus(): void {
    this.http.get<SchedulesStatusPayload>('/api/dashboard/schedules/status').subscribe({
      next: (s) => this.status.set(s),
      error: () => {},
    });
  }

  runtimeFor(id: string): ScheduleRuntime | null {
    return this.status()?.schedules[id] ?? null;
  }

  refresh(): void {
    this.loading.set(true);
    this.http.get<ReadonlyArray<Schedule>>('/api/dashboard/schedules').subscribe({
      next: (list) => { this.schedules.set(list ?? []); this.loading.set(false); },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load schedules');
        this.loading.set(false);
      },
    });
  }

  add(): void {
    const name = this.newName().trim();
    const cron = this.newCron().trim();
    if (!name || !cron) return;
    this.saving.set(true);
    this.http.post<Schedule>('/api/dashboard/schedules', {
      name, cron, action: this.newAction(),
    }).subscribe({
      next: (s) => {
        this.schedules.update((list) => [...list, s]);
        this.newName.set('');
        this.showAdd.set(false);
        this.saving.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to add schedule');
        this.saving.set(false);
      },
    });
  }

  toggle(id: string): void {
    this.http.post<Schedule>(`/api/dashboard/schedules/${id}/toggle`, {}).subscribe({
      next: (updated) => {
        this.schedules.update((list) => list.map((s) => (s.id === id ? updated : s)));
      },
    });
  }

  remove(id: string): void {
    this.http.delete(`/api/dashboard/schedules/${id}`).subscribe({
      next: () => this.schedules.update((list) => list.filter((s) => s.id !== id)),
    });
  }

  runNow(id: string): void {
    this.firingId.set(id);
    this.http.post(`/api/dashboard/schedules/${id}/run`, {}).subscribe({
      next: () => {
        // The backend spawns a subprocess and returns immediately; show the spinner for a
        // beat so the user sees their click registered, then clear. Polling will then
        // pick up the "running" state from the status endpoint.
        setTimeout(() => { this.firingId.set(null); this.refreshStatus(); }, 800);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to trigger schedule');
        this.firingId.set(null);
      },
    });
  }
}
