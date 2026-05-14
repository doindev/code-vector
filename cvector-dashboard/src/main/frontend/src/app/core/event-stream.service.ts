import { Injectable, NgZone, OnDestroy, inject } from '@angular/core';
import { Observable, Subject, filter, map } from 'rxjs';

export interface ServerEvent<T = unknown> {
  readonly name: string;
  readonly data: T;
}

/**
 * Single shared EventSource subscription to {@code /api/events}. The backend pushes
 * named events ({@code scan-status}, {@code graph-mutated}, {@code hello}, ...) and
 * heartbeats (SSE comments — invisible to EventSource handlers but keep the
 * connection from idling out).
 *
 * <p>Components subscribe to {@link events} or one of the typed helpers to consume
 * events without each owning their own connection. EventSource itself handles
 * reconnect by default; we surface errors via the same stream so views can show a
 * "disconnected" indicator if they want.
 */
@Injectable({ providedIn: 'root' })
export class EventStreamService implements OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly subject = new Subject<ServerEvent>();
  private source?: EventSource;

  constructor() {
    this.connect();
  }

  /** All events as they arrive. Multicast — many subscribers share one connection. */
  readonly events$ = this.subject.asObservable();

  /** Filter events by their server-side name (e.g. {@code scan-status}). */
  on<T = unknown>(name: string): Observable<T> {
    return this.events$.pipe(
      filter((e) => e.name === name),
      map((e) => e.data as T),
    );
  }

  ngOnDestroy(): void {
    this.source?.close();
    this.subject.complete();
  }

  private connect(): void {
    // Build the EventSource outside Angular's zone so the EventSource's internal
    // network ticks don't trigger change detection. We re-enter the zone explicitly
    // when emitting so Angular components see updates and re-render.
    this.zone.runOutsideAngular(() => {
      this.source = new EventSource('/api/events');
      this.source.addEventListener('scan-status',  (e) => this.dispatch('scan-status',  e));
      this.source.addEventListener('graph-mutated', (e) => this.dispatch('graph-mutated', e));
      this.source.addEventListener('hello',         (e) => this.dispatch('hello',         e));
      // EventSource auto-reconnects on transient errors; we don't replace the source
      // here. If the server is down for a long time the browser keeps retrying with
      // an exponential backoff. Surfacing the error gives views a hint to dim the
      // live-status indicators.
      this.source.onerror = () => this.dispatch('error', null);
    });
  }

  private dispatch(name: string, event: MessageEvent | null): void {
    let data: unknown = null;
    if (event && event.data) {
      try { data = JSON.parse(event.data); }
      catch { data = event.data; }
    }
    this.zone.run(() => this.subject.next({ name, data }));
  }
}
