# Proposal: JWT Authentication for the Research API (Phase 1 — Backend Only)

## Why (Motivation)
The backend currently has **no authentication at all**: every endpoint under `/api/research/**` — including session deletion, bulk delete, follow-up submission and the live SSE stream — is open to anyone who can reach port 8080. This is the largest production-readiness gap in the project and it blocks multi-user use: today every client on the machine sees and can mutate every other client's sessions.

Phase 1 adds **stateless JWT authentication on the backend only**:

- `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`
- Bearer-token protection for all existing research endpoints
- Query-parameter token auth for the SSE stream endpoint (the browser's `EventSource` API cannot set custom headers — see design.md Decision 5)

Passwords are stored only as BCrypt hashes. The JWT is signed HS256 with a configured secret and bounded expiry.

**Scope boundary:** frontend login UI, Angular interceptors and route guards are Phase 2; per-user data isolation (`ownerId` on sessions) is Phase 3; RBAC (ADMIN role enforcement) is Phase 5. This change ships none of those.

**Known transitional state (accepted):** once this change deploys, the current auth-unaware Angular UI will receive `401` on every API call until Phase 2 lands. The API remains fully usable via curl/Postman using a token from `/api/auth/login`. Phases 1 and 2 are intended to be merged back-to-back; the CI/CD pipeline deploys each phase automatically, so the broken-UI window is at most one PR cycle.

## What Changes

### Added
- **`model.entity.User`** — `@Document("user")`: `id` (UUID), `username` (unique index), `passwordHash`, `role` (default `USER`), `createdAt`. Collection auto-created by Spring Data Mongo annotations; no migration script needed.
- **`service.UserService`** — `register(username, password)` (validation + BCrypt hash + duplicate detection), `authenticate(username, password)` (BCrypt verify → `User` or 401), `findByUsername`.
- **`security.JwtService`** — issues/validates HS256 compact JWTs via `nimbus-jose-jwt`: claims `sub`=username, `role`, `iat`, `exp`, `iss`. Config under `app.security.jwt.*` (`secret`, `expiration-seconds`, `issuer`).
- **`security.SecurityConfig`** — `SecurityFilterChain`: stateless session policy, CSRF disabled, permit `/api/auth/**` and `/api/research/stream/**`, every other request requires authentication; JSON `401`/`403` entry-point/denied handlers.
- **`security.JwtAuthFilter`** (`OncePerRequestFilter`) — reads `Authorization: Bearer <jwt>`, validates via `JwtService`, populates the `SecurityContext` with a `CurrentUser(username, role)` principal and role authority; invalid/absent tokens leave the context anonymous (authorization then yields 401).
- **`controller.AuthController`** — the three auth endpoints above; register returns `201` with `{token, username}` (auto-login), login returns `200 {token, username}`, `/me` returns `{username, role, createdAt}`.
- **Dependencies (`pom.xml`)** — `spring-boot-starter-security`, `com.nimbusds:nimbus-jose-jwt` (compile), `spring-security-test` (test).
- **Tests** — `JwtServiceTest` (round-trip, expiry, tampered signature), `UserServiceTest` (register/login/duplicate/weak-password), `AuthControllerTest` (`@WebMvcTest` + real minted tokens), a security integration test asserting: unauthenticated research call → 401 JSON, valid Bearer → 200, stream without token → 401, stream with valid token → SSE opens.

### Modified
- **`ResearchStreamController.streamProgress`** — gains optional `@RequestParam("token")`; validates via `JwtService`; return type becomes `ResponseEntity<SseEmitter>` so an invalid/missing token yields a `401` JSON body *before* any stream is opened. Valid token → identical behavior to today.
- **`src/main/resources/application.yml`** — new `app.security.jwt.*` block (secret with dev default + `${JWT_SECRET:}` env override, `expiration-seconds: 28800`, `issuer: research-agent`).
- **~11 existing `@WebMvcTest` controller tests** (`ResearchController*Test`) — annotated `@WithMockUser` so they pass through the new security chain unchanged.

### Removed
- Nothing. No endpoint paths, payloads or SSE event names change; no `research_session` schema change; no data migration in this phase.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `controller/ResearchStreamController.java` | Modified (token param + ResponseEntity) | Medium — SSE path is exercised by the live UI until Phase 2 |
| `security/*` (4 new classes), `controller/AuthController.java`, `service/UserService.java`, `model/entity/User.java` | Added | Low–Medium — additive; filter chain ordering is the main risk |
| `pom.xml` | Modified (3 deps) | Low — security starter changes default auto-config for every web test |
| `src/test/.../ResearchController*Test.java` (~11 files) | Modified (`@WithMockUser`) | Low — one-line annotations, same scenarios |
| `application.yml` | Modified (new block) | Low — additive keys only |

Behavioral guarantees preserved: research pipeline semantics (breakdown → rounds → synthesis), SSE event protocol (PROGRESS/CONTENT/REPORT_CHUNK/REPORT_DONE/STEP_COMPLETE/ERROR), Mongo schema for `research_session`, abandoned-session cleanup scheduler, search backend routing, cancellation and partial-failure tolerance — all unchanged. Existing service-level tests (`OrchestratorPersistenceIntegrationTest`, `AbandonedSessionCleanupServiceTest`, orchestrator/tool tests) do not go through HTTP and are unaffected by the filter chain.
