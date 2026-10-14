# Tasks — session-ownership

## 1. Model

- [x] 1.1 Add `owner` to `ResearchSession` with `@Indexed` and a doc comment stating the
      `null` = legacy semantics and that auto-index creation is off in this project.
- [x] 1.2 Add owner-scoped queries to `ResearchSessionRepositoryCustom`
      (`findOwnedByOrderByCreatedAtDesc`, `findByTopicContainingIgnoreCaseAndOwner`, `countOwnedBy`).

## 2. Repository implementation

- [x] 2.1 Implement the owner-scoped queries with `Criteria.where("owner").in(owner, null)`
      (one `$in` covers the missing-field case — no `$exists` dance, no migration).
- [x] 2.2 Push the page window into the Mongo query (`skip`/`limit`) instead of paging in memory.
- [x] 2.3 Keep the topic regex case-insensitive and literal (`(?i)\Q…\E`, neutralising `\E`).

## 3. Orchestrator

- [x] 3.1 `createAndStart(request, owner)` records the authenticated username, normalised.
- [x] 3.2 `getHistoricalSessions(owner, page)` and `searchByTopic(query, owner, page)` delegate to
      the owner-scoped queries.
- [x] 3.3 Persist `subTopicCount` / `maxRounds` on the session so resume replays the original depth.

## 4. Controller gate

- [x] 4.1 `currentUsername()` reads the principal from the `SecurityContext` (the `JwtAuthFilter`
      already populated it); handles the `CurrentUser` record and the plain-String principal that
      spring-security-test installs.
- [x] 4.2 `ownResearch(sessionId)` returns `null` for foreign owners → every per-session endpoint
      answers 404 (never 403).
- [x] 4.3 Bulk delete drops foreign ids before calling the orchestrator.
- [x] 4.4 `ResearchStreamController` rejects foreign sessions before opening the `SseEmitter`.

## 5. Tests

- [x] 5.1 `ResearchControllerForeignSessionIsInvisibleTest` — 404 on status / historical detail /
      report / resume / delete for a session owned by another user.
- [x] 5.2 `SecurityIntegrationTest.streamWithValidTokenForForeignSession_returns404BeforeSseOpens`
      (real filter chain, real Mongo) plus the positive case with an owned session.
- [x] 5.3 Existing MVC tests keep passing (owner is `null` in token-less MVC requests, so
      unowned documents stay reachable — the legacy rule exercised, not skipped).

## 6. Still open (deliberately not done here)

- [ ] 6.1 A one-shot script to stamp legacy sessions with an owner (needs an out-of-band decision
      about who owns them).
- [ ] 6.2 An `owner` facet on history so a superuser role can see across users.
- [ ] 6.3 Moving the SSE token out of the query string (separate change; see
      `openspec/changes/move-sse-token-out-of-query/`).
