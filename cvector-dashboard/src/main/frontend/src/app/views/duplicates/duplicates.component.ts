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
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

interface DuplicateGroup {
  readonly name: string;
  readonly paramCount: number;
  readonly returnType: string;
  readonly lineBucket: number;
  readonly occurrences: number;
  readonly members: ReadonlyArray<string>;
}

interface DuplicatesResponse {
  readonly project: { projectId: string; name: string };
  readonly min: number;
  readonly groupCount: number;
  readonly groups: ReadonlyArray<DuplicateGroup>;
}

@Component({
  selector: 'cv-duplicates',
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Duplicates</h1>
        <div class="cv-page-subtitle">
          Methods with the same shape (name + params + return type + ~size) —
          extraction candidates
        </div>
      </div>
      <div class="d-flex gap-2 align-items-center">
        <label class="small text-secondary mb-0">Min group size</label>
        <input class="form-control form-control-sm" type="number" style="width:5rem"
               min="2" max="20" [(ngModel)]="min" name="min" (change)="refresh()" />
        <button class="btn btn-sm btn-outline-secondary" (click)="refresh()">
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
          No duplicate-shape method groups at min={{ d.min }}. Lower the threshold to
          surface near-misses.
        </div>
      } @else {
        <div class="row g-3 mb-3">
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">Groups found</div>
              <div class="fs-3 fw-semibold cv-accent">{{ d.groupCount | number }}</div>
              <div class="small text-secondary">at min={{ d.min }} occurrences</div>
            </div>
          </div>
        </div>

        <div class="cv-surface p-0">
          <table class="table table-hover mb-0">
            <thead>
              <tr>
                <th>Name</th>
                <th style="width:5rem" class="text-end">Params</th>
                <th>Return</th>
                <th style="width:6rem" class="text-end">~ lines</th>
                <th style="width:5rem" class="text-end">Count</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (g of d.groups; track $index) {
                <tr style="cursor:pointer" (click)="toggle($index)">
                  <td class="fw-semibold font-monospace small">{{ g.name }}</td>
                  <td class="text-end font-monospace small">{{ g.paramCount }}</td>
                  <td class="font-monospace small text-secondary text-truncate"
                      style="max-width:14rem;" [title]="g.returnType">{{ g.returnType }}</td>
                  <td class="text-end font-monospace small text-secondary">{{ g.lineBucket }}</td>
                  <td class="text-end font-monospace small">{{ g.occurrences | number }}</td>
                  <td>
                    <i class="bi"
                       [class.bi-chevron-down]="expanded() === $index"
                       [class.bi-chevron-right]="expanded() !== $index"></i>
                  </td>
                </tr>
                @if (expanded() === $index) {
                  <tr>
                    <td colspan="6" class="p-0">
                      <div class="cv-duplicates-members p-3">
                        <table class="table table-sm table-hover mb-0">
                          <tbody>
                            @for (m of g.members; track $index) {
                              <tr>
                                <td class="font-monospace small" style="word-break:break-all;">{{ m }}</td>
                                <td class="text-end" style="width:4rem">
                                  <a class="btn btn-sm btn-link p-0 text-secondary me-2"
                                     [routerLink]="['/explain']" [queryParams]="{ symbol: m }">
                                    <i class="bi bi-info-circle"></i>
                                  </a>
                                  <a class="btn btn-sm btn-link p-0 text-secondary"
                                     [routerLink]="['/rename']" [queryParams]="{ symbol: m }">
                                    <i class="bi bi-input-cursor-text"></i>
                                  </a>
                                </td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }
    }
  `,
  styles: [
    `.cv-duplicates-members { background: var(--bs-tertiary-bg); }`,
  ],
})
export class DuplicatesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  min = 2;
  readonly data = signal<DuplicatesResponse | null>(null);
  readonly error = signal('');
  readonly expanded = signal<number>(-1);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.error.set('');
    const params = new HttpParams().set('min', String(this.min));
    this.http
      .get<DuplicatesResponse>('/api/duplicates', { params })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (d) => this.data.set(d),
        error: (err) =>
          this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load duplicates'),
      });
  }

  toggle(i: number): void {
    this.expanded.set(this.expanded() === i ? -1 : i);
  }
}
