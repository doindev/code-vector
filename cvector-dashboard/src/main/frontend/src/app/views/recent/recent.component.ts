import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';
import { EventStreamService } from '../../core/event-stream.service';

interface RecentRow {
  readonly label: string;
  readonly fqName: string;
  readonly name?: string;
  readonly lastIngestedAt: string;
}

type Window = '30m' | '1h' | '24h' | '7d' | '30d';

@Component({
  selector: 'cv-recent',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Recently ingested</h1>
        <div class="cv-page-subtitle">
          Nodes touched by the latest scan(s) — last
          <span class="fw-semibold cv-accent">{{ since() }}</span>
        </div>
      </div>
      <div class="d-flex gap-2 align-items-center">
        <div class="btn-group btn-group-sm" role="group">
          @for (w of windows; track w) {
            <button class="btn btn-outline-secondary"
                    [class.active]="since() === w"
                    (click)="setWindow(w)">{{ w }}</button>
          }
        </div>
        <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
      </div>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    <div class="cv-surface p-0">
      <table class="table table-hover mb-0">
        <thead>
          <tr>
            <th style="width:9rem">Label</th>
            <th>fqName</th>
            <th style="width:14rem">Last ingested</th>
            <th style="width:4rem"></th>
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track $index) {
            <tr>
              <td><span class="badge bg-secondary">{{ row.label }}</span></td>
              <td class="font-monospace small" style="word-break:break-all;">{{ row.fqName }}</td>
              <td class="small text-secondary">{{ row.lastIngestedAt | date: 'medium' }}</td>
              <td class="text-end">
                <a class="btn btn-sm btn-link p-0 text-secondary"
                   [routerLink]="['/graph']"
                   [queryParams]="{ symbol: row.fqName || row.name }"
                   title="Open in Graph">
                  <i class="bi bi-diagram-3"></i>
                </a>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="4" class="text-secondary text-center py-4">
                @if (loading()) { Loading… } @else { No nodes ingested in this window. }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>

    <div class="small text-secondary mt-2 d-flex justify-content-between">
      <span>{{ rows().length | number }} row(s)</span>
      <span>Live via SSE &middot; fallback poll 30s</span>
    </div>
  `,
})
export class RecentComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stream = inject(EventStreamService);

  readonly windows: ReadonlyArray<Window> = ['30m', '1h', '24h', '7d', '30d'];

  readonly since = signal<Window>('24h');
  readonly rows = signal<ReadonlyArray<RecentRow>>([]);
  readonly loading = signal(true);
  readonly error = signal('');

  ngOnInit(): void {
    this.refresh();
    // SSE: a scan or schedule landing graph data publishes graph-mutated; refresh
    // immediately so the "recent" rows include the freshly ingested nodes.
    this.stream.on('graph-mutated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
    // Fallback poll: relaxed from 10 s to 30 s now that SSE handles the hot path.
    visiblePoll(30_000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  setWindow(w: Window): void {
    this.since.set(w);
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    const params = new HttpParams().set('since', this.since()).set('limit', '100');
    this.http.get<ReadonlyArray<RecentRow>>('/api/recent', { params }).subscribe({
      next: (list) => { this.rows.set(list ?? []); this.loading.set(false); },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load recent nodes');
        this.loading.set(false);
      },
    });
  }
}
