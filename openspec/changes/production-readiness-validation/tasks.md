# Production Validation Tasks

## Discover & Inventory
- [x] List all controllers, services, repositories, configs, tools
- [x] Map API endpoints (REST + SSE)
- [x] Identify entity relationships and cascade behavior

## Code Review — Controllers
- [x] `ResearchController` — 9 endpoints reviewed
- [ ] `ResearchStreamController` — review SSE endpoint

## Code Review — Services
- [x] `ResearchOrchestratorService` — core orchestration logic reviewed
- [x] `ResearchStreamingService` — SSE emitter management reviewed
- [x] `FollowUpService` — follow-up handler reviewed
- [x] `AbandonedSessionCleanupService` — scheduler reviewed

## Code Review — Security
- [ ] Authentication: verify none exists, flag as Critical
- [ ] Authorization: verify endpoint access control is absent
- [ ] CORS: review allowed origins for production readiness
- [ ] Input validation: review DTO constraints
- [ ] SQL injection: review JPQL queries
- [ ] XSS: review report content rendering
- [ ] Secret management: check hardcoded credentials

## Code Review — Database / Persistence
- [ ] Schema validation (ddl-auto: validate)
- [ ] N+1 query risk from `findByIdWithSteps` JPQL
- [ ] Transaction boundaries (@Transactional on read-only reads)
- [ ] Cascade delete correctness

## Code Review — Configuration
- [ ] Hardcoded API key in application.yml default value
- [ ] No actuator/health endpoints configured
- [ ] Thread pool sizing (core=5, max=20, queue=100)
- [ ] SSE timeout configuration
- [ ] Cleanup scheduler interval

## Code Review — Async & Concurrency
- [ ] Race condition: session created in `createAndStart` but marked PROCESSING after async starts
- [ ] SseEmitter cleanup on connection loss
- [ ] AtomicReference usage for report buffer

## Generate Tests — Unit Tests (Service Layer)
- [x] ResearchOrchestratorServiceTest — existing 14 tests reviewed
- [x] ResearchStreamingServiceTest — existing 9 tests reviewed
- [ ] AbandonedSessionCleanupServiceTest — missing test file
- [ ] FollowUpServiceTest — missing test file
- [ ] McpClientErrorHandlerTest — existing test needs review

## Generate Tests — Controller Tests
- [x] ResearchControllerStartResearchTest — existing 5 tests reviewed
- [x] ResearchControllerGetHistoryAndSearchTest — existing 6 tests reviewed
- [x] ResearchControllerDeleteHistorySessionSuccessTest — exists
- [x] ResearchControllerCancelResearchTest — exists
- [ ] Missing: delete history not found (404) test
- [ ] Missing: bulk delete rollback test
- [ ] Missing: submit follow-up validation test

## Generate Tests — Edge Cases & Regression
- [ ] Empty/null payload handling across all endpoints
- [ ] Concurrent session creation race condition
- [ ] SSE emitter timeout and cleanup verification
- [ ] LLM prompt injection in user topic (sanitization)
- [ ] Report fallback generation edge cases

## Execute Tests
- [ ] Run `mvn test` — collect all results
- [ ] Categorize failures: Critical/High/Medium/Low

## Fix Issues (TDD)
- [ ] For each failing test, verify red → green cycle
- [ ] No fix without a failing test first

## Regression Testing
- [ ] Re-run full suite after fixes
- [ ] Verify no new regressions

## Dependency Audit
- [ ] Review pom.xml for known CVEs (jsoup 1.20.1, h2 2.4.240)
- [ ] Verify Spring Boot / Spring AI compatibility matrix
- [ ] Deprecated API usage check

## Performance Review
- [ ] N+1 query analysis on `findByIdWithSteps` LEFT JOIN FETCH
- [ ] Connection pool sizing for production load
- [ ] Thread safety in ConcurrentHashMap (sessions map)
- [ ] SSE emitter memory under high concurrency

## Security Review (OWASP Top 10)
- [ ] A01 — Broken Access Control: No auth exists (CRITICAL)
- [ ] A02 — Cryptographic Failures: API key in config default
- [ ] A03 — Injection: LLM prompt injection via user topic
- [ ] A07 — Identification & Authentication: None implemented
- [ ] A09 — Security Logging & Monitoring: No audit logging

## Configuration Review
- [ ] Production profile missing (no application-prod.yml)
- [ ] CORS allows only localhost:4200 (dev-only)
- [ ] No rate limiting configured

## Frontend Verification
- [ ] Angular standalone components compile
- [ ] API contract matches backend response shapes
- [ ] SSE client reconnection logic reviewed

## Production Report
- [ ] Compile final report with score and recommendation
