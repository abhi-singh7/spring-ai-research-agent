# Design: Automated Cleanup of Abandoned PROCESSING Sessions

## Root Cause Analysis

Sessions stuck in `PROCESSING` state have no automated mechanism to transition to a terminal state (CANCELLED/FAILED/COMPLETED). The existing cancel flow (`cancelResearch()`) only handles explicitly user-initiated cancellation and sets status to FAILED via `session.fail()` — it does not handle abandoned/interrupted sessions. The delete endpoint blocks any PROCESSING session as a safety guard, leaving users unable to remove stale entries from history.

## Technical Approach

### 1. New Service: `AbandonedSessionCleanupService`

A Spring-managed singleton bean annotated with `@Component` (no need for `@Service` since it's not part of business logic). Uses `@Scheduled(fixedRate = ...)` to run the cleanup at a configurable interval.

**Key responsibilities:**
- Query repository for PROCESSING sessions older than stale threshold
- Iterate matching sessions and update status to CANCELLED via entity method (not direct field mutation — maintains consistency with other state transitions)
- Log summary of operation at INFO level; individual session IDs at DEBUG level
- Handle exceptions gracefully (log error but do not crash scheduler)

### 2. Repository Query Addition

Add a Spring Data JPA derived query or `@Query` annotation to `ResearchSessionRepository`:

```java
List<ResearchSession> findAllByStatusAndCreatedAtBefore(ResearchStatus status, LocalDateTime cutoff);
```

This uses Spring Data's method-name-based query derivation with the `<property> Before <cutoff>` convention. The parameter is a `LocalDateTime` representing "1 hour ago" computed in the service layer using `LocalDateTime.now().minusHours(staleAfter.toHours())`.

### 3. Scheduler Configuration via application.yml

```yaml
app:
  cleanup:
    stale-after: PT1H        # Duration after which PROCESSING is considered abandoned
    interval: PT30M          # How often the scheduler runs (fixed rate, milliseconds or ISO-8601 duration)
```

These values are injected into the service via `@Value` annotations with sensible defaults if not configured.

### 4. State Transition — Mark as CANCELLED (not FAILED/DELETED)

Sessions marked by this cleanup receive status `CANCELLED`, NOT:
- **FAILED**: reserved for actual research failures (LLM errors, search failures). Using FAILED would imply something went wrong during the run.
- **Deleted**: permanent removal loses data; users may want to see what was interrupted.

`CANCELLED` clearly communicates "this session is no longer active and was abandoned" — visible in history with the existing CANCELLED status chip styling already defined in the frontend.

### 5. Thread Safety & Concurrency Considerations

- The scheduler runs on a single thread by default (fixed rate, same instance). No concurrent execution risk within a single JVM instance.
- Each session update is wrapped in its own transaction implicitly via `@Transactional` propagation from the service method.
- If multiple instances run the cleanup simultaneously (e.g., Kubernetes pod restarts), the stale threshold provides natural deduplication — only sessions older than 1 hour are touched, and once marked CANCELLED, they won't match future runs.

## Data Model Changes

**None.** The `ResearchSession` entity already has:
- `status` field with `CANCELLED` value in the enum ✓
- `createdAt` timestamp for age calculation ✓

No DDL migration required. PostgreSQL schema is unchanged.

## Testing Approach

Unit test using Mockito to verify:
1. Query returns correct sessions (PROCESSING + older than cutoff)
2. Matching sessions have status updated to CANCELLED
3. Sessions within the stale threshold are NOT modified
4. Exceptions during individual session updates do not stop the rest of the batch

## Security & Performance Considerations

- **Security**: No new API endpoints, no user-facing surface. Scheduler runs internally with no authentication/authorization needed.
- **Performance**: Query is O(N) where N = number of PROCESSING sessions (expected to be small in practice). Index on `(status, created_at)` would help if PROCESSING count grows large — but unlikely given the 1-hour stale threshold means most PROCESSING sessions resolve quickly or get cleaned up automatically.
