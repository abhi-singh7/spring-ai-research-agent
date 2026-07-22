## ADDED Requirements

### Requirement: Streaming content is displayed during active research
During an active research session before the final report is complete, the accumulated content from SSE events (CONTENT and REPORT_CHUNK) SHALL be visible to the user in real-time via the `<report-viewer>` component.

#### Scenario: Content appears as it streams in
- **WHEN** a research session is in PROCESSING status with streaming active (`isStreaming() === true`) and no final report exists yet
- **THEN** the `<report-viewer>` component displays the accumulated content from `_reportContentSignal` (which contains all CONTENT and REPORT_CHUNK event payloads received so far)

#### Scenario: Content does not appear after session completes
- **WHEN** a research session status changes to COMPLETED with a final report available (`researchSession().finalReport` is set)
- **THEN** the `<report-viewer>` component displays `researchSession().finalReport` instead of the accumulated streaming content

#### Scenario: No viewer shown when no active research
- **WHEN** there is no active research session or the session is not in PROCESSING status and has no final report
- **THEN** neither the streaming content nor the final report viewer is displayed

### Requirement: Step list shows dynamic step during sub-topic research
When a PROGRESS event arrives during sub-topic research (when `_researchStepsSignal` is empty), a dynamic "Researching Sub-topics" step SHALL be created and displayed, showing the current sub-topic being researched.

#### Scenario: Dynamic step created from first PROGRESS event
- **WHEN** `handleProgress()` receives a PROGRESS event and `_researchStepsSignal.length === 0`
- **THEN** a new step with `{ stepNumber: 1, name: 'Researching Sub-topics', status: 'IN_PROGRESS' }` is created with the description from `event.payload`

#### Scenario: Dynamic step remains IN_PROGRESS until REPORT_START arrives
- **WHEN** a dynamic step exists and no subsequent steps have been added via `REPORT_START`
- **THEN** the dynamic step retains its IN_PROGRESS status and description from the last PROGRESS event

#### Scenario: Subsequent PROGRESS events update dynamic step description
- **WHEN** `handleProgress()` receives another PROGRESS event while a dynamic step exists but no new steps have been added via `REPORT_START`
- **THEN** the dynamic step's description is updated with the new payload value, and previous sub-topic steps (if any) are marked COMPLETED

### Requirement: Step completion uses reliable matching logic
When a STEP_COMPLETE event arrives, the corresponding step SHALL be marked as COMPLETED using robust identifier matching.

#### Scenario: Step matched by name in payload
- **WHEN** `handleStepComplete()` receives a STEP_COMPLETE event and `event.payload` is a string that matches or is contained within a step's name
- **THEN** that step is updated with status 'COMPLETED'

#### Scenario: Step matched by index in payload
- **WHEN** `handleStepComplete()` receives a STEP_COMPLETE event and `event.payload` contains a numeric index, the step at that index (or with matching stepNumber) is marked COMPLETED

#### Scenario: No match found falls back to last known IN_PROGRESS step
- **WHEN** neither name nor index matching succeeds for a STEP_COMPLETE event payload
- **THEN** the most recently added IN_PROGRESS step is marked as COMPLETED

### Requirement: Progress bar reflects accurate progress during sub-topic research
The progress percentage SHALL be non-zero during sub-topic research, calculated from available steps.

#### Scenario: Progress shows meaningful value during sub-topic research with dynamic step
- **WHEN** `_researchStepsSignal.length > 0` and the session is in PROCESSING status
- **THEN** `progressPercent()` returns a percentage based on completed + partially-completed IN_PROGRESS steps divided by total steps, displayed as an integer between 1 and 99

#### Scenario: Progress shows 100% when all research phases complete
- **WHEN** all steps have status 'COMPLETED'
- **THEN** `progressPercent()` returns exactly 100

### Requirement: Session loading errors are surfaced to users
When the initial session fetch in `loadSession()` fails, an error notification SHALL be displayed to the user.

#### Scenario: Error on failed session fetch for new research
- **WHEN** `getStatus()` HTTP call fails during active research setup (session doesn't exist or network error)
- **THEN** a user-facing error message is shown explaining that the session could not be loaded

#### Scenario: Error on failed session fetch when reconnecting to existing session
- **WHEN** `getStatus()` HTTP call fails while reconnecting SSE for an existing research session
- **THEN** a user-facing error message is shown indicating the session status could not be retrieved, with a retry option
