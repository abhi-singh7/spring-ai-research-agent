# Requirements: Research Pipeline Quality

## ADDED Requirements — Iterative Sub-topic Research

### Requirement: Multi-round research per sub-topic
The system SHALL execute up to N LLM rounds per sub-topic, where N is the request's `maxIterations` (or `app.research.default-max-iterations`, min 1). Round 1 sweeps the planned search queries; later rounds chase Open Questions from prior notes.

#### Scenario: Thin first round triggers a second round
- **WHEN** round-1 findings are shorter than 400 chars or cite fewer than 3 distinct source URLs
- **THEN** the system runs another research round for that sub-topic, providing its Open Questions to the model

#### Scenario: Sufficient coverage stops iteration early
- **WHEN** a note cites ≥3 distinct source URLs AND contains ≥400 chars of findings
- **AND THEN** no further rounds are executed for that sub-topic (even if `maxIterations` allows more)

#### Scenario: Diminishing returns stop iteration
- **WHEN** a round adds no new source URLs beyond previous rounds and the sub-topic is not yet covered
- **AND THEN** further rounds for that sub-topic are skipped after round 2

## ADDED Requirements — Source Provenance

### Requirement: References built only from captured sources
The final report's `## References` section SHALL be constructed exclusively from "Title — URL" lines parsed out of research notes (bullet or bare URL), deduplicated by normalized URL. The synthesis prompt SHALL forbid citing URLs not in the aggregated list, and SHALL require an explicit confidence caveat when no sources were captured.

#### Scenario: Bullet sources flow into References
- **WHEN** a note contains "- Survey paper — https://example.com/paper-one"
- **AND THEN** that line appears (deduplicated) in the synthesis input's Aggregated Source List and may be cited in `## References`

#### Scenario: Bare URLs are captured too
- **WHEN** findings mention "https://one.io/a" without a bullet
- **AND THEN** it is added to the aggregated source list as a standalone entry

#### Scenario: No sources captured
- **WHEN** no URL was parsed from any note across all sub-topics
- **AND THEN** the report omits fabricated References and states under Research Gaps & Confidence that evidence was limited

## ADDED Requirements — Resilience

### Requirement: Partial failure tolerance
A failed sub-topic SHALL NOT abort the session. Each LLM call retries up to 3 times with backoff; if all attempts fail, the sub-topic is recorded as a FAILED step and research continues with remaining sub-topics. The final report SHALL list failed sub-topics under Research Gaps & Confidence.

#### Scenario: One of two sub-topics fails
- **WHEN** all retry attempts for sub-topic A fail but sub-topic B succeeds
- **AND THEN** the session completes, the report is generated from B's findings, and "Research Gaps" names A as failed

#### Scenario: Total failure still fails the session
- **WHEN** every sub-topic exhausts its retries
- **AND THEN** no report is generated; the session status becomes FAILED and an ERROR SSE event carries the reason

### Requirement: Breakdown JSON recovery
When the breakdown response does not contain parseable JSON, the system SHALL issue one corrective retry demanding raw JSON only, before falling back to a single generic sub-topic.

## ADDED Requirements — Execution Semantics

### Requirement: Non-blocking submit
POST /api/research SHALL return immediately after creating the session; research processing SHALL run asynchronously on `researchTaskExecutor`, with progress delivered over SSE.
