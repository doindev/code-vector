import { Routes } from '@angular/router';

import { ShellComponent } from './layout/shell.component';

export const routes: Routes = [
  {
    path: '',
    component: ShellComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () =>
          import('./views/overview/overview.component').then((m) => m.OverviewComponent),
      },
      {
        path: 'monitors',
        loadComponent: () =>
          import('./views/monitors/monitors.component').then((m) => m.MonitorsComponent),
      },
      {
        path: 'schedules',
        loadComponent: () =>
          import('./views/schedules/schedules.component').then((m) => m.SchedulesComponent),
      },
      {
        path: 'explorer',
        loadComponent: () =>
          import('./views/explorer/explorer.component').then((m) => m.ExplorerComponent),
      },
      {
        path: 'recent',
        loadComponent: () =>
          import('./views/recent/recent.component').then((m) => m.RecentComponent),
      },
      {
        path: 'guard',
        loadComponent: () =>
          import('./views/guard/guard.component').then((m) => m.GuardComponent),
      },
      {
        path: 'wiki',
        loadComponent: () =>
          import('./views/wiki/wiki.component').then((m) => m.WikiComponent),
      },
      {
        path: 'health',
        loadComponent: () =>
          import('./views/health/health.component').then((m) => m.HealthComponent),
      },
      {
        path: 'services',
        loadComponent: () =>
          import('./views/services/services.component').then((m) => m.ServicesComponent),
      },
      {
        path: 'flows',
        loadComponent: () =>
          import('./views/flows/flows.component').then((m) => m.FlowsComponent),
      },
      {
        path: 'audit',
        loadComponent: () =>
          import('./views/audit/audit.component').then((m) => m.AuditComponent),
      },
      {
        path: 'communities',
        loadComponent: () =>
          import('./views/communities/communities.component').then((m) => m.CommunitiesComponent),
      },
      {
        path: 'doctor',
        loadComponent: () =>
          import('./views/doctor/doctor.component').then((m) => m.DoctorComponent),
      },
      {
        path: 'query',
        loadComponent: () =>
          import('./views/query/query.component').then((m) => m.QueryComponent),
      },
      {
        path: 'graph',
        loadComponent: () =>
          import('./views/graph/graph.component').then((m) => m.GraphComponent),
      },
      {
        path: 'explain',
        loadComponent: () =>
          import('./views/explain/explain.component').then((m) => m.ExplainComponent),
      },
      {
        path: 'impact',
        loadComponent: () =>
          import('./views/impact/impact.component').then((m) => m.ImpactComponent),
      },
      {
        path: 'settings',
        loadComponent: () =>
          import('./views/settings/settings.component').then((m) => m.SettingsComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
