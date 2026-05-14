import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';

interface OutgoingHttpRow {
  readonly caller?: string;
  readonly target?: string;
  readonly method?: string;
  readonly client?: string;
  readonly calls?: number;
}

interface OutgoingMessagingRow {
  readonly caller: string;
  readonly target: string;
  readonly calls: number;
}

interface ConsumerRow {
  readonly handler: string;
}

interface RestEndpointRow {
  readonly method: string;
  readonly path: string;
  readonly framework: string;
  readonly file: string;
  readonly handler?: string;
}

interface TableTouchedRow {
  readonly table: string;
  readonly sources: number;
  readonly access: 'READS_TABLE' | 'WRITES_TABLE';
}

interface ServiceLinksResponse {
  readonly project: { projectId: string; name: string };
  readonly outgoingHttp: ReadonlyArray<OutgoingHttpRow>;
  readonly outgoingMessaging: ReadonlyArray<OutgoingMessagingRow>;
  readonly incomingConsumers: ReadonlyArray<ConsumerRow>;
  readonly restEndpoints: ReadonlyArray<RestEndpointRow>;
  readonly tablesTouched: ReadonlyArray<TableTouchedRow>;
}

@Component({
  selector: 'cv-services',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Service links</h1>
        <div class="cv-page-subtitle">Cross-service edges discovered in the active scan</div>
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
        <div class="col-lg-12">
          <div class="cv-surface">
            <div class="cv-services-card-header">
              <i class="bi bi-arrow-up-right-circle text-primary"></i>
              <span class="fw-semibold">Outgoing HTTP</span>
              <span class="text-secondary small">Method &rarr; remote endpoint</span>
              <span class="badge bg-secondary ms-auto">{{ d.outgoingHttp.length | number }}</span>
            </div>
            @if (d.outgoingHttp.length === 0) {
              <p class="text-secondary small mb-0">No outgoing HTTP edges discovered.</p>
            } @else {
              <div class="table-responsive">
                <table class="table table-sm table-hover mb-0">
                  <thead>
                    <tr>
                      <th>Caller</th>
                      <th style="width:6rem">Method</th>
                      <th>Target</th>
                      <th style="width:7rem">Client</th>
                      <th style="width:5rem" class="text-end">Calls</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (r of d.outgoingHttp; track $index) {
                      <tr>
                        <td class="font-monospace small">
                          @if (r.caller) {
                            <a [routerLink]="['/explain']"
                               [queryParams]="{ symbol: r.caller }"
                               class="cv-link">{{ r.caller }}</a>
                          }
                        </td>
                        <td><span class="badge bg-secondary">{{ r.method || '-' }}</span></td>
                        <td class="font-monospace small">{{ r.target || '-' }}</td>
                        <td class="small text-secondary">{{ r.client || '-' }}</td>
                        <td class="text-end font-monospace small">{{ (r.calls ?? '') | number }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <div class="cv-services-card-header">
              <i class="bi bi-broadcast text-primary"></i>
              <span class="fw-semibold">Outgoing messaging</span>
              <span class="text-secondary small">Publish via Kafka/Rabbit/JMS/SQS/SNS</span>
              <span class="badge bg-secondary ms-auto">{{ d.outgoingMessaging.length | number }}</span>
            </div>
            @if (d.outgoingMessaging.length === 0) {
              <p class="text-secondary small mb-0">No outgoing messaging edges.</p>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <thead>
                  <tr>
                    <th>Caller</th>
                    <th>Target</th>
                    <th class="text-end" style="width:4rem">Calls</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of d.outgoingMessaging; track $index) {
                    <tr>
                      <td class="font-monospace small">
                        <a [routerLink]="['/explain']"
                           [queryParams]="{ symbol: r.caller }"
                           class="cv-link">{{ r.caller }}</a>
                      </td>
                      <td class="font-monospace small text-secondary">{{ r.target }}</td>
                      <td class="text-end font-monospace small">{{ r.calls | number }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>

        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <div class="cv-services-card-header">
              <i class="bi bi-inbox text-success"></i>
              <span class="fw-semibold">Incoming queue consumers</span>
              <span class="badge bg-secondary ms-auto">{{ d.incomingConsumers.length | number }}</span>
            </div>
            @if (d.incomingConsumers.length === 0) {
              <p class="text-secondary small mb-0">No queue listeners found.</p>
            } @else {
              <ul class="cv-services-list">
                @for (r of d.incomingConsumers; track r.handler) {
                  <li>
                    <a [routerLink]="['/explain']"
                       [queryParams]="{ symbol: r.handler }"
                       class="font-monospace small cv-link" [title]="r.handler">{{ r.handler }}</a>
                  </li>
                }
              </ul>
            }
          </div>
        </div>

        <div class="col-lg-12">
          <div class="cv-surface">
            <div class="cv-services-card-header">
              <i class="bi bi-door-open text-success"></i>
              <span class="fw-semibold">REST endpoints exposed</span>
              <span class="text-secondary small">@if (d.project.name) { from {{ d.project.name }} }</span>
              <span class="badge bg-secondary ms-auto">{{ d.restEndpoints.length | number }}</span>
            </div>
            @if (d.restEndpoints.length === 0) {
              <p class="text-secondary small mb-0">No REST endpoints discovered.</p>
            } @else {
              <div class="table-responsive">
                <table class="table table-sm table-hover mb-0">
                  <thead>
                    <tr>
                      <th style="width:5rem">Method</th>
                      <th>Path</th>
                      <th style="width:7rem">Framework</th>
                      <th>Handler</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (r of d.restEndpoints; track $index) {
                      <tr>
                        <td><span class="badge bg-secondary">{{ r.method || '?' }}</span></td>
                        <td class="font-monospace small">{{ r.path }}</td>
                        <td class="small text-secondary">{{ r.framework }}</td>
                        <td class="font-monospace small">
                          @if (r.handler) {
                            <a [routerLink]="['/explain']"
                               [queryParams]="{ symbol: r.handler }"
                               class="cv-link">{{ r.handler }}</a>
                          } @else {
                            <span class="text-secondary">-</span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </div>

        <div class="col-lg-12">
          <div class="cv-surface">
            <div class="cv-services-card-header">
              <i class="bi bi-database text-warning"></i>
              <span class="fw-semibold">Database tables touched</span>
              <span class="badge bg-secondary ms-auto">{{ d.tablesTouched.length | number }}</span>
            </div>
            @if (d.tablesTouched.length === 0) {
              <p class="text-secondary small mb-0">No table reads or writes discovered.</p>
            } @else {
              <table class="table table-sm table-hover mb-0">
                <thead>
                  <tr>
                    <th>Table</th>
                    <th style="width:7rem">Access</th>
                    <th class="text-end" style="width:6rem">Sources</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of d.tablesTouched; track $index) {
                    <tr>
                      <td class="font-monospace small">{{ r.table }}</td>
                      <td>
                        <span class="badge"
                              [class.bg-info]="r.access === 'READS_TABLE'"
                              [class.bg-warning]="r.access === 'WRITES_TABLE'">
                          {{ r.access === 'READS_TABLE' ? 'read' : 'write' }}
                        </span>
                      </td>
                      <td class="text-end font-monospace small">{{ r.sources | number }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>
      </div>
    } @else if (!error()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">Loading service links…</div>
      </div>
    }
  `,
  styles: [`
    .cv-services-card-header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
    }
    .cv-services-list {
      list-style: none;
      padding: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      max-height: 20rem;
      overflow-y: auto;
    }
    .cv-services-list li a {
      display: block;
      padding: 0.2rem 0.35rem;
      border-radius: 0.25rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .cv-services-list li a:hover {
      background: var(--bs-tertiary-bg);
    }
    .cv-link {
      color: var(--bs-body-color);
      text-decoration: none;
    }
    .cv-link:hover { color: var(--cv-accent, #e0592d); }
  `],
})
export class ServicesComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly data = signal<ServiceLinksResponse | null>(null);
  readonly error = signal('');

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.error.set('');
    this.http.get<ServiceLinksResponse>('/api/service-links').subscribe({
      next: (d) => this.data.set(d),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load service links'),
    });
  }
}
