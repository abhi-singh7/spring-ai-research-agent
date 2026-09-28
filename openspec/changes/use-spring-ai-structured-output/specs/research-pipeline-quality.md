# Requirements: Research Pipeline Quality (delta)

## MODIFIED Requirements — Structured LLM Output

### Requirement: Breakdown plan obtained via structured output
The breakdown stage SHALL obtain its sub-topic plan through Spring AI structured output: a JSON schema generated from `List<SubTopic>` is appended to the planner prompt and the model's final answer is parsed into typed `SubTopic` records. The system SHALL NOT hand-parse free-text JSON (no markdown-fence stripping, no bespoke corrective re-prompting). Unconvertible answers consume one of the existing 3 retry attempts; after all attempts fail — or if the plan normalizes to zero usable entries — the system SHALL fall back to a single generic sub-topic covering the whole topic.

#### Scenario: Planner answer converts cleanly
- **WHEN** the model's final response parses into `List<SubTopic>`
- **AND THEN** title-less entries are dropped, missing ids renumbered by position, blank descriptions defaulted, and the plan is used as-is for research rounds

#### Scenario: Unconvertible planner output
- **WHEN** a structured completion attempt throws (answer not convertible to the schema)
- **AND THEN** the attempt is retried up to 3 times with backoff; if all fail, the session continues with one generic sub-topic instead of failing

### Requirement: Research rounds obtained via structured output with free-form fallback
Each research round SHALL request structured output (`ResearchRoundNote`: findings, sources consulted as `{title, url}` pairs, open questions) on its first attempt. If that attempt fails, the remaining attempts in the round's existing 3-attempt budget SHALL fall back to a free-form note parsed by the legacy text rules. The rendered/returned note SHALL keep the three-section markdown shape so step content, follow-up rounds, the coverage heuristic and source capture are unaffected. References SHALL still be built only from "Title — URL" lines (plus bare-URL scan) in the round notes, deduplicated by normalized URL.

#### Scenario: Structured round succeeds
- **WHEN** the first attempt of a round converts into a `ResearchRoundNote`
- **AND THEN** no further attempts are made for that round and the note is rendered to "## Findings / ## Sources Consulted / ## Open Questions" before being appended to the sub-topic's accumulated note

#### Scenario: Structured round fails, free-form fallback succeeds
- **WHEN** attempt 1 of a round throws but a later free-form attempt returns a parseable note
- **AND THEN** the round completes with that note and source capture proceeds exactly as before (bullet + bare URL extraction)

#### Scenario: All round attempts fail
- **WHEN** all 3 attempts of a round fail
- **AND THEN** the sub-topic is recorded as a FAILED step, research continues with remaining sub-topics, and total failure still fails the session

## UNCHANGED Requirements
Iterative rounds (coverage heuristic ≥400 chars AND ≥3 distinct URLs; no-new-evidence stop at round ≥2), per-phase temperatures (0.3 planning / 0.7 research / 0.4 synthesis), streamed free-form synthesis with References built only from the aggregated source list, cancellation checkpoints and atomic CANCELLED persistence, SSE event protocol — all unchanged by this delta.
