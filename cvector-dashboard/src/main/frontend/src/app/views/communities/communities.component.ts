import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { RouterLink } from '@angular/router';

interface Community {
  readonly id: number;
  readonly rank: number;
  readonly size: number;
  readonly internalEdges: number;
  readonly cohesion: number;
  readonly members: ReadonlyArray<string>;
  readonly truncated: boolean;
}

interface CommunitiesResponse {
  readonly algorithm: string;
  readonly project: { projectId: string; name: string };
  readonly methodCount: number;
  readonly edgeCount: number;
  readonly modularity: number | null;
  readonly communities: ReadonlyArray<Community>;
  readonly totalAboveMinSize: number;
}

type Algorithm = 'leiden' | 'louvain' | 'connected-components';

@Component({
  selector: 'cv-communities',
  standalone: true,
  imports: [FormsModule, DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Method communities</h1>
        <div class="cv-page-subtitle">
          Functional clusters detected in the Method&rarr;Method CALLS subgraph
        </div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    <div class="cv-surface mb-3">
      <div class="d-flex gap-3 align-items-center flex-wrap">
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small text-secondary mb-0">Algorithm</label>
          <select class="form-select form-select-sm" style="width:10rem"
                  [(ngModel)]="algorithm" (ngModelChange)="refresh()">
            @for (a of algorithms; track a) {
              <option [ngValue]="a">{{ a }}</option>
            }
          </select>
        </div>
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small text-secondary mb-0">Min size</label>
          <select class="form-select form-select-sm" style="width:5rem"
                  [(ngModel)]="minSize" (ngModelChange)="refresh()">
            @for (m of minSizes; track m) {
              <option [ngValue]="m">{{ m }}</option>
            }
          </select>
        </div>
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small text-secondary mb-0">Show</label>
          <select class="form-select form-select-sm" style="width:5rem"
                  [(ngModel)]="limit" (ngModelChange)="refresh()">
            @for (l of limits; track l) {
              <option [ngValue]="l">{{ l }}</option>
            }
          </select>
        </div>
        @if (loading()) {
          <span class="small text-secondary">
            <span class="spinner-border spinner-border-sm me-1"></span>
            Computing&hellip;
          </span>
        }
      </div>
      @if (error()) {
        <div class="alert alert-warning small mt-2 mb-0">{{ error() }}</div>
      }
    </div>

    @if (data(); as d) {
      <div class="cv-communities-summary cv-surface mb-3">
        <div class="row g-3">
          <div class="col-md-3">
            <div class="text-secondary text-uppercase small">Methods</div>
            <div class="fs-4 fw-semibold">{{ d.methodCount | number }}</div>
          </div>
          <div class="col-md-3">
            <div class="text-secondary text-uppercase small">CALLS edges</div>
            <div class="fs-4 fw-semibold">{{ d.edgeCount | number }}</div>
          </div>
          <div class="col-md-3">
            <div class="text-secondary text-uppercase small">Communities (&ge; {{ minSize() }})</div>
            <div class="fs-4 fw-semibold cv-accent">{{ d.totalAboveMinSize | number }}</div>
          </div>
          <div class="col-md-3">
            <div class="text-secondary text-uppercase small">Modularity</div>
            <div class="fs-4 fw-semibold">
              {{ d.modularity !== null ? (d.modularity | number: '1.4-4') : '—' }}
            </div>
          </div>
        </div>
      </div>

      @if (d.communities.length === 0) {
        <div class="cv-surface text-secondary text-center py-4">
          No communities at this minimum size.
        </div>
      } @else {
        <div class="row g-3">
          @for (c of d.communities; track c.id) {
            <div class="col-lg-6">
              <div class="cv-surface h-100">
                <div class="d-flex align-items-center gap-2 mb-2">
                  <span class="badge cv-community-badge"
                        [style.background]="colorFor(c.id)">#{{ c.rank }}</span>
                  <span class="fw-semibold">Community {{ c.id }}</span>
                  <span class="badge bg-secondary ms-auto">{{ c.size | number }} method(s)</span>
                </div>
                <div class="small text-secondary mb-2">
                  internal edges: <span class="font-monospace">{{ c.internalEdges | number }}</span>
                  &middot; cohesion:
                  <span class="cv-community-bar">
                    <span class="cv-community-fill" [style.width.%]="c.cohesion * 100"></span>
                  </span>
                  <span class="font-monospace">{{ c.cohesion | number: '1.3-3' }}</span>
                </div>
                <ul class="cv-community-members">
                  @for (m of c.members; track $index) {
                    <li>
                      <a class="font-monospace small cv-link"
                         [routerLink]="['/explain']"
                         [queryParams]="{ symbol: m }">{{ m }}</a>
                    </li>
                  }
                  @if (c.truncated) {
                    <li class="small text-secondary">… and more</li>
                  }
                </ul>
              </div>
            </div>
          }
        </div>
      }

      <p class="small text-secondary mt-3 mb-0">
        <i class="bi bi-info-circle"></i>
        Algorithms: <strong>Leiden</strong> (highest modularity, default),
        <strong>Louvain</strong> (faster, similar quality), <strong>connected-components</strong>
        (strict reachability via union-find).
      </p>
    } @else if (!error()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">Loading communities…</div>
      </div>
    }
  `,
  styles: [`
    .cv-community-badge {
      color: #fff;
      min-width: 2.25rem;
      text-align: center;
    }
    .cv-community-bar {
      display: inline-block;
      width: 6rem;
      height: 0.4rem;
      background: var(--bs-tertiary-bg);
      border-radius: 999px;
      overflow: hidden;
      vertical-align: middle;
      margin: 0 0.35rem;
    }
    .cv-community-fill {
      display: block;
      height: 100%;
      background: var(--cv-accent, #e0592d);
      border-radius: inherit;
    }
    .cv-community-members {
      list-style: none;
      padding: 0;
      margin: 0;
      max-height: 14rem;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
    }
    .cv-community-members li a {
      display: block;
      padding: 0.15rem 0.3rem;
      border-radius: 0.25rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .cv-community-members li a:hover {
      background: var(--bs-tertiary-bg);
    }
    .cv-link { color: var(--bs-body-color); text-decoration: none; }
    .cv-link:hover { color: var(--cv-accent, #e0592d); }
  `],
})
export class CommunitiesComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly algorithms: ReadonlyArray<Algorithm> = ['leiden', 'louvain', 'connected-components'];
  readonly minSizes: ReadonlyArray<number> = [2, 3, 5, 10, 20];
  readonly limits: ReadonlyArray<number> = [10, 15, 25, 50];

  readonly algorithm = signal<Algorithm>('leiden');
  readonly minSize = signal(3);
  readonly limit = signal(15);

  readonly data = signal<CommunitiesResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading.set(true);
    this.error.set('');
    const params = new HttpParams()
      .set('algorithm', this.algorithm())
      .set('minSize', String(this.minSize()))
      .set('limit', String(this.limit()));
    this.http.get<CommunitiesResponse>('/api/communities', { params }).subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load communities');
        this.loading.set(false);
      },
    });
  }

  /**
   * Deterministic HSL colour per community id so the same community keeps the same colour
   * across reloads and so neighbouring rank ids look distinct (golden-angle stride).
   */
  colorFor(id: number): string {
    const hue = (id * 137.508) % 360;
    return `hsl(${hue}, 55%, 45%)`;
  }
}
