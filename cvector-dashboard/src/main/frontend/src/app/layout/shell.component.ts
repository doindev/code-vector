import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NgClass } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { ThemeService } from '../core/theme.service';
import { CommandPaletteComponent } from './command-palette.component';

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
 * hovers the tile. The flyout stays open as long as the cursor is over the tile or the
 * flyout panel itself, plus a brief 250 ms grace period to forgive a fast diagonal cursor
 * jump from tile to flyout.
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

/** How long the flyout stays open after the cursor leaves both the tile and the flyout. */
const HIDE_DELAY_MS = 250;

@Component({
  selector: 'cv-shell',
  standalone: true,
  imports: [NgClass, RouterLink, RouterLinkActive, RouterOutlet, CommandPaletteComponent],
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
                 (mouseenter)="onTileEnter(section.title)"
                 (mouseleave)="onTileLeave()">
              <button class="cv-nav-tile"
                      [class.cv-nav-tile--active]="isSectionActive(section)"
                      [class.cv-nav-tile--open]="openSection() === section.title"
                      (click)="toggleSection(section.title)"
                      (focus)="onTileEnter(section.title)">
                <i class="bi" [ngClass]="section.icon"></i>
                <span class="cv-nav-tile-label">{{ section.title }}</span>
              </button>
              @if (openSection() === section.title) {
                <div class="cv-nav-flyout"
                     (mouseenter)="onFlyoutEnter()"
                     (mouseleave)="onFlyoutLeave()">
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
  readonly themeIcon = computed(() =>
    this.theme.theme() === 'dark' ? 'bi-sun' : 'bi-moon-stars'
  );

  private readonly router = inject(Router);

  /** Which section's flyout is currently visible (title), or '' for none. */
  readonly openSection = signal<string>('');
  /** Pending hide-timer id; cleared if the cursor re-enters before it fires. */
  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  onTileEnter(title: string): void {
    this.cancelHide();
    this.openSection.set(title);
  }

  onTileLeave(): void {
    this.scheduleHide();
  }

  onFlyoutEnter(): void {
    this.cancelHide();
  }

  onFlyoutLeave(): void {
    this.scheduleHide();
  }

  toggleSection(title: string): void {
    // Tap-to-toggle for touch / keyboard users. Mouse hover handles it for pointer users.
    this.cancelHide();
    this.openSection.set(this.openSection() === title ? '' : title);
  }

  closeFlyout(): void {
    this.cancelHide();
    this.openSection.set('');
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

  private scheduleHide(): void {
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
}
