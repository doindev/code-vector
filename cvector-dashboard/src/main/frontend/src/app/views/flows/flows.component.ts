import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { RouterLink } from '@angular/router';

interface RestFlowRow {
  readonly method?: string;
  readonly path?: string;
  readonly handler?: string;
  readonly reaches?: ReadonlyArray<string>;
}

interface EntryFlowRow {
  readonly entry: string;
  readonly reaches?: ReadonlyArray<string>;
}

interface FlowsResponse {
  readonly project: { projectId: string; name: string };
  readonly kind: string;
  readonly depth: number;
  readonly limit: number;
  readonly rest: ReadonlyArray<RestFlowRow>;
  readonly main: ReadonlyArray<EntryFlowRow>;
  readonly test: ReadonlyArray<EntryFlowRow>;
}

type Kind = 'all' | 'rest' | 'main' | 'test';

@Component({
  selector: 'cv-flows',
  standalone: true,
  imports: [FormsModule, DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Trace flows</h1>
        <div class="cv-page-subtitle">
          What each REST handler, <code>main</code>, and test reaches through CALLS edges
        </div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    <div class="cv-surface mb-3">
      <div class="d-flex gap-3 align-items-center flex-wrap">
        <div class="btn-group btn-group-sm" role="group">
          @for (k of kinds; track k) {
            <button class="btn btn-outline-secondary"
                    [class.active]="kind() === k"
                    (click)="setKind(k)">{{ k }}</button>
          }
        </div>
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small text-secondary mb-0">Depth</label>
          <select class="form-select form-select-sm" style="width:5rem"
                  [(ngModel)]="depth" (ngModelChange)="refresh()">
            @for (d of depths; track d) {
              <option [ngValue]="d">{{ d }}</option>
            }
          </select>
        </div>
        <div class="d-flex align-items-center gap-2">
          <label class="form-label small text-secondary mb-0">Limit</label>
          <select class="form-select form-select-sm" style="width:5rem"
                  [(ngModel)]="limit" (ngModelChange)="refresh()">
            @for (l of limits; track l) {
              <option [ngValue]="l">{{ l }}</option>
            }
          </select>
        </div>
      </div>
      @if (error()) {
        <div class="alert alert-warning small mt-2 mb-0">{{ error() }}</div>
      }
    </div>

    @if (data(); as d) {
      @if (showRest()) {
        <div class="cv-surface mb-3">
          <div class="cv-flows-card-header">
            <i class="bi bi-door-open text-primary"></i>
            <span class="fw-semibold">REST handlers</span>
            <span class="badge bg-secondary ms-auto">{{ d.rest.length | number }}</span>
          </div>
          @if (d.rest.length === 0) {
            <p class="text-secondary small mb-0">No REST flows.</p>
          } @else {
            @for (r of d.rest; track $index) {
              <div class="cv-flow-row">
                <div class="cv-flow-head">
                  <span class="badge bg-secondary">{{ r.method || '?' }}</span>
                  <span class="font-monospace small">{{ r.path }}</span>
                  <span class="text-secondary">&rarr;</span>
                  <a class="font-monospace small cv-link"
                     [routerLink]="['/explain']"
                     [queryParams]="{ symbol: r.handler }">{{ r.handler }}</a>
                  <span class="ms-auto small text-secondary">{{ (r.reaches?.length ?? 0) | number }} reach(es)</span>
                </div>
                @if ((r.reaches?.length ?? 0) > 0) {
                  <details class="cv-flow-reaches">
                    <summary class="small text-secondary">show reaches</summary>
                    <ul>
                      @for (m of r.reaches; track $index) {
                        <li>
                          <a class="font-monospace small cv-link"
                             [routerLink]="['/explain']"
                             [queryParams]="{ symbol: m }">{{ m }}</a>
                        </li>
                      }
                    </ul>
                  </details>
                }
              </div>
            }
          }
        </div>
      }

      @if (showMain()) {
        <div class="cv-surface mb-3">
          <div class="cv-flows-card-header">
            <i class="bi bi-play-circle text-success"></i>
            <span class="fw-semibold">Main entry points</span>
            <span class="badge bg-secondary ms-auto">{{ d.main.length | number }}</span>
          </div>
          @if (d.main.length === 0) {
            <p class="text-secondary small mb-0">No static main methods found.</p>
          } @else {
            @for (r of d.main; track r.entry) {
              <div class="cv-flow-row">
                <div class="cv-flow-head">
                  <a class="font-monospace small cv-link"
                     [routerLink]="['/explain']"
                     [queryParams]="{ symbol: r.entry }">{{ r.entry }}</a>
                  <span class="ms-auto small text-secondary">{{ (r.reaches?.length ?? 0) | number }} reach(es)</span>
                </div>
                @if ((r.reaches?.length ?? 0) > 0) {
                  <details class="cv-flow-reaches">
                    <summary class="small text-secondary">show reaches</summary>
                    <ul>
                      @for (m of r.reaches; track $index) {
                        <li>
                          <a class="font-monospace small cv-link"
                             [routerLink]="['/explain']"
                             [queryParams]="{ symbol: m }">{{ m }}</a>
                        </li>
                      }
                    </ul>
                  </details>
                }
              </div>
            }
          }
        </div>
      }

      @if (showTest()) {
        <div class="cv-surface mb-3">
          <div class="cv-flows-card-header">
            <i class="bi bi-check2-square text-warning"></i>
            <span class="fw-semibold">Test entry points</span>
            <span class="badge bg-secondary ms-auto">{{ d.test.length | number }}</span>
          </div>
          @if (d.test.length === 0) {
            <p class="text-secondary small mb-0">No test methods found.</p>
          } @else {
            @for (r of d.test; track r.entry) {
              <div class="cv-flow-row">
                <div class="cv-flow-head">
                  <a class="font-monospace small cv-link"
                     [routerLink]="['/explain']"
                     [queryParams]="{ symbol: r.entry }">{{ r.entry }}</a>
                  <span class="ms-auto small text-secondary">{{ (r.reaches?.length ?? 0) | number }} reach(es)</span>
                </div>
                @if ((r.reaches?.length ?? 0) > 0) {
                  <details class="cv-flow-reaches">
                    <summary class="small text-secondary">show reaches</summary>
                    <ul>
                      @for (m of r.reaches; track $index) {
                        <li>
                          <a class="font-monospace small cv-link"
                             [routerLink]="['/explain']"
                             [queryParams]="{ symbol: m }">{{ m }}</a>
                        </li>
                      }
                    </ul>
                  </details>
                }
              </div>
            }
          }
        </div>
      }
    } @else if (!error()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">Loading flows…</div>
      </div>
    }
  `,
  styles: [`
    .cv-flows-card-header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
    }
    .cv-flow-row {
      border-bottom: 1px solid var(--bs-border-color);
      padding: 0.4rem 0;
    }
    .cv-flow-row:last-child { border-bottom: none; }
    .cv-flow-head {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-wrap: wrap;
    }
    .cv-flow-reaches {
      margin-top: 0.35rem;
    }
    .cv-flow-reaches summary {
      cursor: pointer;
      user-select: none;
      padding: 0.15rem 0;
    }
    .cv-flow-reaches ul {
      list-style: none;
      padding-left: 1.25rem;
      margin: 0.25rem 0 0;
      max-height: 14rem;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
    }
    .cv-link {
      color: var(--bs-body-color);
      text-decoration: none;
    }
    .cv-link:hover { color: var(--cv-accent, #e0592d); }
  `],
})
export class FlowsComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly kinds: ReadonlyArray<Kind> = ['all', 'rest', 'main', 'test'];
  readonly depths: ReadonlyArray<number> = [1, 2, 3, 4, 5, 6, 7, 8];
  readonly limits: ReadonlyArray<number> = [25, 50, 100, 200];

  readonly kind = signal<Kind>('all');
  readonly depth = signal(3);
  readonly limit = signal(50);
  readonly data = signal<FlowsResponse | null>(null);
  readonly error = signal('');

  readonly showRest = computed(() => this.kind() === 'rest' || this.kind() === 'all');
  readonly showMain = computed(() => this.kind() === 'main' || this.kind() === 'all');
  readonly showTest = computed(() => this.kind() === 'test' || this.kind() === 'all');

  ngOnInit(): void { this.refresh(); }

  setKind(k: Kind): void {
    this.kind.set(k);
    this.refresh();
  }

  refresh(): void {
    this.error.set('');
    const params = new HttpParams()
      .set('kind', this.kind())
      .set('depth', String(this.depth()))
      .set('limit', String(this.limit()));
    this.http.get<FlowsResponse>('/api/flows', { params }).subscribe({
      next: (d) => this.data.set(d),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load flows'),
    });
  }
}
