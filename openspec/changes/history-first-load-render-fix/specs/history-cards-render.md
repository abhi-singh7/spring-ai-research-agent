# Spec: History Cards Render on First SPA Navigation

## Requirement

When users navigate to `/research/history` for the first time (SPA routing, not browser F5 reload), all research session cards must render their full content immediately — no blank card placeholders. The metadata counter ("X history items") and pagination controls should also display correctly.

### Scenario 1: First SPA navigation shows all cards with content
**WHEN** a user navigates to `/research/history` via SPA routing (not F5 reload)  
**THEN** all research session cards render their full content (topic, status chip, date info, delete button) on initial page load — no blank or empty card placeholders

### Scenario 2: Metadata counter displays correct total
**WHEN** the history page loads with `N` sessions  
**THEN** the header shows "N history items" (or "N history item" for singular) immediately

### Scenario 3: Pagination controls appear when needed
**WHEN** there are more than 20 research sessions in history  
**THEN** pagination controls (Previous / Next buttons with page indicator) render correctly below the card list, and navigating between pages loads the correct subset of cards with full content on each SPA navigation

### Scenario 4: Search/filter still works
**WHEN** a user types into the search bar after first navigation  
**THEN** history is re-fetched via `/api/research/history/search` endpoint and filtered results render correctly in all cards (no blank placeholders)

### Scenario 5: Error state renders cleanly
**WHEN** the backend returns an HTTP error during history fetch on first SPA navigation  
**THEN** the component displays a clean error state or empty state message instead of showing partially-rendered blank cards, without throwing unhandled exceptions to the console
