# Design: Fix History Cards Not Rendering on First SPA Navigation

## Root Cause Analysis

The `ResearchHistoryService.loadHistory()` method uses callback-based `.subscribe()` with signal updates wrapped in `NgZone.run()`. When the component is lazy-loaded via Angular routes and signals are updated inside async HTTP response callbacks during early zone initialization, the change detection does not reliably propagate to all bound template nodes — particularly cards rendered via `@for` loops.

The metadata signal (`totalElements`) updates correctly because it's a simple scalar binding that triggers change detection even in partial CD cycles. But the `sessions` array update inside the `@for` loop requires full CD propagation, which fails on first lazy-loaded component render due to zone microtask timing.

## Technical Approach

### Change: Refactor `loadHistory()` from callback-based subscribe to RxJS operator chain

**Before (current):**
```typescript
this.http.get<any>(url, { params }).subscribe({
  next: (response) => {
    // ... parse response
    this.ngZone.run(() => {
      this.sessions.set(sessionsData);
      this.currentPage.set(response?.number ?? 0);
      this.totalPages.set(response?.totalPages ?? 0);
      this.totalElements.set(response?.totalElements ?? 0);
    });
  },
  error: (err) => {
    // ... handle error with NgZone.run() wrapping too
  }
});
```

**After:**
```typescript
this.http.get<any>(url, { params }).pipe(
  map(response => {
    const content = response?.content ?? [];
    return {
      sessions: content.map(item => ({...item, ...fieldMapping})),
      number: response?.number ?? 0,
      totalPages: response?.totalPages ?? 0,
      totalElements: response?.totalElements ?? 0
    };
  })
).subscribe({
  next: (result) => {
    this.sessions.set(result.sessions);
    this.currentPage.set(result.number);
    this.totalPages.set(result.totalPages);
    this.totalElements.set(result.totalElements);
  },
  error: (err) => {
    this.sessions.set([]);
    this.currentPage.set(0);
    this.totalPages.set(0);
    this.totalElements.set(0);
  }
});
```

### Why This Fixes the Issue

1. **RxJS operators (`map`) are zone-aware by default** — They integrate with Angular's `NgZone` microtask tracking, ensuring that any signal updates following them trigger full change detection cycles including all bound template nodes.

2. **Eliminates the need for explicit `NgZone.run()` wrapping** — The current code wraps signal updates in `NgZone.run()`, which can cause timing issues when called from within already-running zone microtasks during lazy-loaded component initialization. Using RxJS operators removes this dependency entirely.

3. **Separation of concerns** — Response parsing/mapping happens inside the operator chain (pure function), while signal state mutations happen only in the subscribe callback, making the code cleaner and easier to test.

## Data Model Changes

None. No changes to backend entities, API contracts, or frontend type definitions.

## Testing & Security Considerations

- **Testing**: Verify via manual QA that all cards render on first SPA navigation (no automated test — this is a rendering/timing issue)
- **Security**: No new security surface — same HTTP endpoint called with same params
- **Performance**: Negligible impact — RxJS operators add minimal overhead vs the existing callback approach
