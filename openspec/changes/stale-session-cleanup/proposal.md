# Proposal: Automated Cleanup of Abandoned PROCESSING Sessions

## Why (Problem Statement)

Research sessions that get interrupted during processing remain stuck in `PROCESSING` state indefinitely, cluttering the history page with "Processing..." statuses even though no actual work is happening. Users have no way to remove these stale entries because the delete endpoint deliberately blocks deletion of any session still marked as PROCESSING — a valid safety guard for genuinely active runs.

We need an automated mechanism that periodically identifies and resolves these abandoned sessions without requiring manual intervention or compromising the safety check for live research operations.

## What (Changes)

Add a background scheduler bean (`AbandonedSessionCleanupService`) that:
1. Runs every 30 minutes
2. Finds all `ResearchSession` records where `status = PROCESSING AND createdAt < NOW() - INTERVAL '1 hour'`
3. Marks matching sessions as `CANCELLED` (not deleted — preserves history for audit/debugging)

**No frontend changes.** This is a backend-only feature that improves the user experience by cleaning up stale statuses automatically, but no UI update required.

## Impact

- **Files affected**: 4 files
  - New: `AbandonedSessionCleanupService.java` (scheduled cleanup bean)
  - Modified: `ResearchSessionRepository.java` (add time-based query method)
  - Modified: `application.yml` (scheduler configuration properties)
  - New test: `AbandonedSessionCleanupServiceTest.java` (unit test with mocked repository)
- **Risk**: Low — adds a new service bean; does not modify existing cancel/delete logic. Scheduler is independent of active research operations.
- **Breaking changes**: None

## Assumptions Made

1. PostgreSQL handles time-based queries natively (`NOW() - INTERVAL '1 hour'` syntax works in JPQL)
2. Abandoned sessions have no live background threads running — marking as CANCELLED only affects DB state, not any in-flight SSE streams or research task executors (those are handled separately if needed)
3. The scheduler should log at INFO level when it runs and DEBUG level for individual session IDs found, so production logs stay clean but audit trail is available via debug logging
4. Default threshold of 1 hour stale + 30 minute interval matches the user's stated preference; both are configurable via `application.yml` properties
