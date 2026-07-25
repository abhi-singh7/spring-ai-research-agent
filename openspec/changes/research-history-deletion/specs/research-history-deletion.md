# Requirements: Research History Deletion

## ADDED Requirements — Single Session Deletion

### Requirement: Delete Individual Session
The system SHALL allow deletion of a single research session from history, permanently removing the session and its associated steps from the database.

#### Scenario: Successful deletion of completed session
- **WHEN** user clicks delete action on a completed/cancelled/failed session card in the History list page
- **THEN** a MatDialog confirmation dialog is displayed with warning text "This action will permanently delete this research session and all its associated steps. This cannot be undone."
- **AND IF** user confirms by clicking Delete Permanently button
- **THEN** the system sends DELETE request to `/api/research/history/{sessionId}` endpoint
- **AND THEN** the session and its cascaded steps are removed from the database, a success snackbar shows "1 session deleted", and the list is refreshed

#### Scenario: User cancels deletion confirmation
- **WHEN** user clicks delete action on a session card in the History list page
- **THEN** a MatDialog confirmation dialog is displayed with warning text
- **AND IF** user cancels by clicking Cancel button or closing the dialog
- **THEN** no deletion occurs and the UI returns to its original state

#### Scenario: Deletion fails due to network error
- **WHEN** user confirms deletion of a session
- **THEN** if the DELETE API call fails with an HTTP error (500, timeout, etc.)
- **AND THEN** the system shows an error snackbar "Failed to delete session" and no data is modified

#### Scenario: Session is still processing — should not be deletable via history endpoint
- **WHEN** a running/processing research session is deleted via the cancel endpoint (`DELETE /api/research/{sessionId}`)
- **THEN** it should NOT be accessible from the delete endpoint for historical sessions (this behavior already exists and is preserved)

### Requirement: Redirect After Single Deletion
The system SHALL redirect the user to the history list page after a successful single session deletion.

#### Scenario: Single deletion from detail view redirects to list
- **WHEN** user deletes a session while viewing its detail page (`/research/history/:sessionId`)
- **THEN** after successful deletion, the user is redirected to `/research/history` (the History list)

#### Scenario: Single deletion from list page refreshes the list
- **WHEN** user deletes a session while on the history list page
- **AND IF** it was the last item on that page
- **THEN** after successful deletion, an empty state message "No research history found." is displayed

## ADDED Requirements — Bulk Session Deletion

### Requirement: Multi-Select Mode with Toolbar Action (Approach A)
The system SHALL provide a multi-select mode on the History list page where users can select multiple sessions and delete them in bulk.

#### Scenario: Activate multi-select by selecting first item
- **WHEN** user selects one or more session cards via their checkboxes in the History list page
- **THEN** an action toolbar appears showing "X selected" text (where X is the count) and a "Delete Selected" button (enabled)
- **AND THEN** all selected items are highlighted visually

#### Scenario: Multi-select actions after selecting multiple items
- **WHEN** user has selected N sessions via checkboxes
- **THEN** the action toolbar shows "N selected" and an enabled Delete button
- **AND IF** user clicks Delete Selected button in the toolbar
- **THEN** a MatDialog confirmation dialog is displayed listing the topics of all selected sessions with warning text

#### Scenario: Multi-select deselect behavior
- **WHEN** user has some items selected on the current page
- **THEN** clicking an already-selected checkbox removes that item from selection
- **AND IF** this makes no items remaining selected on the current page
- **THEN** the action toolbar disappears (no more "X selected" shown)

#### Scenario: Bulk delete confirmation with topics listing
- **WHEN** user confirms bulk deletion via the MatDialog in multi-select mode
- **THEN** a list of all selected session topics is displayed in the dialog for review
- **AND THEN** user must click Delete Permanently to proceed or Cancel to abort

#### Scenario: Successful bulk delete refreshes history
- **WHEN** user confirms bulk deletion of N sessions
- **THEN** the system sends POST request to `/api/research/history/bulk-delete` with all selected session IDs in a JSON array
- **AND THEN** if all deletions succeed, a success snackbar shows "N sessions deleted" and the history list is refreshed

#### Scenario: Bulk delete fails — rollback semantics
- **WHEN** user confirms bulk deletion of N sessions from which one or more fail to delete (e.g., session doesn't exist, PROCESSING status)
- **THEN** if ANY single deletion in the batch fails
- **AND THEN** NO deletions occur for any item in the batch (all rollback — atomic operation)
- **AND THEN** an error snackbar shows "Failed to delete sessions" indicating how many failed

### Requirement: Single Delete Action Available Always
The system SHALL provide both single and bulk deletion options at all times, regardless of multi-select mode state.

#### Scenario: Single delete card action available on any history item
- **WHEN** viewing the History list page
- **THEN** each session card has a visible delete action (icon or button) that can be used to delete just that one session
- **AND IF** user clicks this single-card delete action
- **THEN** a MatDialog confirmation dialog is displayed for that specific session

## ADDED Requirements — User Experience

### Requirement: Deletion Confirmation Always Required
The system SHALL require explicit user confirmation via a confirmation dialog before any deletion (single or bulk) proceeds. No silent deletions allowed.

#### Scenario: Dialog always shows destructive warning
- **WHEN** any delete action is initiated (single or bulk)
- **THEN** the dialog displays clear text indicating the operation is permanent and irreversible — "This will permanently delete X session(s). This cannot be undone."
- **AND THEN** the confirm button uses a warn/error color to indicate destructive action

### Requirement: Success Feedback After Deletion
The system SHALL show a snackbar notification after successful deletion showing the count of items deleted.

#### Scenario: Single delete success feedback
- **WHEN** single session deletion succeeds
- **THEN** a snackbar appears with message "1 session deleted" (singular)

#### Scenario: Bulk delete success feedback
- **WHEN** bulk session deletion succeeds for N sessions
- **THEN** a snackbar appears with message "N sessions deleted" (plural, even if N=2)

### Requirement: Error Feedback on Deletion Failure
The system SHALL show a snackbar notification after failed deletion indicating the failure.

#### Scenario: Single delete failure feedback
- **WHEN** single session deletion fails for any reason
- **THEN** an error snackbar appears with message "Failed to delete session"

#### Scenario: Bulk delete failure feedback
- **WHEN** bulk session deletion fails (rollback) for any reason
- **AND IF** M of N sessions failed to delete
- **THEN** an error snackbar appears with message "Failed to delete X session(s)" where X is the count of failures
