import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';

interface GodFileRow { readonly path: string; readonly methods: number; }
interface GodClassRow { readonly fqName: string; readonly methods: number; }
interface LongMethodRow { readonly fqName: string; readonly lines: number; }
interface DeadCodeRow { readonly fqName: string; }

interface HealthResponse {
  readonly project: { projectId: string; name: string };
  readonly godFiles: ReadonlyArray<GodFileRow>;
  readonly godClasses: ReadonlyArray<GodClassRow>;
  readonly longMethods: ReadonlyArray<LongMethodRow>;
  readonly deadCode: ReadonlyArray<DeadCodeRow>;
}

@Component({
  selector: 'cv-health',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Code health</h1>
        <div class="cv-page-subtitle">Complexity hotspots ranked by the active scan</div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      <div class="row g-3">
        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <div class="cv-health-card-header">
              <i class="bi bi-file-earmark-binary text-danger"></i>
              <span class="fw-semibold">God files</span>
              <span class="text-secondary small">&ge; 30 methods</span>
              <span class="badge bg-secondary ms-auto">{{ d.godFiles.length | number }}</span>
            </div>
            @if (d.godFiles.length === 0) {
              <p class="text-secondary small mb-0">None.</p>
            } @else {
              <ul class="cv-health-list">
                @for (r of d.godFiles; track r.path) {
                  <li>
                    <span class="cv-health-name font-monospace small" [title]="r.path">{{ r.path }}</span>
                    <span class="cv-health-bar">
                      <span class="cv-health-fill"
                            [style.width.%]="pctOf(r.methods, maxGodFile())"></span>
                    </span>
                    <span class="cv-health-count font-monospace small">{{ r.methods | number }}</span>
                  </li>
                }
              </ul>
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <div class="cv-health-card-header">
              <i class="bi bi-bricks text-danger"></i>
              <span class="fw-semibold">God classes</span>
              <span class="text-secondary small">&ge; 20 methods</span>
              <span class="badge bg-secondary ms-auto">{{ d.godClasses.length | number }}</span>
            </div>
            @if (d.godClasses.length === 0) {
              <p class="text-secondary small mb-0">None.</p>
            } @else {
              <ul class="cv-health-list">
                @for (r of d.godClasses; track r.fqName) {
                  <li>
                    <a class="cv-health-name font-monospace small"
                       [routerLink]="['/explain']"
                       [queryParams]="{ symbol: r.fqName }"
                       [title]="r.fqName">{{ r.fqName }}</a>
                    <span class="cv-health-bar">
                      <span class="cv-health-fill"
                            [style.width.%]="pctOf(r.methods, maxGodClass())"></span>
                    </span>
                    <span class="cv-health-count font-monospace small">{{ r.methods | number }}</span>
                  </li>
                }
              </ul>
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <div class="cv-health-card-header">
              <i class="bi bi-rulers text-warning"></i>
              <span class="fw-semibold">Long methods</span>
              <span class="text-secondary small">&ge; 80 lines</span>
              <span class="badge bg-secondary ms-auto">{{ d.longMethods.length | number }}</span>
            </div>
            @if (d.longMethods.length === 0) {
              <p class="text-secondary small mb-0">None.</p>
            } @else {
              <ul class="cv-health-list">
                @for (r of d.longMethods; track r.fqName) {
                  <li>
                    <a class="cv-health-name font-monospace small"
                       [routerLink]="['/explain']"
                       [queryParams]="{ symbol: r.fqName }"
                       [title]="r.fqName">{{ r.fqName }}</a>
                    <span class="cv-health-bar">
                      <span class="cv-health-fill"
                            [style.width.%]="pctOf(r.lines, maxLongMethod())"></span>
                    </span>
                    <span class="cv-health-count font-monospace small">{{ r.lines | number }} ln</span>
                  </li>
                }
              </ul>
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <div class="cv-health-card-header">
              <i class="bi bi-trash text-secondary"></i>
              <span class="fw-semibold">Potentially dead code</span>
              <span class="text-secondary small">no inbound calls</span>
              <span class="badge bg-secondary ms-auto">{{ d.deadCode.length | number }}</span>
            </div>
            @if (d.deadCode.length === 0) {
              <p class="text-secondary small mb-0">None.</p>
            } @else {
              <ul class="cv-health-list cv-health-list--plain">
                @for (r of d.deadCode; track r.fqName) {
                  <li>
                    <a class="cv-health-name font-monospace small"
                       [routerLink]="['/explain']"
                       [queryParams]="{ symbol: r.fqName }"
                       [title]="r.fqName">{{ r.fqName }}</a>
                  </li>
                }
              </ul>
            }
          </div>
        </div>
      </div>

      <p class="small text-secondary mt-3 mb-0">
        <i class="bi bi-info-circle"></i>
        Thresholds (30 methods / 20 methods / 80 lines) are baked into the rollup queries.
        Override them via custom Cypher rules in <code>.cvector/rules.yml</code> if your codebase needs different cut-offs.
      </p>
    } @else if (!error()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">Loading code health…</div>
      </div>
    }
  `,
  styles: [`
    .cv-health-card-header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
    }
    .cv-health-list {
      list-style: none;
      padding: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      max-height: 22rem;
      overflow-y: auto;
    }
    .cv-health-list li {
      display: grid;
      grid-template-columns: 1fr 6rem 4rem;
      gap: 0.5rem;
      align-items: center;
    }
    .cv-health-list--plain li {
      grid-template-columns: 1fr;
    }
    .cv-health-name {
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      color: var(--bs-body-color);
      text-decoration: none;
    }
    a.cv-health-name:hover { color: var(--cv-accent, #e0592d); }
    .cv-health-bar {
      height: 0.45rem;
      background: var(--bs-tertiary-bg);
      border-radius: 999px;
      overflow: hidden;
    }
    .cv-health-fill {
      display: block;
      height: 100%;
      background: var(--cv-accent, #e0592d);
      border-radius: inherit;
    }
    .cv-health-count {
      text-align: right;
      color: var(--bs-secondary-color);
    }
  `],
})
export class HealthComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly data = signal<HealthResponse | null>(null);
  readonly error = signal('');

  readonly maxGodFile = computed(() => Math.max(1, ...(this.data()?.godFiles ?? []).map((r) => r.methods)));
  readonly maxGodClass = computed(() => Math.max(1, ...(this.data()?.godClasses ?? []).map((r) => r.methods)));
  readonly maxLongMethod = computed(() => Math.max(1, ...(this.data()?.longMethods ?? []).map((r) => r.lines)));

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.error.set('');
    this.http.get<HealthResponse>('/api/code-health').subscribe({
      next: (d) => this.data.set(d),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load code health'),
    });
  }

  pctOf(value: number, max: number): number {
    if (max <= 0) return 0;
    return Math.max(2, Math.round((value / max) * 100));
  }
}
