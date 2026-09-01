# Design: Fix History Detail Page Showing Another Session's Content

## Root Cause Analysis

`ResearchService.researchSession` is a root-level shared signal that persists across all SPA navigation. Both `HistoryDetailComponent` and `ActiveResearchComponent` render it directly, and both bind it *first* in their templates:

```html
@if (researchSession()) { ... }        <!-- renders whatever is currently held -->
@else if (isLoading()) { Loading... }  <!-- only reached when the signal is null -->
```

Neither component cleared the signal before starting its fetch. So on navigation to a detail page, the previously-viewed session remained in `researchSession` for the duration of the new `GET /api/research/history/{id}` request — and permanently if that request failed (the error path set `hasError` but left the stale signal intact). The page therefore rendered another session's topic, status chip, steps, and report: "loading some other research history."

The backend is not at fault: `getHistoricalSession` → `findByIdWithSteps(UUID)` fetches strictly by primary key, so per-ID data is always correct. Deletion of an unrelated session does not shift any IDs (stable UUIDs). The delete-then-click sequence simply made the stale-state bleed visible.

## Technical Approach

### Change 1: Clear shared state at load start + guard stale responses (history-detail)

**Before:**
```typescript
loadSession(): void {
  this.isLoading.set(true);
  this.hasError.set(false);
  this.researchService.getHistoricalSession(this.sessionId).subscribe({
    next: (session) => {
      this.researchService.researchSession.set(session);
      this.isLoading.set(false);
    },
    error: (error) => { ... this.hasError.set(true); }
  });
}
```

**After:**
```typescript
loadSession(): void {
  this.isLoading.set(true);
  this.hasError.set(false);
  this.researchService.researchSession.set(null);   // show loading state, not stale data
  const requestedId = this.sessionId;
  this.researchService.getHistoricalSession(requestedId).subscribe({
    next: (session) => {
      if (requestedId !== this.sessionId) return;   // drop out-of-order responses
      this.researchService.researchSession.set(session);
      this.isLoading.set(false);
    },
    error: (error) => {
      if (requestedId !== this.sessionId) return;
      ... this.hasError.set(true);
    }
  });
}
```

### Change 2: Same defensive pattern in active-research

`ActiveResearchComponent.loadSession()` had the identical pattern on the same shared signal — cleared at load start with the same `requestedId` guard, so it can never render another session's data during its fetch window either.

## Why This Is Safe

1. **Clearing only changes the loading window** — before: stale session rendered; after: "Loading session..." / "Loading research session..." renders (both states already existed in the templates).
2. **The guard only skips mismatched responses** — a response is dropped only if `sessionId` changed since the request started (fast navigation or retry), which is exactly the out-of-order case that would otherwise overwrite newer state.
3. **No signal-semantics change** — `researchSession` still means "the session currently being viewed"; clearing it when a new view begins loading is the expected lifecycle.

## Data Model / API Changes

None. Backend unchanged.

## Testing Considerations

- No automated tests exist for this flow (frontend has no runnable test script). Verified via `ng build` (passes) and manual QA per tasks.md.
- Security: no new surface — same endpoints, client-state only.
