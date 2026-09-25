## ADDED Requirements

### Requirement: One Step Per Sub-Topic During Live Research
The active-research page SHALL display one step list entry per researched sub-topic while a session
is PROCESSING, with each entry transitioning IN_PROGRESS → COMPLETED (or FAILED) as the backend
emits the corresponding PROGRESS events. The round counter SHALL be shown in the step's
description (`Round r of m`), not as separate steps.

#### Scenario: Sub-topic starts researching
- **WHEN** the SSE stream delivers `PROGRESS` with payload `Researching: <title> (round r/max)`
- **THEN** a step named `<title>` exists in the list with status IN_PROGRESS and description `Round r of m`
- **AND IF** no such step existed before
- **THEN** it is appended after all existing steps

#### Scenario: Sub-topic finishes
- **WHEN** the SSE stream delivers `PROGRESS` with payload `Completed research on: <title>`
- **THEN** the step named `<title>` has status COMPLETED
- **AND** no other step's status changes

#### Scenario: Sub-topic fails after retries
- **WHEN** the SSE stream delivers `PROGRESS` with payload `Research on '<title>' failed after retries`
- **THEN** a step named `<title>` exists with status FAILED and the payload as its description

#### Scenario: Breakdown step completes
- **WHEN** the SSE stream delivers `PROGRESS` with payload `Topic broken down into N sub-topics`
- **THEN** a completed `Breakdown` step is present (created if the list was empty, or the first
  history-seeded step is marked COMPLETED)

### Requirement: Stable Progress Percentage During Live Research
The progress bar SHALL be computed against the known final step total once the breakdown count is
available, so that appending new sub-topic steps does not cause large percentage jumps.

#### Scenario: Bar after breakdown with N sub-topics
- **WHEN** only the completed Breakdown step exists and it reports `Topic broken down into 5 sub-topics`
- **THEN** progressPercent is `round(1 / 7 * 100)` = 14 (expected total = breakdown + 5 sub-topics + report)

#### Scenario: Bar near completion
- **WHEN** all N sub-topic steps are COMPLETED and the `Generating Report` step is IN_PROGRESS
- **THEN** progressPercent is `round((N + 1 + 0.5) / (N + 2) * 100)` (e.g. 93 for N = 5)

#### Scenario: History-seeded session without breakdown count text
- **WHEN** steps were seeded from history and no step description matches `Topic broken down into \d+`
- **THEN** progressPercent falls back to dividing by the current number of steps

### Requirement: Graceful Handling of Unrecognized Progress Messages
Unrecognized PROGRESS payloads SHALL NOT corrupt step statuses.

#### Scenario: Cancellation message mid-run
- **WHEN** a PROGRESS payload that matches no known pattern arrives (e.g. `Research cancelled by user`) while a step is IN_PROGRESS
- **THEN** only that step's description is updated; all statuses are unchanged
- **AND** subsequent polling reconciles the list with persisted DB steps when the session reaches a terminal state
