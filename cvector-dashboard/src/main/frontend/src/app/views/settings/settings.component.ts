import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ThemeService } from '../../core/theme.service';

@Component({
  selector: 'cv-settings',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-page-header">
      <div>
        <h1>Settings</h1>
        <div class="cv-page-subtitle">Backend, appearance, performance</div>
      </div>
    </div>

    <div class="row g-3">
      <div class="col-lg-6">
        <div class="cv-surface h-100">
          <h2 class="fs-6 fw-semibold mb-3">Backend</h2>
          <div class="mb-3">
            <label class="form-label small text-secondary">Graph store</label>
            <select class="form-select">
              <option value="embedded">Embedded KuzuDB</option>
              <option value="neo4j">Neo4j (bolt://)</option>
            </select>
          </div>
          <div class="mb-3">
            <label class="form-label small text-secondary">Neo4j URI</label>
            <input class="form-control font-monospace" value="bolt://localhost:7687" />
          </div>
          <div class="row g-2">
            <div class="col-md-6">
              <label class="form-label small text-secondary">User</label>
              <input class="form-control" value="neo4j" />
            </div>
            <div class="col-md-6">
              <label class="form-label small text-secondary">Password</label>
              <input class="form-control" type="password" value="" placeholder="•••••••" />
            </div>
          </div>
        </div>
      </div>

      <div class="col-lg-6">
        <div class="cv-surface h-100">
          <h2 class="fs-6 fw-semibold mb-3">Appearance</h2>
          <div class="mb-3">
            <label class="form-label small text-secondary d-block">Theme</label>
            <div class="btn-group" role="group">
              <button class="btn btn-outline-secondary"
                      [class.active]="theme.theme() === 'light'"
                      (click)="theme.set('light')">
                <i class="bi bi-sun"></i> Light
              </button>
              <button class="btn btn-outline-secondary"
                      [class.active]="theme.theme() === 'dark'"
                      (click)="theme.set('dark')">
                <i class="bi bi-moon-stars"></i> Dark
              </button>
            </div>
          </div>
          <div class="mb-3">
            <label class="form-label small text-secondary">Accent colour</label>
            <input class="form-control form-control-color" type="color" value="#e0592d" />
          </div>
        </div>
      </div>

      <div class="col-lg-12">
        <div class="cv-surface">
          <h2 class="fs-6 fw-semibold mb-3">Graph rendering</h2>
          <div class="row g-3">
            <div class="col-md-3">
              <label class="form-label small text-secondary">Max nodes</label>
              <input class="form-control" type="number" value="500" />
            </div>
            <div class="col-md-3">
              <label class="form-label small text-secondary">Max nodes with labels</label>
              <input class="form-control" type="number" value="200" />
            </div>
            <div class="col-md-3">
              <label class="form-label small text-secondary">Default layout</label>
              <select class="form-select">
                <option>force-directed</option>
                <option>cose-bilkent</option>
                <option>circle</option>
                <option>grid</option>
              </select>
            </div>
            <div class="col-md-3">
              <label class="form-label small text-secondary">Animation</label>
              <select class="form-select">
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </select>
            </div>
          </div>
        </div>
      </div>
    </div>

    <p class="small text-secondary mt-3 mb-0">
      <i class="bi bi-info-circle"></i>
      Settings persistence lives at <code>/api/dashboard/settings</code>; the form below currently uses local stub values.
    </p>
  `,
})
export class SettingsComponent {
  readonly theme = inject(ThemeService);
}
