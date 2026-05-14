import { Injectable, signal } from '@angular/core';

/**
 * Visibility coordinator for the Cypher Reference side panel. Lives at the root level
 * because the toggle (Query view) and the panel (rendered in the shell so it sits between
 * the sidebar and main content) are in different components. A signal is the minimum
 * coupling needed to keep them in sync.
 */
@Injectable({ providedIn: 'root' })
export class CypherRefService {
  /** True when the panel should be visible. Persists across route changes. */
  readonly visible = signal(false);

  toggle(): void {
    this.visible.update((v) => !v);
  }

  close(): void {
    this.visible.set(false);
  }
}
