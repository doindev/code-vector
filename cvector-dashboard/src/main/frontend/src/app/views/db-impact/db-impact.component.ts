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

interface Row {
  readonly fqName: string;
  readonly fileId?: string;
  readonly line?: number;
}

interface DbImpactResponse {
  readonly project: { projectId: string; name: string };
  readonly table: string;
  readonly column: string | null;
  readonly readerCount: number;
  readonly writerCount: number;
  readonly readers: ReadonlyArray<Row>;
  readonly writers: ReadonlyArray<Row>;
}

@Component({
  selector: 'cv-db-impact',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>DB impact</h1>
        <div class="cv-page-subtitle">
          Methods that read from or write to a given table (optionally a specific column)
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <form class="row g-2 align-items-end" (ngSubmit)="run()">
        <div class="col-md-5">
          <label class="form-label small text-secondary mb-1">Table</label>
          <input class="form-control form-control-sm font-monospace" type="text"
                 [(ngModel)]="table" name="table"
                 placeholder="e.g. users" required />
        </div>
        <div class="col-md-5">
          <label class="form-label small text-secondary mb-1">Column (optional)</label>
          <input class="form-control form-control-sm font-monospace" type="text"
                 [(ngModel)]="column" name="column"
                 placeholder="e.g. email" />
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
      <div class="row g-3 mb-3">
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Readers</div>
            <div class="fs-3 fw-semibold cv-accent">{{ d.readerCount | number }}</div>
            <div class="small text-secondary">method(s) read from
              <code>{{ d.table }}{{ d.column ? '.' + d.column : '' }}</code></div>
          </div>
        </div>
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Writers</div>
            <div class="fs-3 fw-semibold cv-accent">{{ d.writerCount | number }}</div>
            <div class="small text-secondary">method(s) write to
              <code>{{ d.table }}{{ d.column ? '.' + d.column : '' }}</code></div>
          </div>
        </div>
      </div>

      <div class="row g-3">
        <div class="col-lg-6">
          <div class="cv-surface p-0">
            <div class="px-3 py-2 border-bottom">
              <h2 class="fs-6 fw-semibold mb-0">
                <i class="bi bi-book-half text-secondary me-1"></i>
                Readers ({{ d.readerCount | number }})
              </h2>
            </div>
            @if (d.readers.length === 0) {
              <div class="text-secondary text-center py-4 small">No readers found.</div>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <tbody>
                  @for (r of d.readers; track r.fqName) {
                    <tr>
                      <td class="font-monospace small" style="word-break:break-all;">{{ r.fqName }}</td>
                      <td class="text-end small text-secondary" style="width:5rem">
                        @if (r.line) { :{{ r.line }} }
                      </td>
                      <td class="text-end" style="width: 4rem">
                        <a class="btn btn-sm btn-link p-0 text-secondary"
                           [routerLink]="['/explain']"
                           [queryParams]="{ symbol: r.fqName }">
                          <i class="bi bi-info-circle"></i>
                        </a>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>
        <div class="col-lg-6">
          <div class="cv-surface p-0">
            <div class="px-3 py-2 border-bottom">
              <h2 class="fs-6 fw-semibold mb-0">
                <i class="bi bi-pencil-square text-secondary me-1"></i>
                Writers ({{ d.writerCount | number }})
              </h2>
            </div>
            @if (d.writers.length === 0) {
              <div class="text-secondary text-center py-4 small">No writers found.</div>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <tbody>
                  @for (r of d.writers; track r.fqName) {
                    <tr>
                      <td class="font-monospace small" style="word-break:break-all;">{{ r.fqName }}</td>
                      <td class="text-end small text-secondary" style="width:5rem">
                        @if (r.line) { :{{ r.line }} }
                      </td>
                      <td class="text-end" style="width: 4rem">
                        <a class="btn btn-sm btn-link p-0 text-secondary"
                           [routerLink]="['/explain']"
                           [queryParams]="{ symbol: r.fqName }">
                          <i class="bi bi-info-circle"></i>
                        </a>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>
      </div>
    }
  `,
})
export class DbImpactComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  table = '';
  column = '';

  readonly data = signal<DbImpactResponse | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        this.table = params.get('table') ?? '';
        this.column = params.get('column') ?? '';
        if (this.table) this.fetch();
      });
  }

  run(): void {
    if (!this.table) return;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { table: this.table, column: this.column || null },
      queryParamsHandling: 'merge',
    });
    this.fetch();
  }

  fetch(): void {
    this.loading.set(true);
    this.error.set('');
    let params = new HttpParams().set('table', this.table);
    if (this.column) params = params.set('column', this.column);
    this.http.get<DbImpactResponse>('/api/db-impact', { params }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load DB impact');
        this.loading.set(false);
      },
    });
  }
}
