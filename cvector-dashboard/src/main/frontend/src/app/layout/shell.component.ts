import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgClass } from '@angular/common';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { ThemeService } from '../core/theme.service';
import { CypherRefService } from '../core/cypher-ref.service';
import { CommandPaletteComponent } from './command-palette.component';
import { CypherRefPanelComponent } from './cypher-ref-panel.component';

interface NavItem {
  readonly label: string;
  readonly path: string;
  readonly icon: string;
}

interface NavSection {
  readonly title: string;
  readonly icon: string;
  readonly items: ReadonlyArray<NavItem>;
}

/**
 * Four root sections — the prior flat list scrolled because there are 28+ routes. Each root
 * tile shows just an icon + label; the section's items reveal in a flyout when the user
 * clicks the tile. The flyout auto-closes after a short grace period once the cursor
 * leaves both the tile and the flyout panel, or when the user clicks elsewhere / selects
 * a sub-item / clicks the tile again. Hover alone never opens — that's click-only.
 */
const NAV: ReadonlyArray<NavSection> = [
  {
    title: 'Workspace',
    icon: 'bi-house-door',
    items: [
      { label: 'Overview',   path: '/',         icon: 'bi-grid-1x2'        },
      { label: 'Projects',   path: '/projects', icon: 'bi-collection'      },
      { label: 'Monitors',   path: '/monitors', icon: 'bi-eye'             },
      { label: 'Schedules',  path: '/schedules',icon: 'bi-clock-history'   },
      { label: 'Recent',     path: '/recent',   icon: 'bi-activity'        },
      { label: 'Changelog',  path: '/changelog',icon: 'bi-journal-arrow-up'},
    ],
  },
  {
    title: 'Analysis',
    icon: 'bi-bar-chart-line',
    items: [
      { label: 'Guard',       path: '/guard',       icon: 'bi-shield-check'       },
      { label: 'Rules',       path: '/rules',       icon: 'bi-clipboard-check'    },
      { label: 'Health',      path: '/health',      icon: 'bi-heart-pulse'        },
      { label: 'Services',    path: '/services',    icon: 'bi-hdd-network'        },
      { label: 'Flows',       path: '/flows',       icon: 'bi-signpost-split'     },
      { label: 'Audit',       path: '/audit',       icon: 'bi-shield-exclamation' },
      { label: 'Communities', path: '/communities', icon: 'bi-diagram-2'          },
      { label: 'Duplicates',  path: '/duplicates',  icon: 'bi-files'              },
      { label: 'Wiki',        path: '/wiki',        icon: 'bi-journal-text'       },
    ],
  },
  {
    title: 'Explore',
    icon: 'bi-compass',
    items: [
      { label: 'Explorer', path: '/explorer', icon: 'bi-diagram-3'         },
      { label: 'Query',    path: '/query',    icon: 'bi-terminal'          },
      { label: 'Graph',    path: '/graph',    icon: 'bi-share'             },
      { label: 'Explain',  path: '/explain',  icon: 'bi-info-circle'       },
      { label: 'Impact',   path: '/impact',   icon: 'bi-bullseye'          },
      { label: 'Trace',    path: '/trace',    icon: 'bi-signpost-2'        },
      { label: 'DB impact',path: '/db-impact',icon: 'bi-database'          },
      { label: 'Rename',   path: '/rename',   icon: 'bi-input-cursor-text' },
      { label: 'PR impact',path: '/pr-impact',icon: 'bi-git'               },
      { label: 'Migrate',  path: '/migrate',  icon: 'bi-arrow-right-square'},
      { label: 'Diff',     path: '/diff',     icon: 'bi-arrow-left-right'  },
    ],
  },
  {
    title: 'System',
    icon: 'bi-sliders',
    items: [
      { label: 'Doctor',   path: '/doctor',   icon: 'bi-clipboard-pulse' },
      { label: 'Settings', path: '/settings', icon: 'bi-gear'            },
    ],
  },
];

/** Grace period after the cursor leaves the section before the flyout auto-closes. */
const HIDE_DELAY_MS = 400;

@Component({
  selector: 'cv-shell',
  standalone: true,
  imports: [NgClass, RouterLink, RouterLinkActive, RouterOutlet, CommandPaletteComponent, CypherRefPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cv-shell">
      <aside class="cv-sidebar">
        <div class="cv-brand">
          <i class="bi bi-diagram-3-fill cv-accent"></i>
          <span class="cv-brand-text">cvector</span>
        </div>
        <nav class="cv-nav">
          @for (section of nav; track section.title) {
            <div class="cv-nav-section"
                 (mouseenter)="onSectionEnter()"
                 (mouseleave)="onSectionLeave()">
              <button class="cv-nav-tile"
                      [class.cv-nav-tile--active]="isSectionActive(section)"
                      [class.cv-nav-tile--open]="openSection() === section.title"
                      (click)="toggleSection(section.title)">
                <i class="bi" [ngClass]="section.icon"></i>
                <span class="cv-nav-tile-label">{{ section.title }}</span>
              </button>
              @if (openSection() === section.title) {
                <div class="cv-nav-flyout">
                  <div class="cv-nav-flyout-title">{{ section.title }}</div>
                  @for (item of section.items; track item.path) {
                    <a class="cv-nav-link"
                       [routerLink]="item.path"
                       routerLinkActive="cv-nav-link--active"
                       [routerLinkActiveOptions]="{exact: item.path === '/'}"
                       (click)="closeFlyout()">
                      <i class="bi" [ngClass]="item.icon"></i>
                      <span>{{ item.label }}</span>
                    </a>
                  }
                </div>
              }
            </div>
          }
        </nav>
        <div class="cv-sidebar-footer">
          <button class="btn btn-sm btn-outline-secondary w-100 mb-2 cv-footer-btn"
                  type="button"
                  (click)="palette.openPalette()"
                  title="Quick open">
            <i class="bi bi-command"></i>
            <span class="cv-footer-btn-label">Open</span>
          </button>
          <button class="btn btn-sm btn-outline-secondary w-100 cv-footer-btn"
                  type="button"
                  (click)="theme.toggle()"
                  [title]="theme.theme() === 'dark' ? 'Light theme' : 'Dark theme'">
            <i class="bi" [ngClass]="themeIcon()"></i>
            <span class="cv-footer-btn-label">{{ theme.theme() === 'dark' ? 'Light' : 'Dark' }}</span>
          </button>
        </div>
      </aside>
      @if (cypherRef.visible() && isQueryRoute()) {
        <cv-cypher-ref-panel />
      }
      <main class="cv-content" (click)="closeFlyout()">
        <router-outlet />
      </main>
    </div>
    <cv-command-palette #palette />
  `,
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  readonly nav = NAV;
  readonly theme = inject(ThemeService);
  readonly cypherRef = inject(CypherRefService);
  readonly themeIcon = computed(() =>
    this.theme.theme() === 'dark' ? 'bi-sun' : 'bi-moon-stars'
  );

  private readonly router = inject(Router);

  /** Current top-level URL (updated on NavigationEnd) — drives the per-route gating of
      the Cypher Reference panel which only exists on /query. */
  private readonly currentUrl = signal(this.router.url);
  readonly isQueryRoute = computed(() => {
    const u = this.currentUrl();
    return u === '/query' || u.startsWith('/query?') || u.startsWith('/query/') || u.startsWith('/query#');
  });

  constructor() {
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((e) => this.currentUrl.set(e.urlAfterRedirects));
  }

  /** Which section's flyout is currently visible (title), or '' for none. */
  readonly openSection = signal<string>('');
  /** Pending auto-close timer; cleared if the cursor re-enters before it fires. */
  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  toggleSection(title: string): void {
    this.cancelHide();
    this.openSection.set(this.openSection() === title ? '' : title);
  }

  closeFlyout(): void {
    this.cancelHide();
    this.openSection.set('');
  }

  /**
   * Re-entering the section cancels any pending auto-close. Mouseenter never *opens* the
   * flyout — only click does — so this is purely a "user came back, keep it open" signal.
   */
  onSectionEnter(): void {
    this.cancelHide();
  }

  /** Schedule a hide after the grace period; cancelled if the cursor returns. */
  onSectionLeave(): void {
    if (this.openSection() === '') return;  // nothing to hide
    this.cancelHide();
    this.hideTimer = setTimeout(() => {
      this.openSection.set('');
      this.hideTimer = null;
    }, HIDE_DELAY_MS);
  }

  private cancelHide(): void {
    if (this.hideTimer != null) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
  }

  /** Is the user currently on a route owned by this section? Used for tile highlight. */
  isSectionActive(section: NavSection): boolean {
    const url = this.router.url;
    if (url === '/' || url.startsWith('/?')) {
      return section.title === 'Workspace';  // Overview lives under Workspace
    }
    return section.items.some(
      (item) => item.path !== '/' && (url === item.path || url.startsWith(item.path + '/') || url.startsWith(item.path + '?')),
    );
  }
}
