import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [

  {
    path: '',
    redirectTo: 'research/new',
    pathMatch: 'full'
  },

  // Unguarded entry point — the only screen anonymous users can see.
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component')
        .then(m => m.LoginComponent)
  },

  {
    path: 'research/new',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/research-input/research-input.component')
        .then(m => m.ResearchInputComponent)
  },

  // ✅ MUST come BEFORE research/:sessionId
  {
    path: 'research/history',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/research-history/research-history.component')
        .then(m => m.ResearchHistoryComponent)
  },

  {
    path: 'research/history/:sessionId',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/history-detail/history-detail.component')
        .then(m => m.HistoryDetailComponent)
  },

  // ⚠️ dynamic route goes AFTER specific ones
  {
    path: 'research/:sessionId',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/active-research/active-research.component')
        .then(m => m.ActiveResearchComponent)
  },

  {
    path: '**',
    redirectTo: 'research/new'
  }
];
