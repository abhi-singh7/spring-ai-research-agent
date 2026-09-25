# Design: Live Per-Sub-Topic Progress

## Event → Step Mapping

The orchestrator (`ResearchOrchestratorService`) emits these `PROGRESS` payloads in order:

| Backend payload | UI effect |
|---|---|
| `Topic broken down into N sub-topics` | Ensure a completed `Breakdown` step exists (create if list empty; mark first step COMPLETED if history-seeded) |
| `Researching: <title> (round r/max)` | Upsert step named `<title>` → IN_PROGRESS, description `Round r of m` |
| `Completed research on: <title>` | Upsert step named `<title>` → COMPLETED |
| `Research on '<title>' failed after retries` | Upsert step named `<title>` → FAILED, description = payload |
| `Research complete!` | Mark all steps COMPLETED |
| anything else (e.g. `Research cancelled by user`) | Set active (IN_PROGRESS) step's description to the message; no status change |

Title extraction:
- `Researching:` — regex `^Researching:\s*(.+?)(?:\s*\(round (\d+)\/(\d+)\))?$` (round suffix is
  optional: single-round runs send no suffix).
- `Completed research on:` / failure message — prefix strip / quoted-title capture.

Matching existing steps is case-insensitive exact title equality (`upsertSubTopicStep`). History-
seeded steps use generic names (`Sub-topic N`) and therefore never match live titles; live work
appends new titled steps after them, and the terminal-state poll replaces the whole list with
DB-backed steps — same reconciliation behavior as before this change.

## Progress Bar

`progressPercent` = `(completed + 0.5 × inProgress) / expectedTotal`, capped at 100.
`expectedTotal` is `N + 2` (breakdown + N sub-topics + report) once a step's description matches
`Topic broken down into \d+`; otherwise it falls back to the current step count (e.g. for
history-seeded sessions where the breakdown description is the persisted plan JSON).

## Failure Modes

- **Unrecognized payload**: never mutates statuses; worst case the message appears as the active
  step's description. Terminal-state polling still reconciles the list from the DB.
- **Sub-topic failure**: its step shows FAILED (red ✗) inline; the run continues and the report
  documents the gap — matching backend partial-failure semantics.
- **Cancel mid-run**: `Research cancelled by user` lands in the fallback branch; polling picks up
  CANCELLED + persisted steps, as before.

## Non-Goals

- No backend/DTO/SSE schema changes (strings stay strings).
- No per-round steps (rounds are sub-state of a sub-topic step, shown in its description).
- No reconnection/buffering changes; SSE resilience (backoff + polling fallback) is untouched.
