import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Thin wrapper around HttpClient pointing at the cvector backend's /api root. The
 * proxy.conf.json in this project rewrites /api/* to http://localhost:2969 in
 * development; in production the SPA and the API share an origin (Spring serves
 * both), so the same relative path works.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  status(): Observable<{ service: string; dashboardVersion: string; ok: boolean }> {
    return this.http.get<{ service: string; dashboardVersion: string; ok: boolean }>(
      '/api/dashboard/status'
    );
  }

  health(): Observable<unknown> {
    return this.http.get('/api/health');
  }

  stats(): Observable<unknown> {
    return this.http.get('/api/stats');
  }

  projects(): Observable<unknown> {
    return this.http.get('/api/projects');
  }

  search(query: string, limit = 25): Observable<unknown> {
    return this.http.get('/api/search', { params: { q: query, limit } });
  }
}
