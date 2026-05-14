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

interface CallerRow { fqName: string; name?: string; callSiteLine?: number; id?: string; }
interface RefRow { label: string; fqName: string; fileId?: string; line?: number; }
interface FileRow { path: string; }
interface FileMeta { path?: string; language?: string; }

interface RenameResponse {
  project: { projectId: string; name: string };
  query: string;
  found: boolean;
  symbol?: { label?: string; fqName?: string; name?: string; startLine?: number; fileId?: string };
  file?: FileMeta;
  callers?: ReadonlyArray<CallerRow>;
  references?: ReadonlyArray<RefRow>;
  importingFiles?: ReadonlyArray<FileRow>;
  totals?: { callers: number; references: number; importers: number };
}

@Component({
  selector: 'cv-rename',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Rename impact</h1>
        <div class="cv-page-subtitle">
          Callers, references, and importing files for a symbol — preview the blast radius before
          you rename.
        </div>
      </div>
    </div>

    <div class="cv-surface mb-3">
      <form class="row g-2 align-items-end" (ngSubmit)="run()">
        <div class="col-md-10">
          <label class="form-label small text-secondary mb-1">Symbol</label>
          <input class="form-control form-control-sm font-monospace"
                 [(ngModel)]="symbol" name="symbol"
                 placeholder="e.g. CvectorRuntime.loadConfig() or ChangelogController" required />
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
      @if (!d.found) {
        <div class="cv-surface text-center py-5 text-secondary">
          No symbol matching <code>{{ d.query }}</code>.
        </div>
      } @else {
        <div class="cv-surface mb-3">
          <div class="d-flex gap-3 align-items-baseline flex-wrap">
            <span class="badge bg-secondary">{{ d.symbol?.label }}</span>
            <span class="fw-semibold font-monospace small">{{ d.symbol?.fqName }}</span>
            @if (d.file?.path) {
              <span class="small text-secondary">
                <i class="bi bi-file-code"></i>
                <code>{{ d.file?.path }}</code> @if (d.symbol?.startLine) { :{{ d.symbol?.startLine }} }
              </span>
            }
          </div>
        </div>

        <div class="row g-3 mb-3">
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Callers</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.totals?.callers ?? 0 | number }}</div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">References</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.totals?.references ?? 0 | number }}</div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Importing files</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.totals?.importers ?? 0 | number }}</div>
            </div>
          </div>
        </div>

        <div class="row g-3">
          <div class="col-lg-6">
            <div class="cv-surface p-0">
              <div class="px-3 py-2 border-bottom"><h2 class="fs-6 fw-semibold mb-0">Callers</h2></div>
              @if ((d.callers?.length ?? 0) === 0) {
                <div class="text-secondary text-center py-4 small">No callers.</div>
              } @else {
                <table class="table table-sm table-hover mb-0">
                  <tbody>
                    @for (c of d.callers ?? []; track $index) {
                      <tr>
                        <td class="font-monospace small" style="word-break:break-all;">{{ c.fqName }}</td>
                        <td class="text-end small text-secondary" style="width:5rem">
                          @if (c.callSiteLine) { :{{ c.callSiteLine }} }
                        </td>
                        <td class="text-end" style="width:4rem">
                          <a class="btn btn-sm btn-link p-0 text-secondary"
                             [routerLink]="['/explain']"
                             [queryParams]="{ symbol: c.fqName }">
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
              <div class="px-3 py-2 border-bottom"><h2 class="fs-6 fw-semibold mb-0">References</h2></div>
              @if ((d.references?.length ?? 0) === 0) {
                <div class="text-secondary text-center py-4 small">No references.</div>
              } @else {
                <table class="table table-sm table-hover mb-0">
                  <tbody>
                    @for (r of d.references ?? []; track $index) {
                      <tr>
                        <td class="small text-secondary" style="width:5rem">
                          <span class="badge bg-secondary">{{ r.label }}</span>
                        </td>
                        <td class="font-monospace small" style="word-break:break-all;">{{ r.fqName }}</td>
                        <td class="text-end small text-secondary" style="width:5rem">
                          @if (r.line) { :{{ r.line }} }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </div>
          </div>
          <div class="col-12">
            <div class="cv-surface p-0">
              <div class="px-3 py-2 border-bottom">
                <h2 class="fs-6 fw-semibold mb-0">Importing files</h2>
              </div>
              @if ((d.importingFiles?.length ?? 0) === 0) {
                <div class="text-secondary text-center py-4 small">No importers.</div>
              } @else {
                <ul class="list-group list-group-flush mb-0">
                  @for (f of d.importingFiles ?? []; track f.path) {
                    <li class="list-group-item">
                      <code>{{ f.path }}</code>
                    </li>
                  }
                </ul>
              }
            </div>
          </div>
        </div>
      }
    }

    <p class="small text-secondary mt-3 mb-0">
      cvector doesn't edit your source — use this as a checklist for the actual rename in your IDE.
    </p>
  `,
})
export class RenameComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  symbol = '';
  readonly data = signal<RenameResponse | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        this.symbol = params.get('symbol') ?? '';
        if (this.symbol) this.fetch();
      });
  }

  run(): void {
    if (!this.symbol) return;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { symbol: this.symbol },
      queryParamsHandling: 'merge',
    });
    this.fetch();
  }

  fetch(): void {
    this.loading.set(true);
    this.error.set('');
    const params = new HttpParams().set('symbol', this.symbol);
    this.http.get<RenameResponse>('/api/rename', { params }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to compute rename impact');
        this.loading.set(false);
      },
    });
  }
}
