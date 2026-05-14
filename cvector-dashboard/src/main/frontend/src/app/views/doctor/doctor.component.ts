import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';

interface Check {
  readonly name: string;
  readonly status: 'ok' | 'warn' | 'fail';
  readonly message: string;
}

interface DoctorResponse {
  readonly project: { projectId: string; name: string; rootPath: string };
  readonly backend: { kind: string; uri: string };
  readonly jvm: {
    javaVersion: string;
    vmName: string;
    availableProcessors: number;
    heapUsedMb: number;
    heapMaxMb: number;
    uptimeMillis: number;
  };
  readonly cache: { entries: number };
  readonly checks: ReadonlyArray<Check>;
}

@Component({
  selector: 'cv-doctor',
  standalone: true,
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Doctor</h1>
        <div class="cv-page-subtitle">System diagnostics for the active cvector setup</div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Re-run checks">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      <div class="cv-surface mb-3">
        <h2 class="fs-6 fw-semibold mb-2">Checks</h2>
        <ul class="cv-doctor-checks">
          @for (c of d.checks; track c.name) {
            <li>
              <span class="cv-check-badge" [class]="badgeClass(c.status)">{{ statusLabel(c.status) }}</span>
              <div>
                <div class="fw-semibold">{{ c.name }}</div>
                <div class="small text-secondary">{{ c.message }}</div>
              </div>
            </li>
          }
        </ul>
      </div>

      <div class="row g-3">
        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">Project</h2>
            <dl class="cv-kv">
              <dt>Name</dt><dd>{{ d.project.name }}</dd>
              <dt>Project ID</dt><dd class="font-monospace small">{{ d.project.projectId }}</dd>
              <dt>Root path</dt><dd class="font-monospace small">{{ d.project.rootPath }}</dd>
              <dt>Backend</dt><dd>{{ d.backend.kind }}</dd>
              <dt>URI</dt><dd class="font-monospace small">{{ d.backend.uri }}</dd>
            </dl>
          </div>
        </div>
        <div class="col-lg-6">
          <div class="cv-surface h-100">
            <h2 class="fs-6 fw-semibold mb-2">JVM</h2>
            <dl class="cv-kv">
              <dt>Java</dt><dd>{{ d.jvm.javaVersion }}</dd>
              <dt>VM</dt><dd class="small">{{ d.jvm.vmName }}</dd>
              <dt>Processors</dt><dd>{{ d.jvm.availableProcessors | number }}</dd>
              <dt>Heap</dt>
              <dd>
                {{ d.jvm.heapUsedMb | number }} / {{ d.jvm.heapMaxMb | number }} MB
                <span class="text-secondary small">({{ heapPct(d.jvm) }}%)</span>
              </dd>
              <dt>Uptime</dt><dd>{{ uptime(d.jvm.uptimeMillis) }}</dd>
              <dt>Cache entries</dt><dd>{{ d.cache.entries | number }}</dd>
            </dl>
          </div>
        </div>
      </div>
    } @else if (!error()) {
      <div class="cv-surface">
        <div class="text-secondary text-center py-4">Running diagnostics…</div>
      </div>
    }
  `,
  styles: [`
    .cv-doctor-checks {
      list-style: none;
      padding: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 0.6rem;
    }
    .cv-doctor-checks li {
      display: grid;
      grid-template-columns: 4rem 1fr;
      gap: 0.75rem;
      align-items: center;
    }
    .cv-check-badge {
      text-align: center;
      padding: 0.2rem 0.4rem;
      border-radius: 0.3rem;
      color: #fff;
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      font-weight: 600;
    }
    .cv-check-badge--ok   { background: var(--bs-success, #198754); }
    .cv-check-badge--warn { background: var(--bs-warning, #ffc107); color: #212529; }
    .cv-check-badge--fail { background: var(--bs-danger,  #dc3545); }
    .cv-kv {
      display: grid;
      grid-template-columns: 9rem 1fr;
      gap: 0.4rem 0.75rem;
      margin: 0;
    }
    .cv-kv dt {
      color: var(--bs-secondary-color);
      font-weight: 500;
    }
    .cv-kv dd {
      margin: 0;
    }
  `],
})
export class DoctorComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly data = signal<DoctorResponse | null>(null);
  readonly error = signal('');

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.error.set('');
    this.http.get<DoctorResponse>('/api/doctor').subscribe({
      next: (d) => this.data.set(d),
      error: (err) =>
        this.error.set(err?.error?.message ?? err?.message ?? 'Failed to run diagnostics'),
    });
  }

  badgeClass(s: string): string { return 'cv-check-badge--' + s; }
  statusLabel(s: string): string { return s === 'ok' ? 'PASS' : s === 'warn' ? 'WARN' : 'FAIL'; }

  heapPct(jvm: { heapUsedMb: number; heapMaxMb: number }): number {
    if (jvm.heapMaxMb <= 0) return 0;
    return Math.round((jvm.heapUsedMb / jvm.heapMaxMb) * 100);
  }

  uptime(ms: number): string {
    const secs = Math.floor(ms / 1000);
    const mins = Math.floor(secs / 60);
    const hours = Math.floor(mins / 60);
    if (hours > 0) return `${hours}h ${mins % 60}m`;
    if (mins > 0) return `${mins}m ${secs % 60}s`;
    return `${secs}s`;
  }
}
