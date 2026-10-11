# Requirements: Research Resume (delta)

## ADDED Requirements

### Requirement: Failed or cancelled sessions can be resumed in place
A `FAILED` or `CANCELLED` research session SHALL be resumable via
`POST /api/research/{sessionId}/resume`. The resume SHALL operate on the SAME session document:
its status flips to `PROCESSING`, its `"Failed: ..."` final report is cleared, and progress
continues over the existing SSE stream for that session id. Resuming a `COMPLETED` or already
`PROCESSING` session SHALL be rejected with HTTP 409; an unknown session SHALL return 404.

#### Scenario: Resume a mid-pipeline failure
- **WHEN** a session failed after 2 of 5 sub-topics completed and the user calls resume
- **AND THEN** the session status becomes `PROCESSING`, its final report is cleared, and the run continues without re-researching the two completed sub-topics

#### Scenario: Double resume is rejected
- **WHEN** two concurrent resume requests arrive for the same failed session
- **AND THEN** exactly one wins the atomic claim and starts processing; the other receives 409 (or finds the session already `PROCESSING`)

#### Scenario: Completed sessions are not resumable
- **WHEN** the user calls resume on a `COMPLETED` session
- **AND THEN** the API returns 409 and the session is unchanged

### Requirement: The original plan is recovered from the breakdown step
Resume SHALL recover the sub-topic plan by parsing the `BREAKDOWN` step's stored JSON
(`{topic, subTopics:[...]}`) into sub-topics with their titles, descriptions and planned search
queries. A fresh LLM breakdown SHALL be invoked ONLY when no usable plan can be recovered.

#### Scenario: Plan reuse without an LLM call
- **WHEN** a session has a completed BREAKDOWN step and is resumed
- **AND THEN** the same sub-topic titles and search queries are used as in the original run, with no breakdown LLM call

#### Scenario: Failed-during-breakdown session
- **WHEN** a session failed before any BREAKDOWN step was persisted and is resumed
- **AND THEN** a fresh breakdown is generated and the pipeline proceeds normally

### Requirement: Completed sub-topics are reused; missing or failed ones are re-researched
For each planned sub-topic, resume SHALL reuse the note stored in its `COMPLETED` SUBTOPIC step
(same position) verbatim — including re-parsing its captured sources for the report References —
and SHALL re-research from scratch any sub-topic whose step is `FAILED`, absent, or blank. A
re-researched sub-topic's step SHALL be written in place at the same `orderIndex` with status
`COMPLETED` and its new note as content.

#### Scenario: Partial run resumes cheaply
- **WHEN** a session with 3 completed and 2 failed sub-topics is resumed
- **AND THEN** research rounds run only for the 2 failed sub-topics, and the final report's Findings by Sub-Topic section contains all five notes

#### Scenario: Failed step is replaced in place
- **WHEN** a re-researched sub-topic completes
- **AND THEN** its step keeps its original `orderIndex`, its status becomes `COMPLETED` and its content is the new research note

### Requirement: Quality gate and report generation always re-run on resume
A resumed run SHALL execute the quality evaluation (with supplementary research when the gate
fails) and the streamed final report generation over the accumulated findings, exactly as a
fresh run does. A session that failed during report streaming SHALL therefore resume with no
sub-topic research cost.

#### Scenario: Report-stage failure resumes to synthesis only
- **WHEN** all sub-topics completed but report streaming failed, and the session is resumed
- **AND THEN** no sub-topic research rounds are executed; the quality gate runs and a new final report is streamed and persisted on success

### Requirement: Resumed runs support cancellation and crash recovery unchanged
A resumed run SHALL register a fresh cancellation handle so `DELETE /api/research/{sessionId}`
stops it cooperatively (no further rounds, stream disposed), and terminal writes SHALL remain
guarded so a cancelled resume can never be overwritten with `COMPLETED`/`FAILED`. A resume run
that crashes (e.g. server restart) SHALL be reclaimed by the existing stale-session cleanup
scheduler and become resumable again once marked `CANCELLED`.

#### Scenario: Cancel a resumed run
- **WHEN** the user cancels a session that is in `PROCESSING` due to a resume
- **AND THEN** further sub-topic rounds do not start, any active report stream is disposed, and the session ends `CANCELLED`

### Requirement: Resume affordance in the UI
The history-detail view SHALL show a "Resume research" button for sessions with status
`FAILED` or `CANCELLED` (never for `COMPLETED`). The live active-research view SHALL show the
same button when a run ends in failure. Activating it SHALL call the resume endpoint and, on
success, attach the session to the live stream (SSE) so progress renders as in a new run; API
errors SHALL surface as an inline/toast error without navigating away.

#### Scenario: Resume from history detail
- **WHEN** the user opens a failed session in history detail and clicks "Resume research"
- **AND THEN** the app navigates to the live view for that session and streams its progress events

#### Scenario: Resume failure surfaces an error
- **WHEN** the resume endpoint returns 409 or 404
- **AND THEN** an error message is shown and the user stays on the current page
