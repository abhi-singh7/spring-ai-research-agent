# Proposal: Fix History Detail Page Showing Another Session's Content

## Why (Problem Statement)

After deleting a research history item, clicking another session card in the history list opened a detail page whose content (topic header, status chip, steps, report) did not match the clicked card — it appeared to load a different research session.

**Root cause:** `HistoryDetailComponent` renders the shared root-level `researchSession` signal from `ResearchService`, which persists across navigations. The template checks `@if (researchSession())` *before* `@else if (isLoading())`, and `loadSession()` never cleared the signal before fetching. So while the clicked session's `GET /api/research/history/{id}` request was in flight, the page rendered whatever session was left in the shared state from a previous detail visit or an active research run — i.e., another session's content. If the fetch was slow or failed, the wrong content stayed on screen.

## What (Changes)

Frontend-only fix:

1. `history-detail.component.ts` — `loadSession()` now clears the shared `researchSession` signal before fetching (page shows "Loading session..." instead of stale data) and ignores out-of-order/stale responses that don't match the current route's `sessionId`.
2. `active-research.component.ts` — same defensive clear + stale-response guard in `loadSession()`, since it renders the same shared signal with the identical pattern.

**No backend changes.** The `GET /api/research/history/{sessionId}` endpoint already fetches strictly by ID (`findByIdWithSteps`) and returns correct data; the mismatch was purely client-side state bleed.

## Impact

- **Files affected**: 2 frontend components
- **Risk**: Low — clearing a shared signal at load start only changes what renders during the (brief) fetch window: a loading state instead of stale data. Stale-response guard only skips responses whose `sessionId` no longer matches the route.
- **Breaking changes**: None

## Assumptions Made

1. The backend history detail endpoint returns correct per-ID data (verified in code — `findByIdWithSteps(UUID)` fetch-join by primary key).
2. The shared `researchSession` signal is intended as "the session currently being viewed"; clearing it when a new view begins loading is the expected lifecycle.
3. No automated tests exist for this flow; verified via `ng build` and manual QA (delete → click another card → correct content loads).
