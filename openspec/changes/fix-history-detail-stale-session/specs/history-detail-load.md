# Spec: History Detail Page Renders Only the Clicked Session

## Requirement

When a user opens a research session from the history list (or navigates to `/research/history/:sessionId`), the detail page must render only the data of the clicked session — never content left over from a previously-viewed session. The same guarantee applies to the active-research page (`/research/:sessionId`).

### Scenario 1: Clicking a card shows that session's content
**WHEN** a user clicks a history card for session X (after any prior navigation, including after deleting another session)  
**THEN** the detail page shows session X's topic, status chip, steps, and report — not another session's data

### Scenario 2: Loading state instead of stale data during fetch
**WHEN** a detail page is opening and its `GET /api/research/history/{id}` request is still in flight  
**THEN** the page shows a loading indicator ("Loading session...") rather than the previously-viewed session's topic/report

### Scenario 3: Failed fetch never leaves stale content on screen
**WHEN** the detail fetch fails (e.g., 404 after the session was deleted)  
**THEN** the page shows the error state with a Retry button — it does not keep rendering another session's topic/report

### Scenario 4: Rapid navigation never overwrites newer state
**WHEN** a user quickly clicks through multiple history cards (or retries) so that HTTP responses arrive out of order  
**THEN** each detail page displays only the data for the session currently in the route — stale/out-of-order responses are ignored

### Scenario 5: Active-research page has the same guarantee
**WHEN** a user opens `/research/:sessionId` for a running or finished session  
**THEN** the page renders only that session's data (loading indicator during the fetch, no bleed from a previously-viewed session)
