import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

/**
 * Theme preference manager. Tracks user choice across three layers, in priority order:
 *   1. localStorage (synchronous, used for instant first-render)
 *   2. {@code (prefers-color-scheme: dark)} media query (fallback for first-time visitors)
 *   3. backend persistence (last-write-wins; survives across browsers / sessions)
 *
 * The backend round-trip is asynchronous and happens *after* the synchronous local read
 * has already applied a theme to {@code <html>}. If the backend value differs from local,
 * we swap to it. Avoids a flash of the wrong theme on slow connections.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private static readonly STORAGE_KEY = 'cv.theme';
  private static readonly SETTINGS_ENDPOINT = '/api/dashboard/settings';
  private static readonly SETTINGS_KEY = 'theme';

  private readonly http = inject(HttpClient);

  readonly theme = signal<Theme>(this.resolveInitial());

  constructor() {
    this.apply(this.theme());
    // Backend sync runs async; don't block constructor. On 404 (dashboard module not built)
    // we just keep the localStorage value -- everything still works.
    this.hydrateFromBackend();
  }

  toggle(): void {
    this.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.theme.set(theme);
    this.apply(theme);
    this.persistLocal(theme);
    this.persistRemote(theme);
  }

  private apply(theme: Theme): void {
    document.documentElement.setAttribute('data-bs-theme', theme);
  }

  private resolveInitial(): Theme {
    try {
      const saved = localStorage.getItem(ThemeService.STORAGE_KEY);
      if (saved === 'dark' || saved === 'light') return saved;
    } catch { /* private mode -- ignore */ }
    if (typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return 'dark';
    }
    return 'light';
  }

  private persistLocal(theme: Theme): void {
    try { localStorage.setItem(ThemeService.STORAGE_KEY, theme); } catch { /* quota -- ignore */ }
  }

  private persistRemote(theme: Theme): void {
    this.http.put(ThemeService.SETTINGS_ENDPOINT, { [ThemeService.SETTINGS_KEY]: theme }).subscribe({
      // The PUT returns the merged settings; we don't need the response body here.
      error: () => { /* dashboard module not built or backend offline -- non-fatal */ },
    });
  }

  private hydrateFromBackend(): void {
    this.http.get<Record<string, unknown>>(ThemeService.SETTINGS_ENDPOINT).subscribe({
      next: (settings) => {
        const remote = settings?.[ThemeService.SETTINGS_KEY];
        if ((remote === 'dark' || remote === 'light') && remote !== this.theme()) {
          // Backend has a different preference than the local guess. Adopt it; the user's
          // explicit toggle later will overwrite via persistRemote().
          this.theme.set(remote);
          this.apply(remote);
          this.persistLocal(remote);
        }
      },
      error: () => { /* expected when dashboard module isn't on the classpath */ },
    });
  }
}
