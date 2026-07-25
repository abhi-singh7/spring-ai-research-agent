# Proposal: Research History Deletion

## Why (Motivation)
Users currently have no way to remove research history from the system — whether individual sessions or multiple at once. This leads to clutter over time and prevents users from managing their data. Without deletion capability, old/irrelevant research results remain permanently visible in the list view.

## What Changes

### Added
- Single-session deletion endpoint (backend + frontend)
- Bulk/multi-select session deletion endpoint (backend + frontend)
- Multi-select UI mode on the History page with card checkboxes and toolbar action bar
- MatDialog confirmation dialogs for both single and bulk delete operations
- Snackbar/toast notifications after successful deletions showing count of deleted items
- Error snackbar when deletion fails, with rollback semantics

### Modified  
- `ResearchHistoryComponent` — add multi-select mode, checkbox UI, selection toolbar, deletion actions
- `ResearchHistoryService` — add single-delete and bulk-delete methods
- `ResearchController` — add new DELETE endpoints for history sessions
- `ResearchOrchestratorService` — add delete service methods

### Removed (if any)
None. No features being deprecated.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `src/main/java/com/researchagent/controller/ResearchController.java` | Modified | Medium — new DELETE endpoints |
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | Modified | Low — new delete methods, no existing logic changes |
| `src/main/java/com/researchagent/repository/ResearchSessionRepository.java` | Verified (no change needed) | Low — JPA `deleteById()` and `deleteAllInBatch()` are already available |
| `src/app/features/research-history/research-history.component.ts` | Modified | Medium — new UI components, multi-select state management |
| `src/app/core/services/research-history.service.ts` | Modified | Low — new delete API methods |

## Assumptions Made
1. **No soft-delete mechanism** — sessions are permanently removed from the database (hard delete). This is confirmed by user decision.
2. **Cascading deletes for ResearchStep** already handled automatically via `orphanRemoval = true` on the `steps` relationship in `ResearchSession` entity — no additional code needed.
3. **Multi-select only applies to current page**, not across all pages of history. This keeps it simple and avoids cross-page selection complexity.
4. **Error rollback semantics** — if any session in a bulk delete fails, the entire operation is rolled back (none are deleted). This means using `deleteAllInBatch()` which either succeeds completely or fails with an exception.
5. **Confirmation dialogs always require explicit user confirmation** before deletion proceeds — no silent deletions.

## Open Questions Resolved
- Q: Single delete redirect behavior? A: After single session deletion, redirect to the history list page and show empty state if it was the last item on that page.
- Q: Multi-select scope? A: Current page only, not across all pages.
- Q: Bulk delete success feedback? A: Snackbar notification with count of deleted items, refresh history list.
- Q: New API for delete vs repurpose existing endpoint? A: Add new DELETE endpoints specifically for historical sessions (`/api/research/history/{sessionId}` and `/api/research/history/bulk-delete`). Existing `DELETE /api/research/{sessionId}` remains for canceling running sessions.
- Q: Bulk delete error rollback? A: Rollback the entire batch if any item fails — use JPA's `deleteAllInBatch()` which is atomic.
- Q: Confirmation dialog approach? A: Use Angular Material's `MatDialog` with clear deletion warning text and confirmation/Cancel buttons.
