import { Routes } from '@angular/router';

export const routes: Routes = [

  {
    path: '',
    redirectTo: 'research/new',
    pathMatch: 'full'
  },

  {
    path: 'research/new',
    loadComponent: () =>
      import('./features/research-input/research-input.component')
        .then(m => m.ResearchInputComponent)
  },

  // ✅ MUST come BEFORE research/:sessionId
  {
    path: 'research/history',
    loadComponent: () =>
      import('./features/research-history/research-history.component')
        .then(m => m.ResearchHistoryComponent)
  },

  {
    path: 'research/history/:sessionId',
    loadComponent: () =>
      import('./features/history-detail/history-detail.component')
        .then(m => m.HistoryDetailComponent)
  },

  // ⚠️ dynamic route goes AFTER specific ones
  {
    path: 'research/:sessionId',
    loadComponent: () =>
      import('./features/active-research/active-research.component')
        .then(m => m.ActiveResearchComponent)
  },

  {
    path: '**',
    redirectTo: 'research/new'
  }
];
