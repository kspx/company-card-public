import { Routes } from '@angular/router';
import { RoleGuard } from './core/guards/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
        import('./pages/login/login.component').then(m => m.LoginComponent),
  },
  {
    path: 'company-card',
    loadComponent: () =>
        import('./pages/company-card-list/company-card-list.view').then(m => m.CompanyCardListView),
    // No guard — read-only browsing is public; edit/create/delete guarded in template via canEdit()
  },
  {
    path: 'company-card/:classId/:instanceId',
    loadComponent: () =>
        import('./pages/company-card/company-card.view').then(m => m.CompanyCardView),
    // No guard — same as above
  },
  {
    path: 'admin',
    loadComponent: () =>
        import('./pages/admin/admin.view').then(m => m.AdminView),
    canActivate: [RoleGuard],
  },
  {
    path: 'stories',
    loadComponent: () =>
        import('./pages/stories/stories.view').then(m => m.StoriesView),
    // No guard — publicly accessible
  },
  {
    path: 'graph',
    loadComponent: () =>
        import('./pages/graph/graph.view').then(m => m.GraphView),
    // No guard — publicly accessible
  },
  {
    path: '',
    redirectTo: 'login',
    pathMatch: 'full',
  },
  {
    path: '**',
    loadComponent: () =>
        import('./pages/not-found/not-found.view').then(m => m.NotFoundView),
  },
];
