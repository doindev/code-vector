import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';

interface ProjectMeta {
  readonly projectId: string;
  readonly name: string;
  readonly rootPath: string;
  readonly backend: string;
}

interface WikiSection {
  readonly id: string;
  readonly title: string;
  readonly kind: 'table';
  readonly columns: ReadonlyArray<string>;
  readonly rows: ReadonlyArray<Record<string, unknown>>;
  readonly count: number;
}

interface WikiResponse {
  readonly project: ProjectMeta;
  readonly generatedAt: string;
  readonly totals: { nodes: number; edges: number };
  readonly sections: ReadonlyArray<WikiSection>;
}

@Component({
  selector: 'cv-wiki',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Project wiki</h1>
        <div class="cv-page-subtitle">
          Generated documentation derived from the active project graph
        </div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      <div class="cv-wiki-meta cv-surface mb-3">
        <div class="row g-3">
          <div class="col-md-3">
            <div class="text-secondary small text-uppercase">Project</div>
            <div class="fw-semibold">{{ d.project.name }}</div>
            <div class="font-monospace small text-secondary">{{ d.project.projectId }}</div>
          </div>
          <div class="col-md-3">
            <div class="text-secondary small text-uppercase">Backend</div>
            <div class="fw-semibold">{{ d.project.backend }}</div>
          </div>
          <div class="col-md-3">
            <div class="text-secondary small text-uppercase">Nodes</div>
            <div class="fw-semibold cv-accent">{{ d.totals.nodes | number }}</div>
          </div>
          <div class="col-md-3">
            <div class="text-secondary small text-uppercase">Edges</div>
            <div class="fw-semibold cv-accent">{{ d.totals.edges | number }}</div>
          </div>
        </div>
        <div class="small text-secondary mt-2">
          <i class="bi bi-clock-history"></i>
          Generated {{ d.generatedAt | date: 'medium' }} · root
          <code>{{ d.project.rootPath }}</code>
        </div>
      </div>

      <div class="cv-wiki-layout">
        <aside class="cv-wiki-nav">
          <div class="text-secondary text-uppercase small fw-semibold mb-2">Sections</div>
          @for (s of d.sections; track s.id) {
            <a class="cv-wiki-nav-link"
               [class.cv-wiki-nav-link--active]="active() === s.id"
               (click)="setActive(s.id)">
              <span>{{ s.title }}</span>
              <span class="badge bg-secondary cv-wiki-badge">{{ s.count | number }}</span>
            </a>
          }
        </aside>
        <section class="cv-wiki-content">
          @if (activeSection(); as s) {
            <h2 class="fs-5 fw-semibold mb-3">{{ s.title }}</h2>
            @if (s.rows.length === 0) {
              <p class="text-secondary small mb-0">No rows.</p>
            } @else {
              <div class="cv-surface p-0">
                <table class="table table-hover mb-0 small">
                  <thead>
                    <tr>
                      @for (c of s.columns; track c) {
                        <th>{{ c }}</th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of s.rows; track $index) {
                      <tr>
                        @for (c of s.columns; track c) {
                          <td [class.font-monospace]="isMonospace(c)"
                              [class.text-end]="isNumeric(row[c])">
                            {{ format(row[c]) }}
                          </td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          }
        </section>
      </div>
    } @else if (!error()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">Loading wiki…</div>
      </div>
    }
  `,
  styles: [`
    .cv-wiki-layout {
      display: grid;
      grid-template-columns: 14rem 1fr;
      gap: 1rem;
      align-items: start;
    }
    @media (max-width: 768px) {
      .cv-wiki-layout {
        grid-template-columns: 1fr;
      }
    }
    .cv-wiki-nav {
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
    .cv-wiki-nav-link {
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
    .cv-wiki-nav-link:hover {
      background: var(--bs-tertiary-bg);
    }
    .cv-wiki-nav-link--active {
      background: var(--cv-accent, #e0592d);
      color: #fff;
    }
    .cv-wiki-nav-link--active .cv-wiki-badge {
      background: rgba(255, 255, 255, 0.2) !important;
    }
    .cv-wiki-badge {
      font-size: 0.7rem;
      font-weight: 500;
    }
    .cv-wiki-content table th {
      position: sticky;
      top: 0;
      background: var(--bs-body-bg);
      z-index: 1;
    }
  `],
})
export class WikiComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly data = signal<WikiResponse | null>(null);
  readonly error = signal('');
  readonly active = signal<string>('graph-nodes');

  readonly activeSection = computed<WikiSection | undefined>(() => {
    const d = this.data();
    if (!d) return undefined;
    return d.sections.find((s) => s.id === this.active()) ?? d.sections[0];
  });

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.error.set('');
    this.http.get<WikiResponse>('/api/wiki').subscribe({
      next: (d) => this.data.set(d),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load wiki'),
    });
  }

  setActive(id: string): void { this.active.set(id); }

  isMonospace(column: string): boolean {
    return /path|class|key|name|group|artifact|table/i.test(column);
  }

  isNumeric(value: unknown): boolean {
    return typeof value === 'number';
  }

  format(value: unknown): string {
    if (value == null) return '';
    if (typeof value === 'number') return value.toLocaleString();
    return String(value);
  }
}
