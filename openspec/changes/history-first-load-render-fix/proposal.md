# Proposal: Fix History Page Cards Not Rendering on First SPA Navigation

## Why (Problem Statement)

When users navigate to `/research/history` via SPA routing for the first time, only the first research session card renders its content — all other cards appear blank. The metadata ("35 history items") displays correctly in the header, confirming the data is fetched and signals are updated, but change detection does not propagate to the card template bindings on initial lazy-loaded component render.

After a browser F5 reload (full page refresh), all 35 cards render their content correctly. This confirms:
- Backend API returns complete data reliably
- Frontend data fetching works correctly
- The issue is purely a client-side change detection timing problem specific to lazy-loaded components

## What (Changes)

Fix the `ResearchHistoryService.loadHistory()` method so that signal updates inside async HTTP response callbacks trigger reliable change detection for ALL bound template nodes — including cards rendered via `@for` loops in the history component.

**No backend changes.** This is a frontend-only fix targeting `research-agent-ui/src/app/core/services/research-history.service.ts`.

## Impact

- **Files affected**: 1 file (frontend service only)
- **Risk**: Low — refactor replaces callback-based subscribe with RxJS operator chain that integrates natively with Angular's zone-aware change detection
- **Breaking changes**: None — behavior is identical from the user's perspective; internal implementation changes

## Assumptions Made

1. The backend `/api/research/history` endpoint returns complete data (all 35 items) on every request, including during first SPA navigation — confirmed by F5 reload working correctly
2. The issue is specifically a zone-aware change detection timing problem when signals update inside async callbacks during lazy-loaded component initialization
3. Other lazy-loaded feature pages in the app do not have similar issues (user has only reported this one)
4. The fix should apply to both `loadHistory()` and any future service methods that similarly update signals inside subscribe callbacks
