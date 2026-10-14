# Requirements: Session Ownership (delta)

## ADDED Requirements

### Requirement: A research session records the authenticated user who created it
Every session created through `POST /api/research` SHALL store an `owner` field equal to the
authenticated principal's username (trimmed, lowercased). A session created without an
authenticated principal SHALL leave `owner` unset.

#### Scenario: Session is owned by the caller
- **WHEN** an authenticated user `alice` starts research
- **AND THEN** the created session document carries `owner: "alice"`

### Requirement: History lists and topic search are scoped to the caller's owner
`GET /api/research/history` and `GET /api/research/history/search` SHALL return only sessions whose
`owner` equals the caller's username, or whose `owner` is absent (legacy documents). Pagination
metadata SHALL describe the filtered set, not the unfiltered collection.

#### Scenario: Another user's session is invisible
- **WHEN** `alice` and `bob` each have sessions and `alice` requests the history page
- **AND THEN** only `alice`'s sessions (and legacy unowned ones) appear, and `totalElements` counts only those

### Requirement: Per-session endpoints reject foreign owners with 404
`GET /{id}`, `GET /{id}/status`, `GET /{id}/report`, `GET /{id}/followups`, `POST /{id}/followup`,
`POST /{id}/resume`, `DELETE /{id}` and bulk delete SHALL answer 404 when the session exists but its
`owner` is a different user. A session with no `owner` SHALL remain reachable.

#### Scenario: Foreign session id is not disclosed
- **WHEN** `bob` requests `GET /api/research/{aliceSessionId}`
- **AND THEN** the response is 404 with no body, identical to a nonexistent id

#### Scenario: Foreign session cannot be resumed
- **WHEN** `bob` posts to `POST /api/research/{aliceFailedSessionId}/resume`
- **AND THEN** the response is 404 and the session document is unchanged

### Requirement: The SSE stream endpoint refuses streams for foreign sessions
`GET /api/research/stream/{id}?token=…` SHALL validate the token and then compare its subject with
the session's owner, answering 404 before any `SseEmitter` is registered when they differ.

#### Scenario: Foreign stream connection is refused
- **WHEN** `bob` opens the stream endpoint with a valid token for `alice`'s session
- **AND THEN** the response is 404 JSON and no SSE connection is created
