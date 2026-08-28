# Tasks: Automated Cleanup of Abandoned PROCESSING Sessions

## Task 1: Add stale-session query method to ResearchSessionRepository

**Description**: Add a Spring Data JPA derived query method that finds all sessions matching `status = PROCESSING AND createdAt < cutoff`. This is the data access layer for the cleanup service.

**File**: `research-agent-backend/src/main/java/com/researchagent/repository/ResearchSessionRepository.java`

**RED (Test First)**: Write a unit test using Mockito that verifies the repository method signature and parameter types compile correctly against the interface contract. No real DB needed — just verify the method exists with correct return type (`List<ResearchSession>`) and parameters (`ResearchStatus`, `LocalDateTime`).

```java
// In a new test file: AbandonedSessionCleanupServiceTest.java
@Test
void cleanupAbandonedSessions_marksStaleProcessingAsCancelled() {
    // Given: 3 sessions, 2 are PROCESSING+old, 1 is PROCESSING+recent
    // When: service runs cleanup
    // Then: only the 2 stale ones get status = CANCELLED
}
```

**GREEN (Minimal Implementation)**: Add derived query method to repository:
```java
List<ResearchSession> findAllByStatusAndCreatedAtBefore(ResearchStatus status, LocalDateTime cutoff);
```

Spring Data JPA auto-generates the implementation from the method name. No JPQL needed.

---

## Task 2: Create AbandonedSessionCleanupService with @Scheduled + unit test

**Description**: Implement the scheduled cleanup service. Test-first using Mockito mocks — no database required for this unit test. The test verifies the business logic (which sessions get marked CANCELLED) without hitting PostgreSQL.

**File**: `research-agent-backend/src/main/java/com/researchagent/service/AbandonedSessionCleanupService.java`
**Test File**: `research-agent-backend/src/test/java/com/researchagent/service/AbandonedSessionCleanupServiceTest.java`

**RED (Test First)**: Write a unit test with mocked repository that asserts:
1. Repository query is called exactly once per cleanup invocation
2. Sessions older than 1 hour have status updated to CANCELLED
3. Recent PROCESSING sessions (< 1 hour old) are NOT modified
4. Exception on one session does not prevent others from being processed

**GREEN (Implementation)**: Service with `@Component` + `@Scheduled(fixedRate = ${app.cleanup.interval})`:

```java
@Component
public class AbandonedSessionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AbandonedSessionCleanupService.class);

    @Value("${app.cleanup.stale-after:PT1H}")
    private Duration staleAfter;

    public void cleanupAbandonedSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minus(staleAfter);
        List<ResearchSession> abandoned = sessionRepo.findAllByStatusAndCreatedAtBefore(
            ResearchStatus.PROCESSING, cutoff);

        int cleanedCount = 0;
        for (ResearchSession session : abandoned) {
            try {
                session.setStatus(ResearchStatus.CANCELLED);
                sessionRepo.save(session);  // or use a transactional update method
                cleanedCount++;
                log.debug("Marked abandoned session as CANCELLED: {}", session.getId());
            } catch (Exception e) {
                log.error("Failed to clean up session {}: {}", session.getId(), e.getMessage());
            }
        }

        log.info("Abandoned session cleanup complete. Found {} stale sessions, marked {} as CANCELLED.",
                 abandoned.size(), cleanedCount);
    }
}
```

**Note on transaction**: Spring Data JPA's `save()` auto-begins a write transaction when called outside an existing one. For batch updates within the scheduler, wrapping in `@Transactional` is fine but not strictly necessary since each session update is independent.

---

## Task 3: Add scheduler configuration to application.yml

**Description**: Document and add the cleanup interval/stale-after properties to `application.yml`. Since `@Scheduled` works without explicit `@EnableScheduling` in Spring Boot (auto-configured), no additional config class needed. Just property documentation.

**File**: `research-agent-backend/src/main/resources/application.yml`

Add under existing `app:` section or create one:
```yaml
app:
  cleanup:
    stale-after: PT1H   # PROCESSING sessions older than this are considered abandoned
    interval: PT30M     # Scheduler runs every 30 minutes (fixed rate)
```

No code changes — just properties for `@Value` injection in the service.

---

## Task 4: Verify build passes with new test + service

**Description**: Run `mvn clean test` to verify:
1. New unit test compiles and passes
2. No regressions in existing tests (the repo query method is additive, shouldn't break anything)
3. Build succeeds cleanly

---

## Task 5: Manual verification — confirm scheduler runs on startup

**Description**: After build passes, start the backend (`mvn spring-boot:run`) and verify:
1. Application starts without errors
2. Scheduler bean is initialized (look for `AbandonedSessionCleanupService` in logs or via actuator)
3. First cleanup run executes within 30 minutes of startup
4. Stale PROCESSING sessions are marked CANCELLED
