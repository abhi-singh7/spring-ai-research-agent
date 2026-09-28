# Tasks: JWT Authentication (Phase 1 — Backend Only)

## Implementation
- [ ] `pom.xml`: add `spring-boot-starter-security`, `com.nimbusds:nimbus-jose-jwt` (compile), `spring-security-test` (test scope)
- [ ] `application.yml`: add `app.security.jwt.*` (`secret` with `${JWT_SECRET:}` override + marked dev default, `expiration-seconds: 28800`, `issuer: research-agent`)
- [ ] Add `model.entity.User` (`@Document("user")`: id UUID, username unique+lowercase, passwordHash, role default `USER`, createdAt)
- [ ] Add `service.UserService`: `register` (validate username/password rules, BCrypt hash, duplicate → 409), `authenticate` (BCrypt verify), `findByUsername`
- [ ] Add `security.JwtService`: HS256 issue (`sub`, `role`, `iat`, `exp`, `iss`) and validate via nimbus-jose-jwt; startup check that secret ≥ 32 bytes
- [ ] Add `security.SecurityConfig`: stateless session policy, CSRF disabled, permit `/api/auth/**` + `/api/research/stream/**`, all else authenticated; JSON 401 entry point + 403 denied handler; `BCryptPasswordEncoder` bean
- [ ] Add `security.JwtAuthFilter` (`OncePerRequestFilter`): parse `Authorization: Bearer <jwt>` → `CurrentUser(username, role)` principal + authority in `SecurityContext`; invalid/absent → anonymous
- [ ] Add `controller.AuthController`: `POST /api/auth/register` (201 + auto-login token), `POST /api/auth/login` (200 token | 401), `GET /api/auth/me` (Bearer)
- [ ] Modify `ResearchStreamController.streamProgress`: optional `@RequestParam("token")`, validate via `JwtService`, return `ResponseEntity<SseEmitter>` (401 JSON before stream opens on missing/invalid; identical SSE behavior when valid)

## Tests
- [ ] `JwtServiceTest` — issue/validate round-trip; expired token rejected; tampered signature rejected; short secret fails at construction
- [ ] `UserServiceTest` — register success, duplicate username → 409, weak password → 400, login success/wrong-password
- [ ] `AuthControllerTest` (`@WebMvcTest`) — register/login/me happy paths + error shapes (real minted tokens via a real `JwtService` instance)
- [ ] Security integration test — unauthenticated `/api/research/**` → 401 JSON; valid Bearer → 200; stream without token → 401 before SSE opens; stream with valid token → SSE events flow
- [ ] Annotate the ~11 existing `@WebMvcTest` controller tests (`ResearchController*Test`) with `@WithMockUser`

## Verification
- [ ] `mvn clean verify`: all tests green, including the two real-MongoDB integration tests (service-level, unaffected by the filter chain)
- [ ] Manual API pass against a running instance: register → login → create session with Bearer → poll status/history/report → stream with `?token=` → delete; then negative cases: no token → 401, garbage token → 401, stream without token → 401
- [ ] Confirm existing service-level tests (`OrchestratorPersistenceIntegrationTest`, `AbandonedSessionCleanupServiceTest`) still pass unchanged

## Deferred / Follow-ups (explicitly NOT part of this change)
- **Phase 2 — frontend:** login screen, auth service (signals), `HttpInterceptor` for the Bearer header, `?token=` on the EventSource URL, route guards, token storage decision
- **Phase 3 — data isolation:** `ownerId` on `research_session`, scoped queries, one-shot migration of existing documents
- **Phase 5 — RBAC:** enforce `role` (e.g. ADMIN-only bulk delete / global history) using the claim already carried in Phase 1
- Token revocation / refresh-token flow if expiry-driven logout ever feels too coarse
- Re-enable CSRF protection if auth ever moves to httpOnly cookies
- Permit `/actuator/health` once the observability phase adds actuator
