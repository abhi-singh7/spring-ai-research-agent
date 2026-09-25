# Proposal: Live Per-Sub-Topic Progress on the Active Research Page

## Why (Motivation)
While a research session is running, the active-research page only ever shows two steps —
"Topic broken down into N sub-topics" and "Generating Report" — until the final report starts
streaming. The backend already emits fine-grained `PROGRESS` events for every research round
(`Researching: <title> (round r/max)`) and every finished sub-topic (`Completed research on:
<title>`), but the frontend state machine in `ResearchService.handleProgress()` collapses all of
them into a single step:

1. The first event creates step 1 (breakdown text, IN_PROGRESS).
2. Every later `Researching: ...` event only overwrites that step's `description` — it never
   renames the step or appends a new one.
3. On `Completed research on: X`, the code marks the in-progress step COMPLETED but then checks
   `hasInProgress` from the **stale pre-update snapshot**, so it concludes an in-progress step
   still exists and never creates the next sub-topic's step.
4. The next `Researching:` event finds no IN_PROGRESS step and re-activates the breakdown step
   with the new description.

Net effect: one step ping-pongs COMPLETED/IN_PROGRESS for the entire research phase, and the
progress bar (which divides by the current step count) is meaningless in the meantime.

## What Changes
- `ResearchService.handleProgress()` — rewritten to maintain **one step per sub-topic**:
  - `Topic broken down into N sub-topics` → a completed `Breakdown` step (created if the list is
    empty, otherwise the first/history-seeded step is marked COMPLETED).
  - `Researching: <title> (round r/max)` → upserts the sub-topic's own step as IN_PROGRESS with
    the round counter in its description.
  - `Completed research on: <title>` → marks that sub-topic's step COMPLETED.
  - `Research on '<title>' failed after retries` → marks that sub-topic's step FAILED.
  - `Research complete!` → marks every remaining step COMPLETED.
  - Unrecognized messages (e.g. `Research cancelled by user`) surface as the active step's
    description instead of corrupting step statuses.
- New private helper `upsertSubTopicStep(title, status, description?)` — case-insensitive
  title match against existing steps; appends a new step when absent.
- `progressPercent` — once the breakdown step reports N sub-topics, the percentage is computed
  against the known final total (breakdown + N sub-topics + report) so the bar moves smoothly
  instead of jumping as steps are appended.

### Added
- Per-sub-topic live steps on the active-research page (IN_PROGRESS → COMPLETED/FAILED).
- Round counter (`Round r of m`) shown in each sub-topic step's description.
- `upsertSubTopicStep()` helper in `ResearchService`.

### Modified
- `research-agent-ui/src/app/core/services/research.service.ts` — `handleProgress()`,
  `progressPercent`, plus the new helper. No backend changes: the orchestrator already emits the
  required PROGRESS messages.

### Removed (if any)
- The old single-step ping-pong logic in `handleProgress()` (including the stale-snapshot
  `hasInProgress` check and the terminal-status branch that rewrote every step's description).

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `research-agent-ui/src/app/core/services/research.service.ts` | Modified | Low — SSE event handling only; no API/schema changes |
| Backend (orchestrator/streaming) | Verified (no change needed) | None |

## Assumptions Made
1. PROGRESS payloads remain the stable strings emitted by `ResearchOrchestratorService`
   (`"Topic broken down into N sub-topics"`, `"Researching: <title> (round r/max)"`,
   `"Completed research on: <title>"`, `"Research on '<title>' failed after retries"`,
   `"Research complete!"`). The handler degrades gracefully for anything else.
2. Sub-topic titles (3–10 words per the breakdown prompt) do not end with a literal
   `(round d/d)` suffix, so the round-suffix regex cannot truncate a title.
3. Steps seeded from history after a mid-run page refresh keep their generic names
   (`Sub-topic N`); live events simply append titled steps for work that hasn't been persisted
   yet. Terminal-state polling still replaces the list with DB-backed steps, as before.

## Open Questions Resolved
- Q: Are the intermediate PROGRESS events lost in transit? A: No — SSE delivery works (the
  breakdown step and report chunks arrive). The loss is entirely in the frontend state machine.
- Q: Should the backend send structured payloads instead of strings? A: Not required for this
  fix; `StreamUpdate.payload` already supports objects if a future change wants typed events.
