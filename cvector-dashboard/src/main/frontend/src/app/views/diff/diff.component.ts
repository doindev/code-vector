import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';

interface DiffRun {
  finishedAt?: string;
  exitCode?: number;
  shaA?: string;
  shaB?: string;
  output?: string;
}

interface DiffStatus {
  running: boolean;
  pid?: number;
  startedAt?: string;
  elapsedMillis?: number;
  shaA?: string;
  shaB?: string;
  partialOutput?: string;
  outputBytes?: number;
  last?: DiffRun;
}

@Component({
  selector: 'cv-diff',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Diff</h1>
        <div class="cv-page-subtitle">
          Architecture drift between two git commits — runs as a background subprocess
        </div>
      </div>
      @if (status(); as s) {
        @if (s.running) {
          <span class="badge cv-bg-accent">
            <i class="bi bi-arrow-repeat"></i>
            Running ({{ ((s.elapsedMillis ?? 0) / 1000) | number: '1.0-0' }}s)
          </span>
        } @else if (s.last?.exitCode !== undefined) {
          <span class="badge"
                [class.bg-secondary]="s.last?.exitCode === 0"
                [class.text-bg-danger]="s.last?.exitCode !== 0">
            Last diff exit {{ s.last?.exitCode }}
          </span>
        }
      }
    </div>

    <div class="cv-surface mb-3">
      <form class="row g-2 align-items-end" (ngSubmit)="run()">
        <div class="col-md-4">
          <label class="form-label small text-secondary mb-1">Base SHA / ref</label>
          <input class="form-control form-control-sm font-monospace"
                 [(ngModel)]="shaA" name="shaA" placeholder="e.g. main or HEAD~5" required />
        </div>
        <div class="col-md-4">
          <label class="form-label small text-secondary mb-1">Target SHA / ref</label>
          <input class="form-control form-control-sm font-monospace"
                 [(ngModel)]="shaB" name="shaB" placeholder="e.g. HEAD or feature-branch" required />
        </div>
        <div class="col-md-2 d-flex flex-column gap-1">
          <label class="form-label small text-secondary mb-1">Options</label>
          <div class="form-check form-check-inline small">
            <input class="form-check-input" type="checkbox"
                   id="includeCalls" [(ngModel)]="includeCalls" name="includeCalls" />
            <label class="form-check-label" for="includeCalls">--include-calls</label>
          </div>
          <div class="form-check form-check-inline small">
            <input class="form-check-input" type="checkbox"
                   id="keep" [(ngModel)]="keep" name="keep" />
            <label class="form-check-label" for="keep">--keep</label>
          </div>
        </div>
        <div class="col-md-2">
          <button class="btn btn-sm cv-bg-accent w-100" type="submit" [disabled]="busy()">
            <i class="bi" [class.bi-arrow-right]="!busy()" [class.bi-arrow-repeat]="busy()"></i>
            Run diff
          </button>
        </div>
      </form>
      @if (error()) {
        <div class="alert alert-warning small mt-2 mb-0">{{ error() }}</div>
      }
    </div>

    @if (status(); as s) {
      @if (s.running) {
        <div class="cv-surface">
          <h2 class="fs-6 fw-semibold mb-2">
            <i class="bi bi-arrow-repeat"></i>
            Diffing
            <code>{{ s.shaA }}</code> ↔ <code>{{ s.shaB }}</code>
          </h2>
          <div class="small text-secondary mb-2">
            PID {{ s.pid }} · elapsed {{ ((s.elapsedMillis ?? 0) / 1000) | number: '1.0-0' }}s
            · {{ (s.outputBytes ?? 0) | number }}b captured
          </div>
          <pre class="cv-diff-output">{{ s.partialOutput || '(no output yet — scanning worktrees…)' }}</pre>
        </div>
      } @else if (s.last) {
        <div class="cv-surface">
          <h2 class="fs-6 fw-semibold mb-2">
            <i class="bi bi-check2-circle text-success"></i>
            Last diff
            <code>{{ s.last.shaA }}</code> ↔ <code>{{ s.last.shaB }}</code>
            <span class="small text-secondary ms-2">
              exit {{ s.last.exitCode }} · finished {{ s.last.finishedAt }}
            </span>
          </h2>
          <pre class="cv-diff-output">{{ s.last.output }}</pre>
        </div>
      } @else {
        <div class="cv-surface text-secondary text-center py-5">
          No diff has run yet. Provide two SHAs above and hit "Run diff".
        </div>
      }
    }
  `,
  styles: [
    `.cv-diff-output {
       max-height: 60vh;
       overflow: auto;
       background: var(--bs-tertiary-bg);
       border-radius: 0.35rem;
       padding: 0.75rem;
       font-size: 0.8rem;
       white-space: pre;
       line-height: 1.35;
     }`,
  ],
})
export class DiffComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  shaA = 'HEAD~1';
  shaB = 'HEAD';
  includeCalls = false;
  keep = false;

  readonly status = signal<DiffStatus | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);

  ngOnInit(): void {
    this.refresh();
    // 2s while a diff is in flight (worktree scans take minutes; the dashboard needs
    // a live tail), 10s after it finishes.
    visiblePoll(2000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  run(): void {
    if (!this.shaA || !this.shaB) {
      this.error.set('Both SHAs are required.');
      return;
    }
    this.error.set('');
    this.busy.set(true);
    this.http
      .post<{ ok?: boolean; reason?: string; message?: string }>('/api/dashboard/diff', {
        shaA: this.shaA,
        shaB: this.shaB,
        includeCalls: this.includeCalls,
        keep: this.keep,
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.refresh();
        },
        error: (err) => {
          const reason = err?.error?.reason;
          const msg = err?.error?.message;
          this.error.set(reason ? `${reason}${msg ? ': ' + msg : ''}` : (err?.message ?? 'Failed to start diff'));
          this.busy.set(false);
        },
      });
  }

  refresh(): void {
    this.http
      .get<DiffStatus>('/api/dashboard/diff/status')
      .subscribe({
        next: (s) => this.status.set(s),
        error: () => { /* polling failures are normal during boot */ },
      });
  }
}
