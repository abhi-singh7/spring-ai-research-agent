# Proposal: Session Ownership

## Why (Motivation)

1. **Every authenticated user sees every session.** `GET /api/research/history`, `GET /{sessionId}`,
   `GET /{sessionId}/report`, `POST /{sessionId}/followup`, `DELETE /{sessionId}` and
   `POST /{sessionId}/resume` never consult the authenticated principal — any valid JWT reads,
   mutates and deletes another user's research. The JWT machinery (`JwtAuthFilter` →
   `CurrentUser(username, role)` in the `SecurityContext`) exists but is only used as an
   allow/deny gate, never as an ownership check.
2. **Cross-user mutation is worse than cross-user reading**: `resume` re-runs another user's
   pipeline, `delete` destroys their history, `followup` appends to their thread.
3. The UI already assumes per-user history (the history page is "my research"), so the current
   behaviour is a bug against the product's own contract.

## What Changes

### Added
- **`ResearchSession.owner`** — new `@Indexed` field on the session entity: the JWT subject
  (`CurrentUser.username`, stored lowercase) that created the session. `null` means "unowned"
  (documents written before this change).
- **`ResearchSessionRepositoryCustom`** — `countOwnedBy(String owner)` (diagnostics/limits hook).
- **`ResearchSessionRepository`** — owner-scoped query fragments:
  `findOwnedByOrderByCreatedAtDesc(owner, page)` and
  `findByTopicContainingIgnoreCaseAndOwner(query, owner, page)`.
- **`ResearchController.currentUsername()`** — reads the `CurrentUser` principal out of the
  `SecurityContext` (same pattern as `AuthController#me`); `null` when anonymous.

### Modified
- **`ResearchOrchestratorService.createAndStart(request, owner)`** — records the owner on the new
  document (trimmed + lowercased, matching how `JwtService` stores `sub`).
- **`ResearchOrchestratorService.getHistoricalSessions / searchByTopic`** — take the owner and
  return only that owner's rows (plus legacy unowned rows, see design).
- **`ResearchController`** — every `/{sessionId}…` route resolves the owner first and answers
  **404** (not 403) when the session belongs to another user: `GET /{id}`, `GET /{id}/status`,
  `GET /{id}/report`, `GET /{id}/followups`, `POST /{id}/followup`, `POST /{id}/resume`,
  `DELETE /{id}`, bulk delete, and `DELETE /{id}` cancel path.
- **`ResearchController` history/search** — pass the current username into the orchestrator query.

### Removed
- Nothing. Behaviour is unchanged for a single-user deployment (one user, one owner).

## Impact
- **API compatibility**: response shapes unchanged; only the *set* of visible sessions shrinks.
  Legacy sessions (`owner == null`) stay visible to everyone so an existing deployed database
  does not silently lose history after the upgrade.
- **Storage**: one new field per session document; no migration (missing field ⇒ `null`).
  The `@Indexed` annotation is documentation only — `auto-index-creation` is false in this project,
  and session counts are small, so owner filtering runs as a linear scan (documented in design).
- **Failure modes**: an anonymous request can never reach an ownership-gated route (the security
  chain 401s first); `currentUsername()` returning `null` therefore only matters in tests.
- **Still open**: the JWT for SSE still rides the `?token=` query parameter (an `EventSource`
  cannot send headers), so it is access-log-visible. Ownership now bounds the damage to the token's
  own subject; moving SSE to a `POST`ed fetch stream is a separate change.
