# Spec: Automated Cleanup of Abandoned PROCESSING Sessions

## Requirement

A background scheduler automatically identifies and resolves abandoned `PROCESSING` sessions that have been stuck for longer than 1 hour, marking them as `CANCELLED` every 30 minutes. This prevents stale "Processing..." statuses from cluttering the history page while preserving data for audit/debugging purposes.

### Scenario 1: Stale PROCESSING sessions are marked CANCELLED
**WHEN** a `ResearchSession` exists in the database with `status = PROCESSING` AND `createdAt` older than 1 hour  
**THEN** the cleanup scheduler marks it as `CANCELLED` on its next run (within 30 minutes of becoming stale)

### Scenario 2: Recent PROCESSING sessions are NOT affected
**WHEN** a `ResearchSession` exists in the database with `status = PROCESSING` AND `createdAt` less than 1 hour ago  
**THEN** the cleanup scheduler leaves it unchanged — active research operations continue uninterrupted

### Scenario 3: Scheduler runs automatically on application startup
**WHEN** the Spring Boot application starts  
**THEN** the `AbandonedSessionCleanupService` bean is registered and begins its fixed-rate scheduling cycle (default: every 30 minutes) without requiring manual configuration or code changes

### Scenario 4: Cleanup does not interfere with delete safety guard
**WHEN** a session has been marked CANCELLED by the cleanup scheduler  
**THEN** it can be deleted via the existing `DELETE /api/research/history/{sessionId}` endpoint (returns 204 No Content, not 409 Conflict) — because CANCELLED is no longer PROCESSING

### Scenario 5: Cleanup does not crash on individual session failure
**WHEN** an exception occurs while marking one abandoned session as CANCELLED  
**THEN** the scheduler logs the error at ERROR level and continues processing remaining sessions without aborting the entire batch

### Scenario 6: Cleanup runs are logged for observability
**WHEN** the cleanup scheduler completes a run  
**THEN** it logs an INFO-level message stating how many stale sessions were found and how many were marked CANCELLED. Individual session IDs are logged at DEBUG level.
