import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

interface ActiveProject {
  projectId: string;
  name: string;
  rootPath: string;
  backend?: string;
  uri?: string;
  nodes?: number;
  edges?: number;
}

interface ProjectRow {
  key: string;
  projectId: string;
  name: string;
  rootPath: string;
  active: boolean;
}

interface ProjectsResponse {
  active: ActiveProject;
  projects: ReadonlyArray<ProjectRow>;
  count: number;
}

@Component({
  selector: 'cv-projects',
  standalone: true,
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // eslint-disable-next-line @angular-eslint/component-max-inline-declarations
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Projects</h1>
        <div class="cv-page-subtitle">
          Every project tracked in the active workspace
        </div>
      </div>
      <button class="btn btn-sm btn-outline-secondary" (click)="refresh()" title="Reload">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-warning small mb-3">{{ error() }}</div>
    }

    @if (data(); as d) {
      <div class="row g-3 mb-3">
        <div class="col-md-6">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Active project</div>
            <div class="fs-5 fw-semibold">{{ d.active.name }}</div>
            <div class="font-monospace small text-secondary">{{ d.active.projectId }}</div>
            <div class="small text-secondary mt-1">
              <i class="bi bi-folder"></i>
              <code>{{ d.active.rootPath }}</code>
            </div>
            @if (d.active.backend) {
              <div class="small text-secondary mt-1">
                <span class="badge bg-secondary me-1">{{ d.active.backend }}</span>
                <code>{{ d.active.uri }}</code>
              </div>
            }
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Nodes</div>
            <div class="fs-3 fw-semibold cv-accent">{{ d.active.nodes ?? 0 | number }}</div>
          </div>
        </div>
        <div class="col-md-3">
          <div class="cv-surface h-100">
            <div class="text-secondary text-uppercase small">Edges</div>
            <div class="fs-3 fw-semibold cv-accent">{{ d.active.edges ?? 0 | number }}</div>
          </div>
        </div>
      </div>

      <div class="cv-surface p-0">
        <div class="px-3 py-2 border-bottom">
          <h2 class="fs-6 fw-semibold mb-0">
            All projects in this workspace ({{ d.count | number }})
          </h2>
        </div>
        @if (d.projects.length === 0) {
          <div class="text-secondary text-center py-4 small">
            No projects configured. Run <code>cvector init</code> in the project root.
          </div>
        } @else {
          <table class="table table-hover mb-0">
            <thead>
              <tr>
                <th style="width: 3rem"></th>
                <th>Name</th>
                <th>Project ID</th>
                <th>Root path</th>
                <th style="width: 6rem" class="text-end"></th>
              </tr>
            </thead>
            <tbody>
              @for (p of d.projects; track p.projectId) {
                <tr [class.table-active]="p.active">
                  <td>
                    @if (p.active) {
                      <span class="badge cv-bg-accent" title="active project">
                        <i class="bi bi-check-lg"></i>
                      </span>
                    }
                  </td>
                  <td>
                    <span class="fw-semibold">{{ p.name }}</span>
                    <span class="text-secondary small ms-2">(key: {{ p.key }})</span>
                  </td>
                  <td class="font-monospace small text-secondary">{{ p.projectId }}</td>
                  <td class="font-monospace small">{{ p.rootPath }}</td>
                  <td class="text-end">
                    @if (!p.active) {
                      <button class="btn btn-sm btn-outline-secondary"
                              [disabled]="switching() === p.key"
                              (click)="switchTo(p.key)">
                        <i class="bi"
                           [class.bi-arrow-right-circle]="switching() !== p.key"
                           [class.bi-arrow-repeat]="switching() === p.key"></i>
                        Switch
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>

      @if (switchMessage()) {
        <div class="alert alert-success small mt-3">{{ switchMessage() }}</div>
      }
      @if (switchError()) {
        <div class="alert alert-warning small mt-3">{{ switchError() }}</div>
      }

      <p class="small text-secondary mt-3 mb-0">
        Runtime switch is live — the dashboard updates in place. The change also persists to
        <code>.cvector/settings.json</code> so a restart picks up the same project.
      </p>
    } @else if (!error()) {
      <div class="cv-surface text-center py-5 text-secondary">Loading projects…</div>
    }
  `,
})
export class ProjectsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = signal<ProjectsResponse | null>(null);
  readonly error = signal('');
  readonly switching = signal<string | null>(null);
  readonly switchMessage = signal('');
  readonly switchError = signal('');

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.error.set('');
    this.http
      .get<ProjectsResponse>('/api/projects')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (d) => this.data.set(d),
        error: (err) =>
          this.error.set(err?.error?.message ?? err?.message ?? 'Failed to load projects'),
      });
  }

  switchTo(key: string): void {
    this.switching.set(key);
    this.switchMessage.set('');
    this.switchError.set('');
    this.http
      .post<{ ok: boolean; active?: { name: string }; reason?: string }>(
        '/api/projects/switch',
        null,
        { params: { key } },
      )
      .subscribe({
        next: (res) => {
          this.switching.set(null);
          if (res?.ok) {
            this.switchMessage.set('Switched to ' + (res.active?.name ?? key) + '.');
            this.refresh();
          } else {
            this.switchError.set('Switch failed: ' + (res?.reason ?? 'unknown'));
          }
        },
        error: (err) => {
          this.switching.set(null);
          const reason = err?.error?.reason ?? err?.error?.message ?? err?.message;
          this.switchError.set('Switch failed: ' + (reason ?? 'server error'));
        },
      });
  }
}
