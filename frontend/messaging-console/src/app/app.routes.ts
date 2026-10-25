import { Routes } from '@angular/router';
import { ACCESS } from './core/auth/access';
import { authGuard, roleGuard } from './core/auth/guards';

/**
 * All feature routes are lazy-loaded standalone components.
 * `roleGuard` / `data.roles` are UX only; the API enforces authorization.
 */
export const routes: Routes = [
  { path: 'login', title: 'Sign in', loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', title: 'Dashboard', loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage) },
      { path: 'messages', title: 'Messages', loadComponent: () => import('./features/messages/message-list.page').then((m) => m.MessageListPage) },
      {
        path: 'messages/new', title: 'New message', canActivate: [roleGuard], data: { roles: ACCESS.submit },
        loadComponent: () => import('./features/messages/message-submit.page').then((m) => m.MessageSubmitPage),
      },
      { path: 'messages/:id', title: 'Message detail', loadComponent: () => import('./features/messages/message-detail.page').then((m) => m.MessageDetailPage) },
      { path: 'templates', title: 'Templates', loadComponent: () => import('./features/templates/template-list.page').then((m) => m.TemplateListPage) },
      { path: 'templates/:id', title: 'Template', loadComponent: () => import('./features/templates/template-detail.page').then((m) => m.TemplateDetailPage) },
      {
        path: 'preferences', title: 'Preferences', canActivate: [roleGuard], data: { roles: ACCESS.preferences },
        loadComponent: () => import('./features/preferences/preferences.page').then((m) => m.PreferencesPage),
      },
      {
        path: 'bulk-jobs', title: 'Bulk jobs', canActivate: [roleGuard], data: { roles: ACCESS.bulk },
        loadComponent: () => import('./features/bulk-jobs/bulk-job-list.page').then((m) => m.BulkJobListPage),
      },
      {
        path: 'bulk-jobs/:id', title: 'Bulk job', canActivate: [roleGuard], data: { roles: ACCESS.bulk },
        loadComponent: () => import('./features/bulk-jobs/bulk-job-detail.page').then((m) => m.BulkJobDetailPage),
      },
      {
        path: 'providers', title: 'Providers', canActivate: [roleGuard], data: { roles: ACCESS.providers },
        loadComponent: () => import('./features/providers/providers.page').then((m) => m.ProvidersPage),
      },
      {
        path: 'queues', title: 'Queues', canActivate: [roleGuard], data: { roles: ACCESS.queues },
        loadComponent: () => import('./features/queues/queues.page').then((m) => m.QueuesPage),
      },
      {
        path: 'failed', title: 'Failed messages', canActivate: [roleGuard], data: { roles: ACCESS.failedView },
        loadComponent: () => import('./features/failed/failed.page').then((m) => m.FailedPage),
      },
      {
        path: 'dead-letters', title: 'Dead letters', canActivate: [roleGuard], data: { roles: ACCESS.deadLetterView },
        loadComponent: () => import('./features/dead-letters/dead-letters.page').then((m) => m.DeadLettersPage),
      },
      {
        path: 'delivery-events', title: 'Delivery events', canActivate: [roleGuard], data: { roles: ACCESS.deliveryEvents },
        loadComponent: () => import('./features/delivery-events/delivery-events.page').then((m) => m.DeliveryEventsPage),
      },
      {
        path: 'audit', title: 'Audit history', canActivate: [roleGuard], data: { roles: ACCESS.audit },
        loadComponent: () => import('./features/audit/audit.page').then((m) => m.AuditPage),
      },
      {
        path: 'admin', title: 'Admin', canActivate: [roleGuard], data: { roles: ACCESS.platformAdmin },
        loadComponent: () => import('./features/admin/admin.page').then((m) => m.AdminPage),
      },
      { path: 'forbidden', title: 'Not permitted', loadComponent: () => import('./features/misc/forbidden.page').then((m) => m.ForbiddenPage) },
      { path: '**', title: 'Not found', loadComponent: () => import('./features/misc/not-found.page').then((m) => m.NotFoundPage) },
    ],
  },
];
