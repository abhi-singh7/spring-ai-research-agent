# Tasks: Fix History Cards Not Rendering on First SPA Navigation

## Task 1: Refactor `ResearchHistoryService.loadHistory()` to use RxJS operators

**Description**: Replace callback-based `.subscribe()` with `.pipe(map(), tap())` operator chain, removing the need for explicit `NgZone.run()` wrapping. Signal updates move into a clean subscribe callback after the mapping pipe completes (which is zone-aware).

**File**: `research-agent-ui/src/app/core/services/research-history.service.ts`

**Steps**:
1. Add RxJS imports: `map`, `tap` from `'rxjs/operators'` and keep existing `Observable` import
2. Replace the HTTP call block in `loadHistory()` with `.pipe(map(...)).subscribe({next, error})` pattern
3. Inside the `map` operator: parse response, build sessions data array, return `{sessions, number, totalPages, totalElements}` tuple
4. In subscribe callback: set signals from the mapped result (no NgZone.run() needed)
5. Same treatment for error handler — set empty signal values without NgZone wrapping

**Verification**: Build passes with no type errors; lint clean.

## Task 2: Remove unused `NgZone` injection from service

**Description**: After refactoring, the service no longer needs `inject(NgZone)` since signals are updated outside `NgZone.run()` wrappers (RxJS operators handle zone integration). Clean up the import and field declaration.

**File**: `research-agent-ui/src/app/core/services/research-history.service.ts`

**Steps**:
1. Remove `NgZone` from imports
2. Remove `private ngZone = inject(NgZone);` field

## Task 3: Manual QA — Verify all cards render on first SPA navigation

**Description**: Start the dev server, navigate to `/research/history` via SPA routing for the first time (not F5), and confirm all history session cards display their content correctly.

**Steps**:
1. Run `npm start` in `research-agent-ui/` (or use existing dev server)
2. Clear browser cache / hard refresh once to ensure clean state
3. Navigate directly to `/research/history` via SPA route (click nav link or type URL — but do NOT F5 from another page)
4. Confirm: all history session cards render with full content (topic, status chip, date, delete button) on initial load
5. Confirm: "X history items" count in header shows correct total
6. Confirm: pagination controls work correctly if > 20 sessions

**Expected result**: All 35 cards render their content immediately — no blank card placeholders. Same as F5 reload behavior but without needing the refresh.
