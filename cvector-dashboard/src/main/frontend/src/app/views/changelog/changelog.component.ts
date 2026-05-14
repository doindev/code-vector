import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
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

interface ChangelogItem {
  readonly fqName: string;
  readonly name?: string;
  readonly lastIngestedAt: string;
}

interface ChangelogGroup {
  readonly label: string;
  readonly count: number;
  readonly truncated: boolean;
  readonly items: ReadonlyArray<ChangelogItem>;
}

interface ChangelogResponse {
  readonly project: { projectId: string; name: string };
  readonly generatedAt: string;
  readonly since: string;
  readonly limit: number;
  readonly totalTouched: number;
  readonly truncated: boolean;
  readonly groups: ReadonlyArray<ChangelogGroup>;
}

type Window = '1h' | '24h' | '7d' | '30d';

@Component({
  selector: 'cv-changelog',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Changelog</h1>
        <div class="cv-page-subtitle">
          What's changed in the last
          <span class="fw-semibold cv-accent">{{ since() }}</span>
          @if (data(); as d) {
            · <span class="fw-semibold">{{ d.totalTouched | number }}</span>
            node(s) touched across <span class="fw-semibold">{{ d.groups.length }}</span>
            label(s)
          }
        </div>
      </div>
      <div class="d-flex gap-2 align-items-center">
        <div class="btn-group btn-group-sm" role="group" aria-label="Time window">
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

    @if (data(); as d) {
      @if (d.groups.length === 0) {
        <div class="cv-surface text-center py-5 text-secondary">
          @if (loading()) { Loading… } @else {
            No nodes were touched within this window. Run
            <code>cvector scan</code> or expand the window.
          }
        </div>
      } @else {
        <div class="cv-changelog-layout">
          <aside class="cv-changelog-nav">
            <div class="text-secondary text-uppercase small fw-semibold mb-2">Labels</div>
            @for (g of d.groups; track g.label) {
              <a class="cv-changelog-nav-link"
                 [class.cv-changelog-nav-link--active]="active() === g.label"
                 (click)="setActive(g.label)">
                <span>{{ g.label }}</span>
                <span class="badge bg-secondary cv-changelog-badge">{{ g.count | number }}</span>
              </a>
            }
          </aside>
          <section class="cv-changelog-content">
            @if (activeGroup(); as g) {
              <div class="d-flex justify-content-between align-items-baseline mb-3">
                <h2 class="fs-5 fw-semibold mb-0">
                  {{ g.label }}
                  <span class="text-secondary fs-6 ms-2">{{ g.count | number }} touched</span>
                </h2>
                @if (g.truncated) {
                  <span class="badge bg-warning-subtle text-warning-emphasis small">
                    showing first {{ g.items.length }}
                  </span>
                }
              </div>
              <div class="cv-surface p-0">
                <table class="table table-hover mb-0">
                  <thead>
                    <tr>
                      <th>Symbol</th>
                      <th style="width: 14rem">Last ingested</th>
                      <th style="width: 6rem" class="text-end">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (item of g.items; track $index) {
                      <tr>
                        <td class="font-monospace small" style="word-break:break-all;">
                          {{ item.fqName || item.name }}
                        </td>
                        <td class="small text-secondary">
                          {{ item.lastIngestedAt | date: 'medium' }}
                        </td>
                        <td class="text-end">
                          <a class="btn btn-sm btn-link p-0 text-secondary me-2"
                             [routerLink]="['/explain']"
                             [queryParams]="{ symbol: item.fqName || item.name }"
                             title="Explain">
                            <i class="bi bi-info-circle"></i>
                          </a>
                          <a class="btn btn-sm btn-link p-0 text-secondary"
                             [routerLink]="['/graph']"
                             [queryParams]="{ symbol: item.fqName || item.name }"
                             title="Open in Graph">
                            <i class="bi bi-diagram-3"></i>
                          </a>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>
        </div>
      }

      <div class="small text-secondary mt-3 d-flex justify-content-between">
        <span>Generated {{ d.generatedAt | date: 'medium' }}</span>
        <span>Live via SSE · fallback poll 60s</span>
      </div>
    } @else if (!error()) {
      <div class="cv-surface text-center py-5 text-secondary">Loading…</div>
    }
  `,
  styles: [
    `
      .cv-changelog-layout {
        display: grid;
        grid-template-columns: 14rem 1fr;
        gap: 1rem;
        align-items: start;
      }
      @media (max-width: 768px) {
        .cv-changelog-layout {
          grid-template-columns: 1fr;
        }
      }
      .cv-changelog-nav {
        position: sticky;
        top: 1rem;
        background: var(--bs-body-bg);
        border: 1px solid var(--bs-border-color);
        border-radius: 0.5rem;
        padding: 0.75rem;
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .cv-changelog-nav-link {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.35rem 0.55rem;
        border-radius: 0.35rem;
        font-size: 0.875rem;
        color: var(--bs-body-color);
        cursor: pointer;
        user-select: none;
      }
      .cv-changelog-nav-link:hover {
        background: var(--bs-tertiary-bg);
      }
      .cv-changelog-nav-link--active {
        background: var(--cv-accent, #e0592d);
        color: #fff;
      }
      .cv-changelog-nav-link--active .cv-changelog-badge {
        background: rgba(255, 255, 255, 0.2) !important;
      }
      .cv-changelog-badge {
        font-size: 0.7rem;
        font-weight: 500;
      }
      .cv-changelog-content table th {
        position: sticky;
        top: 0;
        background: var(--bs-body-bg);
        z-index: 1;
      }
    `,
  ],
})
export class ChangelogComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stream = inject(EventStreamService);

  readonly windows: ReadonlyArray<Window> = ['1h', '24h', '7d', '30d'];

  readonly since = signal<Window>('7d');
  readonly data = signal<ChangelogResponse | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly active = signal<string>('');

  readonly activeGroup = computed<ChangelogGroup | undefined>(() => {
    const d = this.data();
    if (!d) return undefined;
    return d.groups.find((g) => g.label === this.active()) ?? d.groups[0];
  });

  ngOnInit(): void {
    this.refresh();
    this.stream
      .on('graph-mutated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
    // Backend caches /api/changelog at 30s TTL and SSE handles freshness, so 60s
    // visible-only fallback poll is plenty.
    visiblePoll(60_000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  setWindow(w: Window): void {
    this.since.set(w);
    this.refresh();
  }

  setActive(label: string): void {
    this.active.set(label);
  }

  refresh(): void {
    this.loading.set(true);
    const params = new HttpParams().set('since', this.since()).set('limit', '5000');
    this.http.get<ChangelogResponse>('/api/changelog', { params }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
        // Keep the current selection if still present; otherwise default to the first group.
        const groups = d?.groups ?? [];
        if (!groups.find((g) => g.label === this.active())) {
          this.active.set(groups[0]?.label ?? '');
        }
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load changelog');
        this.loading.set(false);
      },
    });
  }
}
