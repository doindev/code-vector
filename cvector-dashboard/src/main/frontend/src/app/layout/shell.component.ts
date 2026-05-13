import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { NgClass } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { ThemeService } from '../core/theme.service';
import { CommandPaletteComponent } from './command-palette.component';

interface NavItem {
  readonly label: string;
  readonly path: string;
  readonly icon: string;
}

interface NavSection {
  readonly title: string;
  readonly items: ReadonlyArray<NavItem>;
}

const NAV: ReadonlyArray<NavSection> = [
  {
    title: 'Workspace',
    items: [
      { label: 'Overview',   path: '/',         icon: 'bi-grid-1x2'        },
      { label: 'Monitors',   path: '/monitors', icon: 'bi-eye'             },
      { label: 'Schedules',  path: '/schedules',icon: 'bi-clock-history'   },
      { label: 'Recent',     path: '/recent',   icon: 'bi-activity'        },
    ],
  },
  {
    title: 'Analysis',
    items: [
      { label: 'Guard',       path: '/guard',       icon: 'bi-shield-check'       },
      { label: 'Health',      path: '/health',      icon: 'bi-heart-pulse'        },
      { label: 'Services',    path: '/services',    icon: 'bi-hdd-network'        },
      { label: 'Flows',       path: '/flows',       icon: 'bi-signpost-split'     },
      { label: 'Audit',       path: '/audit',       icon: 'bi-shield-exclamation' },
      { label: 'Communities', path: '/communities', icon: 'bi-diagram-2'          },
      { label: 'Wiki',        path: '/wiki',        icon: 'bi-journal-text'       },
    ],
  },
  {
    title: 'Explore',
    items: [
      { label: 'Explorer', path: '/explorer', icon: 'bi-diagram-3'   },
      { label: 'Query',    path: '/query',    icon: 'bi-terminal'    },
      { label: 'Graph',    path: '/graph',    icon: 'bi-share'       },
      { label: 'Explain',  path: '/explain',  icon: 'bi-info-circle' },
      { label: 'Impact',   path: '/impact',   icon: 'bi-bullseye'    },
    ],
  },
  {
    title: 'System',
    items: [
      { label: 'Doctor',   path: '/doctor',   icon: 'bi-clipboard-pulse' },
      { label: 'Settings', path: '/settings', icon: 'bi-gear'            },
    ],
  },
];

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
            <div class="cv-nav-section-title">{{ section.title }}</div>
            @for (item of section.items; track item.path) {
              <a class="cv-nav-link"
                 [routerLink]="item.path"
                 routerLinkActive="cv-nav-link--active"
                 [routerLinkActiveOptions]="{exact: item.path === '/'}">
                <i class="bi" [ngClass]="item.icon"></i>
                <span>{{ item.label }}</span>
              </a>
            }
          }
        </nav>
        <div class="cv-sidebar-footer">
          <button class="btn btn-sm btn-outline-secondary w-100 mb-2"
                  type="button"
                  (click)="palette.openPalette()">
            <i class="bi bi-command"></i>
            <span class="ms-2 small">Quick open</span>
            <span class="ms-auto small text-secondary">{{ palette.shortcut }}</span>
          </button>
          <button class="btn btn-sm btn-outline-secondary w-100"
                  type="button"
                  (click)="theme.toggle()">
            <i class="bi" [ngClass]="themeIcon()"></i>
            <span class="ms-2">{{ theme.theme() === 'dark' ? 'Light' : 'Dark' }} theme</span>
          </button>
        </div>
      </aside>
      <main class="cv-content">
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
}
