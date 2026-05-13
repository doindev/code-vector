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
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

interface PrFileRow {
  file: string;
  symbolsTouched: number;
  incomingCallers: number;
  downstreamReach: number;
}

interface PrImpactResponse {
  project: { projectId: string; name: string };
  base: string;
  baseSha: string;
  depth: number;
  changedFileCount: number;
  files: ReadonlyArray<PrFileRow>;
  error?: string;
}

@Component({
  selector: 'cv-pr-impact',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>PR impact</h1>
        <div class="cv-page-subtitle">
          Blast-radius preview for files changed against a base branch
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <form class="row g-2 align-items-end" (ngSubmit)="run()">
        <div class="col-md-7">
          <label class="form-label small text-secondary mb-1">Base branch</label>
          <input class="form-control form-control-sm font-monospace" [(ngModel)]="base" name="base" />
        </div>
        <div class="col-md-3">
          <label class="form-label small text-secondary mb-1">Depth</label>
          <input class="form-control form-control-sm" type="number" min="1" max="8"
                 [(ngModel)]="depth" name="depth" />
        </div>
        <div class="col-md-2">
          <button class="btn btn-sm cv-bg-accent w-100" type="submit" [disabled]="loading()">
            <i class="bi" [class.bi-search]="!loading()" [class.bi-arrow-repeat]="loading()"></i>
            Analyse
          </button>
        </div>
      </form>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      @if (d.error) {
        <div class="cv-surface text-center py-5 text-secondary">{{ d.error }}</div>
      } @else {
        <div class="row g-3 mb-3">
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Base</div>
              <div class="fw-semibold font-monospace small">{{ d.base }}</div>
              <div class="small text-secondary">{{ d.baseSha?.slice(0, 7) }}</div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Changed files</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.changedFileCount | number }}</div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Depth</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.depth | number }}</div>
              <div class="small text-secondary">BFS hops per symbol</div>
            </div>
          </div>
        </div>

        @if (d.files.length === 0) {
          <div class="cv-surface text-center py-5 text-secondary">
            No changed files matched ingested File nodes — run <code>cvector scan</code>
            to refresh the graph.
          </div>
        } @else {
          <div class="cv-surface p-0">
            <table class="table table-hover mb-0">
              <thead>
                <tr>
                  <th>File</th>
                  <th class="text-end" style="width:7rem">Symbols</th>
                  <th class="text-end" style="width:7rem">Callers</th>
                  <th class="text-end" style="width:9rem">Downstream</th>
                </tr>
              </thead>
              <tbody>
                @for (row of d.files; track row.file) {
                  <tr [class.cv-pr-impact-hot]="row.downstreamReach > 50">
                    <td class="font-monospace small" style="word-break:break-all;">{{ row.file }}</td>
                    <td class="text-end font-monospace small">{{ row.symbolsTouched | number }}</td>
                    <td class="text-end font-monospace small">{{ row.incomingCallers | number }}</td>
                    <td class="text-end font-monospace small">{{ row.downstreamReach | number }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    }

    <p class="small text-secondary mt-3 mb-0">
      Counts come from the indexed graph. If a file was edited but the graph hasn't been
      re-scanned, the new edges won't show — run <code>cvector scan</code> first for the
      sharpest answer.
    </p>
  `,
  styles: [
    `.cv-pr-impact-hot td { background-color: var(--bs-danger-bg-subtle, rgba(220, 53, 69, 0.12)); }`,
  ],
})
export class PrImpactComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  base = 'main';
  depth = 3;
  readonly data = signal<PrImpactResponse | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        this.base = params.get('base') ?? this.base;
        const d = Number(params.get('depth') ?? '0');
        if (d > 0) this.depth = d;
        this.fetch();
      });
  }

  run(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { base: this.base, depth: this.depth },
      queryParamsHandling: 'merge',
    });
    this.fetch();
  }

  fetch(): void {
    this.loading.set(true);
    this.error.set('');
    const params = new HttpParams().set('base', this.base).set('depth', String(this.depth));
    this.http.get<PrImpactResponse>('/api/pr-impact', { params }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load PR impact');
        this.loading.set(false);
      },
    });
  }
}
