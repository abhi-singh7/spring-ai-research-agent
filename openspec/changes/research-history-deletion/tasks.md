# Implementation Tasks: Research History Deletion

## Backend Tasks (TDD — Write Tests First)

### Task 1.0: Verify test dependencies
- [ ] Confirm `spring-boot-starter-test` is present in pom.xml (required for unit/integration tests)

### Task 1.1: Unit test for single delete endpoint — successful deletion
- **RED**: Test that DELETE `/api/research/history/{sessionId}` returns 404 when session doesn't exist
- **GREEN**: Implement the endpoint and verify test passes

### Task 1.2: Unit test for single delete endpoint — PROCESSING sessions blocked
- **RED**: Test that DELETE `/api/research/history/{sessionId}` returns 409 Conflict when session status is PROCESSING
- **GREEN**: Add status check in controller/service, verify test passes

### Task 1.3: Unit test for single delete endpoint — successful deletion of completed session
- **RED**: Test that DELETE `/api/research/history/{sessionId}` returns 204 for a COMPLETED/F/CANCELLED session
- **GREEN**: Add service method to call repository.deleteById(), verify test passes

### Task 1.4: Unit test for bulk delete endpoint — successful deletion of multiple sessions
- **RED**: Test that POST `/api/research/history/bulk-delete` returns 204 when deleting valid COMPLETED/F/CANCELLED sessions
- **GREEN**: Implement bulk delete service method with `deleteAllInBatch()`, verify test passes

### Task 1.5: Unit test for bulk delete endpoint — rollback on invalid session ID in batch
- **RED**: Test that POST `/api/research/history/bulk-delete` fails when batch contains an ID that doesn't exist or is PROCESSING
- **GREEN**: Add validation to reject invalid IDs before calling deleteAllInBatch(), verify rollback semantics, test passes

### Task 1.6: Integration test for cascading step deletion
- **RED**: Test that deleting a session also deletes its associated ResearchStep records (verify steps count goes to 0)
- **GREEN**: Verify `orphanRemoval = true` handles this automatically, no code change needed

## Frontend Tasks (No TDD — Direct Implementation)

### Task 2.1: Add new Material imports to ResearchHistoryComponent
- [ ] Import `MatCheckboxModule` for card checkboxes
- [ ] Import `MatDialogModule` for confirmation dialogs
- [ ] Import `MatSnackBarModule` for success/error notifications

### Task 2.2: Create selection state management in ResearchHistoryService
- [ ] Add signal to track selected session IDs (Set<UUID>)
- [ ] Implement `toggleSelect(sessionId)` — add/remove from set
- [ ] Implement `selectCurrentPage()` — add all sessions on current page
- [ ] Implement `deselectAll()` — clear the set

### Task 2.3: Add single delete method to ResearchHistoryService
- [ ] Create `deleteSingleSession(sessionId): Observable<void>` calling DELETE `/api/research/history/{sessionId}`
- [ ] Return observable that completes on success, errors on failure

### Task 2.4: Add bulk delete method to ResearchHistoryService
- [ ] Create `deleteBulkSessions(sessionIds: UUID[]): Observable<void>` calling POST `/api/research/history/bulk-delete`
- [ ] Map session IDs to JSON request body
- [ ] Return observable that completes on success, errors on failure

### Task 2.5: Add selection state tracking to ResearchHistoryComponent
- [ ] Add `selection = new Set<UUID>()` local state variable
- [ ] Add computed signals for `selectedCount()` and `hasSelection()` based on the set
- [ ] Wire up checkbox onChange events to toggleSelect logic

### Task 2.6: Add card checkboxes to history items (Approach A UI)
- [ ] For each history item, add `<mat-checkbox>` before the routerLink anchor tag — only shown when not in selection mode
- [ ] When multi-select is active, remove the anchor tag wrapper and make cards clickable for details
- [ ] Style checkbox consistently with existing Material styling

### Task 2.7: Add Action Toolbar section to history page (Approach A)
- [ ] Between search bar and session list, add a toolbar div that shows when items are selected
- [ ] Show "X selected" text and "Delete Selected" button when selections exist
- [ ] Disable the Delete button until at least one item is selected
- [ ] Add styling consistent with existing card/page layout

### Task 2.8: Implement single delete flow in ResearchHistoryComponent
- [ ] On individual card click (when not multi-select mode): open MatDialog confirmation dialog
- [ ] Dialog shows deletion warning text and session topic for context
- [ ] On confirm: call `deleteSingleSession()`, show snackbar success, redirect to history list page if viewing detail
- [ ] On cancel/error: close dialog, show error snackbar

### Task 2.9: Implement bulk delete flow in ResearchHistoryComponent
- [ ] On toolbar Delete button click: open MatDialog confirmation dialog
- [ ] Dialog shows deletion warning text with count of selected items and list of topics being deleted
- [ ] On confirm: call `deleteBulkSessions()`, show snackbar success with count, refresh history list
- [ ] On cancel/error: close dialog, show error snackbar

### Task 2.10: Add styling for multi-select UI elements (Approach A)
- [ ] Style toolbar section — background color matching existing Material theme
- [ ] Style delete button in toolbar with appropriate icon and disabled state
- [ ] Style checkbox position on cards — left-aligned, proper spacing from card content
- [ ] Ensure dark mode support for all new UI elements

## Integration & Verification
### Task 3.0: Run full build — fix any compilation errors automatically
- [ ] `mvn clean test` passes cleanly
- [ ] Verify no existing tests were affected
