# Design: Research History Deletion

## Technical Approach

### Backend Architecture

Follow existing patterns in `ResearchController` and `ResearchOrchestratorService`:

1. **Single deletion**: Add a new `DELETE /api/research/history/{sessionId}` endpoint in `ResearchController` that delegates to `ResearchOrchestratorService.deleteSession(sessionId)`. The service method simply calls `sessionRepository.deleteById(sessionId)` — cascading delete for steps is automatic via `orphanRemoval = true`.

2. **Bulk deletion**: Add a new `POST /api/research/history/bulk-delete` endpoint (using POST because we're sending a request body with session IDs). The controller accepts an array of UUIDs, validates them exist, then calls `sessionRepository.deleteAllInBatch(sessionList)`. Using `deleteAllInBatch()` ensures atomic rollback semantics — if any delete fails, the entire batch fails and no items are removed.

### Frontend Architecture

Follow existing patterns in `ResearchHistoryComponent`:
- Use Angular Material's `MatDialog` for confirmation dialogs (already available via `MatButtonModule` import)
- Use Angular Material's `MatSnackBar` for success/error notifications
- Track selection state with a local `Set<UUID>` signal — items are selected when user clicks the checkbox on each card in multi-select mode

### Multi-Select UI Pattern (Approach A)

1. Add an **Action Toolbar** at the top of the history page:
   - Default state: Show "Delete Selected" text and a delete icon button, both disabled
   - When items are selected: Show count ("3 selected") and enable the Delete button
2. Add checkbox on each card (left side), styled consistently with existing Material styling
3. When user clicks Delete toolbar action → open `MatDialog` confirmation dialog
4. On confirm → call bulk delete API, show snackbar with count of deleted items
5. After successful deletion → refresh the history list (reload current page)

### Confirmation Dialog Design

Both single and bulk delete use the same confirmation approach:

**Single Delete Dialog:**
- Title: "Delete Research Session"
- Content: "This action will permanently delete this research session and all its associated steps. This cannot be undone."
- Topic/summary shown for context
- Buttons: Cancel (primary), Delete Permanently (warn/error color)

**Bulk Delete Dialog:**
- Title: "Delete Selected Sessions"
- Content: "This will permanently delete X selected research sessions and their associated data. This action cannot be undone."
- List of topics being deleted (scrollable if many)
- Buttons: Cancel (primary), Delete Permanently (warn/error color)

### Error Handling Strategy

- **Single deletion failure**: Show error snackbar ("Failed to delete session"), no redirect — user stays on the list page
- **Bulk deletion failure**: Show error snackbar with count of failed items ("Failed to delete 1 session", etc.) — rollback already guaranteed by `deleteAllInBatch()` atomicity

### Existing Patterns Referenced

| Pattern | Source File | How Used |
|---------|-------------|----------|
| Dialog for confirmation | Angular Material `MatDialog` (from MatButtonModule) | Standard MatDialog usage with template or inline config |
| Snackbar notifications | Angular Material `MatSnackBar` | Show success/error messages after action completion |
| Signal state management | `research-history.service.ts` | Track selection via signal in service, consumed in component |
| Card layout for history items | `research-history.component.ts` | Add checkbox to existing card structure without changing styling pattern |

## Data Model Changes
**No entity changes needed.** Hard delete is handled by JPA repository. Cascading delete of steps already works via `orphanRemoval = true`.

## API Changes (Backend)

### New Endpoints

#### Single Delete — DELETE `/api/research/history/{sessionId}`
| Field | Value |
|-------|-------|
| Method | DELETE |
| Path | /api/research/history/{sessionId} |
| Description | Permanently delete a completed research session and its steps. Only works on non-running sessions (COMPLETED, FAILED, CANCELLED). Does NOT cancel PROCESSING sessions — use the existing cancel endpoint for that. |
| Request Body | None |
| Success Response | 204 No Content |
| Error Responses | 404 Not Found (session doesn't exist), 409 Conflict (session is still PROCESSING) |

#### Bulk Delete — POST `/api/research/history/bulk-delete`
| Field | Value |
|-------|-------|
| Method | POST |
| Path | /api/research/history/bulk-delete |
| Description | Permanently delete multiple research sessions and their steps in a single operation. All deletes succeed or all fail (atomic rollback). Only works on non-running sessions. |
| Request Body | `{ "sessionIds": ["uuid-1", "uuid-2", ...] }` — array of UUID strings |
| Success Response | 204 No Content |
| Error Responses | 500 Internal Server Error (one or more fails, all rolled back) |

### Modified Endpoints
**None.** Existing endpoints remain unchanged:
- `GET /api/research/history` — paginated history list (no change needed for pagination after deletion)
- `GET /api/research/history/search` — search history by topic (no change)
- `DELETE /api/research/{sessionId}` — cancel running session (unchanged, still only works on PROCESSING sessions)

## Frontend Changes

### Modified Component: `ResearchHistoryComponent`

**New imports added to component:**
- `MatCheckboxModule` for card checkboxes
- `MatButtonModule` for toolbar action buttons (already imported, just need to add delete button)
- `MatDialogModule` for confirmation dialogs
- `MatSnackBarModule` for success/error notifications

**New signal/state:**
- `selection = new Set<UUID>()` — tracks selected session IDs

**New methods:**
- `toggleSelect(sessionId)` — toggle a session in/out of the selection set
- `selectCurrentPage()` — select all sessions on current page
- `deselectAll()` — clear all selections
- `hasCurrentSelection()` — signal returning true if any items selected
- `selectedCount` — signal returning number of selected items
- `deleteSelectedSessions()` — trigger bulk delete flow: confirm → API call → snackbar

**UI changes to existing template:**
1. Add checkbox `<mat-checkbox>` on the left side of each history card (outside the routerLink)
2. Replace the single-card-link structure: cards are no longer wrapped in an anchor tag when multi-select is active
3. Add Action Toolbar section between search bar and session list:
   - Shows "X selected" text when items are selected
   - Shows "Delete Selected" button (disabled until selection) — triggers bulk delete
4. Keep existing routerLink on the card content area for viewing details

### Modified Service: `ResearchHistoryService`

**New methods:**
- `deleteSingleSession(sessionId: UUID): Observable<void>` — calls DELETE `/api/research/history/{sessionId}`
- `deleteBulkSessions(sessionIds: UUID[]): Observable<void>` — calls POST `/api/research/history/bulk-delete` with request body

## Testing Strategy
- Backend unit tests for controller endpoints (mocked service layer)
- Backend integration tests for bulk delete rollback semantics
- Frontend unit test for selection state management (Set operations, toggle logic)
- Manual E2E testing of multi-select UI flow and deletion confirmation

## Security Considerations
No authentication/authorization changes needed — the existing research system doesn't have user-level access control. Any authenticated user can delete any session. No additional validation of request body is required since UUIDs are validated by the repository.

## Performance Considerations
Bulk delete uses `deleteAllInBatch()` which performs a single batched DELETE operation per session (not individual deletes). For large batches (>100), consider pagination on the client side, but typical history page sizes are 20 items so this is acceptable.
