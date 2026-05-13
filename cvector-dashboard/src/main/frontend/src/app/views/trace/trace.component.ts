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
import { HttpClient, HttpParams } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

interface PathNode {
  readonly id: string;
  readonly label: string;
  readonly fqName: string;
  readonly name: string;
}

interface PathEdge {
  readonly from: string;
  readonly to: string;
  readonly type: string;
}

interface PathResponse {
  readonly project: { projectId: string; name: string };
  readonly from: string;
  readonly to: string;
  readonly depth: number;
  readonly found: boolean;
  readonly reason?: string;
  readonly source?: { fqName?: string; label?: string };
  readonly target?: { fqName?: string; label?: string };
  readonly pathDepth?: number;
  readonly nodes?: ReadonlyArray<PathNode>;
  readonly edges?: ReadonlyArray<PathEdge>;
}

@Component({
  selector: 'cv-trace',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Trace path</h1>
        <div class="cv-page-subtitle">
          Shortest call-graph path between two symbols (CALLS edges, max depth 12)
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <form class="row g-2 align-items-end" (ngSubmit)="run()">
        <div class="col-md-5">
          <label class="form-label small text-secondary mb-1">From</label>
          <input class="form-control form-control-sm font-monospace" type="text"
                 [(ngModel)]="from" name="from"
                 placeholder="e.g. ChangelogController.changelog" required />
        </div>
        <div class="col-md-5">
          <label class="form-label small text-secondary mb-1">To</label>
          <input class="form-control form-control-sm font-monospace" type="text"
                 [(ngModel)]="to" name="to"
                 placeholder="e.g. GraphStore.recentlyChanged" required />
        </div>
        <div class="col-md-1">
          <label class="form-label small text-secondary mb-1">Depth</label>
          <input class="form-control form-control-sm" type="number" min="1" max="12"
                 [(ngModel)]="depth" name="depth" />
        </div>
        <div class="col-md-1">
          <button class="btn btn-sm cv-bg-accent w-100" type="submit" [disabled]="loading()">
            <i class="bi" [class.bi-arrow-right]="!loading()" [class.bi-arrow-repeat]="loading()"></i>
            Trace
          </button>
        </div>
      </form>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      @if (!d.found) {
        <div class="cv-surface text-center py-5 text-secondary">
          @if (d.reason === 'source-not-found') {
            Source symbol <code>{{ d.from }}</code> not found.
          } @else if (d.reason === 'target-not-found') {
            Target symbol <code>{{ d.to }}</code> not found.
          } @else {
            No path of length ≤ {{ d.depth }} found from
            <code>{{ d.source?.fqName ?? d.from }}</code> to
            <code>{{ d.target?.fqName ?? d.to }}</code>.
            Try increasing depth.
          }
        </div>
      } @else {
        <div class="cv-surface mb-3">
          <div class="row align-items-center g-2">
            <div class="col-md-4">
              <div class="text-secondary small text-uppercase">Source</div>
              <div class="fw-semibold font-monospace small">{{ d.source?.fqName }}</div>
              <span class="badge bg-secondary">{{ d.source?.label }}</span>
            </div>
            <div class="col-md-4 text-center">
              <div class="text-secondary small text-uppercase">Path depth</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.pathDepth | number }}</div>
              <div class="small text-secondary">hops via CALLS</div>
            </div>
            <div class="col-md-4 text-md-end">
              <div class="text-secondary small text-uppercase">Target</div>
              <div class="fw-semibold font-monospace small">{{ d.target?.fqName }}</div>
              <span class="badge bg-secondary">{{ d.target?.label }}</span>
            </div>
          </div>
        </div>

        <div class="cv-surface p-0">
          <table class="table table-hover mb-0">
            <thead>
              <tr>
                <th style="width: 4rem">#</th>
                <th style="width: 7rem">Label</th>
                <th>Symbol</th>
                <th style="width: 12rem">Edge to next</th>
                <th style="width: 6rem"></th>
              </tr>
            </thead>
            <tbody>
              @for (n of d.nodes ?? []; track n.id; let i = $index) {
                <tr>
                  <td class="text-secondary small">{{ i }}</td>
                  <td><span class="badge bg-secondary">{{ n.label }}</span></td>
                  <td class="font-monospace small" style="word-break:break-all;">{{ n.fqName }}</td>
                  <td>
                    @if (i < ((d.nodes?.length ?? 0) - 1)) {
                      <span class="badge text-bg-light">
                        <i class="bi bi-arrow-down"></i> {{ (d.edges?.[i]?.type) ?? 'CALLS' }}
                      </span>
                    }
                  </td>
                  <td class="text-end">
                    <a class="btn btn-sm btn-link p-0 text-secondary me-2"
                       [routerLink]="['/explain']"
                       [queryParams]="{ symbol: n.fqName }"
                       title="Explain">
                      <i class="bi bi-info-circle"></i>
                    </a>
                    <a class="btn btn-sm btn-link p-0 text-secondary"
                       [routerLink]="['/graph']"
                       [queryParams]="{ symbol: n.fqName }"
                       title="Graph">
                      <i class="bi bi-diagram-3"></i>
                    </a>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    }
  `,
})
export class TraceComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  from = '';
  to = '';
  depth = 6;

  readonly data = signal<PathResponse | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        this.from = params.get('from') ?? '';
        this.to = params.get('to') ?? '';
        const d = Number(params.get('depth') ?? '0');
        if (d > 0) this.depth = d;
        if (this.from && this.to) this.fetch();
      });
  }

  run(): void {
    if (!this.from || !this.to) return;
    // Persist into URL so the page is shareable / refreshable.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { from: this.from, to: this.to, depth: this.depth },
      queryParamsHandling: 'merge',
    });
    this.fetch();
  }

  fetch(): void {
    this.loading.set(true);
    this.error.set('');
    const params = new HttpParams()
      .set('from', this.from)
      .set('to', this.to)
      .set('depth', String(this.depth));
    this.http.get<PathResponse>('/api/path', { params }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to trace path');
        this.loading.set(false);
      },
    });
  }
}
