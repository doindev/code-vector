import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { visiblePoll } from '../../core/poll';
import { EventStreamService } from '../../core/event-stream.service';

type Severity = 'ERROR' | 'WARN' | 'INFO';

interface Finding {
  readonly subject: string;
  readonly message: string;
  readonly severity: Severity;
  readonly fileId: string | null;
  readonly line: number | null;
}

interface RuleRun {
  readonly rule: string;
  readonly severity: Severity;
  readonly violations: number;
  readonly findings: ReadonlyArray<Finding>;
}

interface RulesResponse {
  readonly project: { projectId: string; name: string };
  readonly rulesPath: string | null;
  readonly rulesYmlExists: boolean;
  readonly totals: Record<string, number>;
  readonly totalViolations: number;
  readonly hasErrors: boolean;
  readonly runs: ReadonlyArray<RuleRun>;
}

@Component({
  selector: 'cv-rules',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Architecture rules</h1>
        <div class="cv-page-subtitle">
          @if (data(); as d) {
            <span class="fw-semibold">{{ d.totalViolations | number }}</span> violation(s)
            across <span class="fw-semibold">{{ d.runs.length }}</span> rule(s)
            @if (d.hasErrors) {
              · <span class="badge text-bg-danger ms-1">ERROR-level violations</span>
            }
          } @else {
            Evaluate the active project against the configured rule set
          }
        </div>
      </div>
      <div class="d-flex gap-2 align-items-center">
        <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
      </div>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      <div class="row g-3 mb-3">
        @for (sev of severities; track sev) {
          <div class="col-md-4">
            <div class="cv-surface h-100">
              <div class="text-secondary text-uppercase small">{{ sev }}</div>
              <div class="fs-4 fw-semibold" [class.text-danger]="sev === 'ERROR' && (d.totals[sev] ?? 0) > 0">
                {{ d.totals[sev] ?? 0 | number }}
              </div>
            </div>
          </div>
        }
      </div>

      @if (!d.rulesYmlExists) {
        <div class="alert alert-info small mb-3">
          <i class="bi bi-info-circle"></i>
          No <code>.cvector/rules.yml</code> found — using built-in defaults. Run
          <code>cvector rules --init</code> to generate a customisable template at
          @if (d.rulesPath) { <code>{{ d.rulesPath }}</code> }.
        </div>
      }

      @if (d.runs.length === 0) {
        <div class="cv-surface text-center py-5 text-secondary">
          No rule runs to report. (Has the project been scanned yet?)
        </div>
      } @else {
        <div class="cv-surface p-0">
          <table class="table table-hover mb-0">
            <thead>
              <tr>
                <th style="width:14rem">Rule</th>
                <th style="width:6rem">Severity</th>
                <th style="width:8rem" class="text-end">Violations</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (run of d.runs; track run.rule) {
                <tr [class.cv-rule-row-error]="run.severity === 'ERROR' && run.violations > 0"
                    style="cursor: pointer;"
                    (click)="toggle(run.rule)">
                  <td class="fw-semibold">{{ run.rule }}</td>
                  <td>
                    <span class="badge"
                          [class.text-bg-danger]="run.severity === 'ERROR'"
                          [class.text-bg-warning]="run.severity === 'WARN'"
                          [class.text-bg-secondary]="run.severity !== 'ERROR' && run.severity !== 'WARN'">
                      {{ run.severity }}
                    </span>
                  </td>
                  <td class="text-end font-monospace small"
                      [class.text-danger]="run.severity === 'ERROR' && run.violations > 0">
                    {{ run.violations | number }}
                  </td>
                  <td>
                    @if (run.violations > 0) {
                      <i class="bi"
                         [class.bi-chevron-down]="expanded() === run.rule"
                         [class.bi-chevron-right]="expanded() !== run.rule"></i>
                    } @else {
                      <i class="bi bi-check2-circle text-success"></i>
                    }
                  </td>
                </tr>
                @if (expanded() === run.rule && run.violations > 0) {
                  <tr>
                    <td colspan="4" class="p-0">
                      <div class="cv-rule-findings p-3">
                        <table class="table table-sm table-hover mb-0">
                          <thead>
                            <tr>
                              <th>Subject</th>
                              <th>Message</th>
                              <th style="width: 4rem" class="text-end">Line</th>
                              <th style="width: 4rem"></th>
                            </tr>
                          </thead>
                          <tbody>
                            @for (v of run.findings; track $index) {
                              <tr>
                                <td class="font-monospace small" style="word-break:break-all;">
                                  {{ v.subject }}
                                </td>
                                <td class="small">{{ v.message }}</td>
                                <td class="text-end font-monospace small text-secondary">
                                  {{ v.line ?? '' }}
                                </td>
                                <td class="text-end">
                                  <a class="btn btn-sm btn-link p-0 text-secondary"
                                     [routerLink]="['/explain']"
                                     [queryParams]="{ symbol: v.subject }"
                                     title="Explain">
                                    <i class="bi bi-info-circle"></i>
                                  </a>
                                </td>
                              </tr>
                            }
                          </tbody>
                        </table>
                        @if (run.violations > run.findings.length) {
                          <div class="small text-secondary mt-2">
                            … {{ (run.violations - run.findings.length) | number }} more violation(s) not shown
                          </div>
                        }
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }

      <div class="small text-secondary mt-3 d-flex justify-content-between">
        <span>
          @if (d.rulesPath) { Rules config: <code>{{ d.rulesPath }}</code> }
        </span>
        <span>Live via SSE · fallback poll 60s</span>
      </div>
    } @else if (!error()) {
      <div class="cv-surface text-center py-5 text-secondary">Evaluating rules…</div>
    }
  `,
  styles: [
    `
      .cv-rule-row-error {
        border-left: 3px solid var(--bs-danger);
      }
      .cv-rule-findings {
        background: var(--bs-tertiary-bg);
      }
    `,
  ],
})
export class RulesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stream = inject(EventStreamService);

  readonly severities: ReadonlyArray<Severity> = ['ERROR', 'WARN', 'INFO'];

  readonly data = signal<RulesResponse | null>(null);
  readonly error = signal('');
  readonly expanded = signal<string>('');

  ngOnInit(): void {
    this.refresh();
    this.stream
      .on('graph-mutated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
    visiblePoll(60_000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  refresh(): void {
    this.error.set('');
    this.http.get<RulesResponse>('/api/rules').subscribe({
      next: (d) => this.data.set(d),
      error: (err) => this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load rules'),
    });
  }

  toggle(rule: string): void {
    this.expanded.set(this.expanded() === rule ? '' : rule);
  }
}
