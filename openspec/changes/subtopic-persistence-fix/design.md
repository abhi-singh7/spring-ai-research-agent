# Design: Sub-Topic Persistence Fix

## Technical Approach
The fix addresses two interrelated issues in `ResearchOrchestratorService.processResearchAsync()`:

1. **Missing intermediate persistence**: Steps are added to the session collection via `session.addStep()` but not persisted until the end of research or on error. This means:
   - If application restarts during sub-topic processing, all un-saved steps are lost
   - No visibility into progress if researcher takes longer than expected

2. **Potential JSON parsing failures**: The breakdown LLM response may not return valid JSON array, triggering fallback to single generic sub-topic. Without improved logging, debugging this is difficult.

### Implementation Strategy
- Add `sessionRepo.save(session)` call inside the sub-topic processing loop (after each iteration)
- Enhance logging around JSON parsing to capture raw LLM response and parse results
- Verify and fix DTO mapping in `ResearchController.getStatus()` if content field is missing

Following existing patterns:
- Use same `CascadeType.ALL` cascade configuration already on ResearchSession.steps collection
- Maintain transactional boundaries — each save happens within Spring-managed transaction
- Keep SSE streaming unchanged — progress events still flow through ResearchStreamingService

## Data Model Changes
**No schema changes required.** Current `research_step` table structure supports the fix:
- `content` column is TEXT (nullable)
- `order_index` and unique constraint already in place
- Cascade configuration on ResearchSession entity handles automatic persistence

### Entity Relationships
```
ResearchSession (1) ---- (*) ResearchStep
  ├── cascade = CascadeType.ALL
  └── orphanRemoval = true
```

No migration needed since we're not altering the schema — only changing when existing structure is populated.

## API Changes (Backend)
**No new endpoints.** Existing endpoints remain unchanged:

| Endpoint | Method | Path | Purpose | Change |
|----------|--------|------|---------|--------|
| Start Research | POST | `/api/research` | Creates session, kicks off async research | No change |
| Get Status | GET | `/api/research/{sessionId}` | Returns status with steps | Verify DTO includes content (may need fix) |
| Get History | GET | `/api/research/history` | Paginated history list | No change |
| Get Historical Session | GET | `/api/research/history/{sessionId}` | Full detail including content | No change |

### Potential DTO Fix in getStatus()
If StepDTO mapping is missing content field (as suspected from code review), update `ResearchController.getStatus()` to include it:

```java
// Current code may be missing this line:
dto.setContent(step.getContent());
```

This ensures the status endpoint returns full step data, not just metadata.

## Frontend Changes (if applicable)
**No frontend changes required.** The fix is backend-only — improving persistence and potentially exposing more complete data via existing API.

Frontend already handles SSE streaming correctly:
- Detects `EventSource.readyState === CLOSED` and switches to polling on reconnect
- Uses StepDTO from `/api/research/{sessionId}` for step list display

## Testing Strategy
### Unit Tests (TDD)
1. **Test intermediate persistence**: Verify ResearchStep entities are saved after each sub-topic iteration, not just at end
   - Mock ChatClient to return controlled responses
   - Assert `sessionRepo.findById()` returns persisted steps mid-loop
   
2. **Test JSON parsing fallback**: Verify single sub-topic creation when LLM response isn't valid JSON array
   - Mock ChatClient to return malformed JSON
   - Confirm fallback behavior creates exactly 1 SubTopic

3. **Test enhanced logging**: Verify breakdown response is logged before/after parsing
   - Use log capturer or verify logging calls via mock framework

### Integration Tests (if time permits)
- End-to-end research flow with real PostgreSQL (dev profile)
- Verify all N sub-topic steps persisted after completion

## Security Considerations
**No authentication/authorization changes.** Research session creation is public API. No user input is modified — only internal processing improvements.

## Performance Considerations
**Minimal impact expected:**
- Additional DB writes occur once per sub-topic (typically 5-10 times)
- Each write is a simple INSERT via JPA cascade
- PostgreSQL handles small transaction batches efficiently
- Timeout configuration (`spring.ai.sse.timeout:600000`) still applies to SSE connections

No connection pool tuning or batch optimizations needed for typical sub-topic counts.
