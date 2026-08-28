## ADDED Requirements

### Requirement: Intermediate Step Persistence
The system SHALL persist ResearchStep entities immediately after each sub-topic completes, not only at end of research or on error. This ensures progress is preserved if application restarts during long-running research processes.

#### Scenario: Sub-topic step saved mid-loop
- **WHEN** `ResearchOrchestratorService.processResearchAsync()` completes processing one sub-topic and calls `session.addStep()` for that SUBTOPIC entry
- **THEN** The session repository should immediately persist the updated steps collection via `sessionRepo.save(session)` before proceeding to next iteration
- **AND IF** application restarts between sub-topic iterations
- **THEN** Previously completed sub-topic findings remain in database and can be recovered

#### Scenario: All sub-topics persisted after completion
- **WHEN** all N sub-topics have been processed and final report generated
- **THEN** `research_step` table contains exactly N+1 entries: 1 BREAKDOWN step + N SUBTOPIC steps (or FINAL_REPORT if that's also saved)
- **AND IF** user queries via `/api/research/history/{sessionId}` endpoint
- **THEN** All sub-topic steps appear in order with their actual research content

### Requirement: LLM Breakdown Response Logging
The system SHALL log the raw LLM response from topic breakdown step before and after JSON parsing attempt. This enables debugging when sub-topics don't parse correctly.

#### Scenario: Successful JSON parse logged
- **WHEN** `chatClient.prompt().system(breakdownPrompt).user(...)` returns a valid JSON array
- **THEN** Log message includes raw response content with session ID prefix (e.g., "LLM breakdown response for session {id}: ...")
- **AND IF** parsing succeeds via `objectMapper.readValue()`
- **THEN** Log message confirms parsed count: "Successfully parsed N sub-topics"

#### Scenario: Failed JSON parse logged
- **WHEN** LLM returns text that cannot be parsed as `List<SubTopic>`
- **THEN** Log error includes raw response snippet (truncated to 500 chars if too long) and exception details
- **AND IF** fallback single-sub-topic creation occurs
- **THEN** Log message indicates fallback: "Using fallback sub-topic count: 1, title: {topic}"

### Requirement: DTO Content Field Inclusion
The system SHALL include the `content` field in StepDTO responses from all endpoints that return step metadata. This ensures frontend and debugging tools see actual research data, not just step type/status/order.

#### Scenario: getStatus endpoint returns content
- **WHEN** client calls `GET /api/research/{sessionId}` (or internal `getStatus()` method)
- **THEN** Returned StepDTO objects include non-null content field for steps that have content
- **AND IF** step has no content (e.g., failed before producing output)
- **THEN** Content field is null or empty string (matching database value), not missing from DTO

#### Scenario: getHistoricalSession endpoint returns content
- **WHEN** client calls `GET /api/research/history/{sessionId}` (or internal detail retrieval method)
- **THEN** Returned StepDTO objects include full content for all steps including SUBTOPIC entries
- **AND IF** content is large TEXT field (>1KB)
- **THEN** Content is included without truncation (TEXT column supports PostgreSQL's unlimited length)

### Requirement: Sub-topic Count Validation
The system SHALL validate that parsed sub-topic count matches expected range before processing. This prevents runaway loops if LLM returns unexpected number of items.

#### Scenario: Valid sub-topic count processed normally
- **WHEN** parsed sub-topics list size is between 1 and `request.getMaxIterations()` (default 10)
- **THEN** All items are processed in loop with correct indexing (`i + 1` for order_index)
- **AND IF** count exceeds max iterations
- **THEN** Only first N items are processed, rest ignored with warning log

#### Scenario: Invalid sub-topic count handled gracefully
- **WHEN** parsed sub-topics list is empty (0 items) after JSON parse succeeds
- **THEN** System creates single generic sub-topic from original topic as fallback
- **AND IF** LLM returns malformed JSON that strips to non-array type
- **THEN** Exception caught, logged with raw response, fallback applied

## MODIFIED Requirements

### Requirement: Step Persistence Timing Changed
The system previously persisted steps only at end of research (after final report or on error). This requirement modifies that behavior to persist after each sub-topic completes.

#### Scenario: Old behavior removed
- **WHEN** researching a topic with 5 sub-topics, current code saves all 5 SUBTOPIC entries only after FINAL_REPORT step is generated and saved
- **THEN** New behavior saves each SUBTOPIC entry immediately after its corresponding LLM response is received and validated
- **AND IF** final report generation fails
- **THEN** All previously completed sub-topic findings are still persisted (not lost due to cascading failure)

#### Scenario: Transactional boundary maintained
- **WHEN** `sessionRepo.save(session)` is called mid-loop for intermediate persistence
- **THEN** Operation occurs within Spring-managed transaction context (default @Transactional on service methods)
- **AND IF** sub-topic processing throws exception after some but not all saves complete
- **THEN** Partial progress remains in database; session status set to FAILED with error message
