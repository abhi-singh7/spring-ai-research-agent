# Implementation Tasks: Sub-Topic Persistence Fix

## Backend Tasks (TDD — Write Tests First)

### Task 1.0: [Setup] Verify test dependencies in pom.xml
- [ ] Confirm JUnit Jupiter and Spring Boot Test are present in `pom.xml`
- [ ] Check if Mockito is available for mocking ChatClient and repositories

### Task 1.1: [Test] Write failing unit test for intermediate step persistence
**TDD RED**: Create test that verifies ResearchStep entities are persisted after each sub-topic iteration (not just at end).
```java
// Expected behavior:
// Given: LLM returns valid JSON breakdown with N sub-topics
// When: processResearchAsync is called
// Then: After first sub-topic completes, sessionRepo.findById(sessionId) should return 2 steps 
//       (BREAKDOWN + SUBTOPIC_1), not just BREAKDOWN
```

**TDD GREEN**: Implement minimal change to make test pass — add `sessionRepo.save(session)` after each iteration.

### Task 1.2: [Test] Write failing unit test for JSON parsing fallback
**TDD RED**: Test that malformed JSON breakdown response triggers single generic sub-topic creation.
```java
// Expected behavior:
// Given: LLM returns invalid JSON string (not parseable as List<SubTopic>)
// When: processResearchAsync is called
// Then: Exactly 1 SUBTOPIC step is created with fallback title = request.getTopic()
// AND: Log message "Failed to parse sub-topic breakdown" appears in logs
```

**TDD GREEN**: Enhance logging around JSON parsing to capture raw response and failures.

### Task 1.3: [Test] Write failing unit test for DTO content field inclusion
**TDD RED**: Test that `ResearchController.getStatus()` returns StepDTO with content field populated.
```java
// Expected behavior:
// Given: ResearchSession has steps with non-null content
// When: GET /api/research/{sessionId} is called (or getStatus() method)
// Then: Returned StepDTO objects should have content field set (not null/missing)
```

**TDD GREEN**: If test fails, add `dto.setContent(step.getContent())` to status endpoint DTO mapping.

### Task 1.4: [Implementation] Apply all changes from tests above
- [ ] Add `sessionRepo.save(session)` call in sub-topic processing loop (line ~136)
- [ ] Enhance logging in JSON parsing section (lines 90-101) to capture raw response
- [ ] Fix DTO mapping if Task 1.3 test fails

### Task 1.5: [Refactor] Clean up and document changes
- [ ] Add inline comments explaining why intermediate saves occur
- [ ] Ensure log messages include session ID for traceability
- [ ] Verify no other code paths depend on steps-only-being-saved-at-end assumption

## Frontend Tasks (No TDD — Direct Implementation)
**None.** This is a backend-only fix. No frontend changes required.

## Integration & Verification
### Task 2.0: Run full build and fix any compilation errors automatically
- [ ] Execute `mvn clean test` in `research-agent-backend/`
- [ ] Address any compilation warnings or errors per build-fix skill guidelines
- [ ] Verify all new tests pass before proceeding to verification
