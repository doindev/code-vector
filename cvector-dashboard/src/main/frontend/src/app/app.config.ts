import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withHashLocation } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    // Hash-location keeps `cvector serve -o` working when the dashboard is loaded
    // straight off the filesystem (file:// origins can't use HTML5 history), and
    // it also keeps Spring's SPA fallback simpler -- no server-side path rewrites
    // needed for deep-linked routes like /dashboard/#/monitors.
    provideRouter(routes, withHashLocation()),
    provideHttpClient(withFetch()),
  ],
};
