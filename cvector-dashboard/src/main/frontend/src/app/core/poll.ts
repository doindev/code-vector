import { Observable, filter, interval } from 'rxjs';

/**
 * Same shape as rxjs `interval(periodMs)` but skips ticks while the tab is in the
 * background (`document.hidden === true`). Cuts wasted polls when the user has the
 * dashboard open in a background tab -- backend queries that no one is looking at.
 *
 * When the user returns to the tab, polling resumes on the next tick. There's a
 * slight latency hit on the very first visible tick (up to `periodMs`), which is
 * deliberate -- components that need instant on-focus refresh should also subscribe
 * to the `visibilitychange` event directly.
 */
export function visiblePoll(periodMs: number): Observable<number> {
  return interval(periodMs).pipe(filter(() => !document.hidden));
}
