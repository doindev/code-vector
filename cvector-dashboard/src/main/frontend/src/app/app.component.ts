import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { ThemeService } from './core/theme.service';

@Component({
  selector: 'cv-root',
  standalone: true,
  imports: [RouterOutlet],
  template: `<router-outlet />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  // Injecting ThemeService here so its constructor runs at app start -- it reads
  // the persisted preference from localStorage and applies data-bs-theme to <html>
  // before any view renders. Avoids a flash of light theme on dark-mode page loads.
  private readonly _theme = inject(ThemeService);
}
