import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { PreloadAllModules, provideRouter, withHashLocation, withPreloading } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    // Hash-location keeps `cvector serve -o` working when the dashboard is loaded
    // straight off the filesystem (file:// origins can't use HTML5 history), and
    // it also keeps Spring's SPA fallback simpler -- no server-side path rewrites
    // needed for deep-linked routes like /dashboard/#/monitors.
    //
    // PreloadAllModules: every route uses loadComponent() for code-splitting, which
    // keeps the initial bundle small. Without preloading, the first navigation to
    // each view paid a 0.5–2 s round-trip to fetch its chunk over the wire — the
    // settings page felt particularly bad because its form is large. PreloadAllModules
    // background-fetches every lazy chunk as soon as the initial app is interactive,
    // so all subsequent navigations are instant without growing the critical-path bundle.
    provideRouter(routes, withHashLocation(), withPreloading(PreloadAllModules)),
    provideHttpClient(withFetch()),
  ],
};
