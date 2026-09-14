# Tasks: Fix History Detail Page Showing Another Session's Content

## Task 1: Clear shared session state before detail fetch + guard stale responses

**Description**: In `HistoryDetailComponent.loadSession()`, reset the root-level `researchSession` signal to null before starting the fetch so the template shows the loading state instead of the previously-viewed session. Capture the requested `sessionId` and ignore any response that no longer matches the current route param (out-of-order responses after fast navigation/retry).

**File**: `research-agent-ui/src/app/features/history-detail/history-detail.component.ts`

**Steps**:
1. In `loadSession()`, add `this.researchService.researchSession.set(null)` after setting `isLoading`/`hasError`
2. Capture `const requestedId = this.sessionId` before subscribing
3. In both `next` and `error` callbacks, early-return when `requestedId !== this.sessionId`

**Verification**: `ng build` passes; manual QA (Task 3).

## Task 2: Apply the same defensive pattern to active-research

**Description**: `ActiveResearchComponent.loadSession()` renders the same shared signal with the identical stale-render pattern. Clear it at load start and guard its success callback against out-of-order responses.

**File**: `research-agent-ui/src/app/features/active-research/active-research.component.ts`

**Steps**:
1. In `loadSession()`, add `this.researchService.researchSession.set(null)` before the fetch
2. Capture `const requestedId = this.sessionId`; early-return in the `next` callback when it no longer matches `this.sessionId`

**Verification**: `ng build` passes; manual QA (Task 3).

## Task 3: Manual QA — Reproduce the reported flow and confirm the fix

**Description**: Verify the exact user scenario (delete → click another card) and adjacent navigation paths.

**Steps**:
1. Start backend + frontend (`mvn spring-boot:run`, `npm start`)
2. Open `/research/history`; open any session's detail page; go back to the list
3. Delete a different session from the list (confirm in dialog)
4. Click another session card → confirm the detail page shows **that** session's topic and report (no flash of a different session, no stale content after load)
5. Repeat: click through 2–3 cards rapidly → each page shows the correct session
6. Open a detail page for a session, then delete that same session from the list in a second tab / via API → confirm error state renders (no stale content)
7. Start a new research, open its active-research page, go back to history, click an old session → detail shows the old session's data; return to the running session's page → it resumes showing the correct session

**Expected result**: Every page renders only the session in its route; loading/error states replace stale content.
