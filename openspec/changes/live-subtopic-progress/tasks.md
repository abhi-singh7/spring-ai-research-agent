# Implementation Tasks: Live Per-Sub-Topic Progress

## Frontend Tasks (research-agent-ui)

### Task 1: Rewrite `ResearchService.handleProgress()`
- [x] Replace the single-step ping-pong logic with per-message handling:
  - [x] `Topic broken down into N sub-topics` → completed Breakdown step (create or mark first step COMPLETED)
  - [x] `Researching: <title> (round r/max)` → upsert sub-topic step IN_PROGRESS + round description
  - [x] `Completed research on: <title>` → upsert sub-topic step COMPLETED
  - [x] `Research on '<title>' failed after retries` → upsert sub-topic step FAILED
  - [x] `Research complete!` → all steps COMPLETED
  - [x] Fallback → active step description only (no status corruption)
- [x] Add `upsertSubTopicStep(title, status, description?)` helper (case-insensitive match, append if absent)

### Task 2: Smooth progress bar
- [x] `progressPercent` uses expected total `N + 2` once the breakdown count is known; falls back to current step count otherwise; capped at 100

## Verification

### Task 3: Static checks
- [x] `npx tsc --noEmit -p tsconfig.json` passes in `research-agent-ui/`

### Task 4: Manual verification (dev environment)
- [ ] Start a research session and watch the active-research page:
  - [ ] Breakdown step appears COMPLETED immediately
  - [ ] A new step appears per sub-topic with a spinner while its rounds run; description shows `Round r of m`
  - [ ] Each finished sub-topic flips to ✓ (or ✗ if it failed after retries)
  - [ ] Progress bar moves monotonically from ~14% toward 100% instead of jumping
  - [ ] `Generating Report` step appears last and completes when the report finishes
- [ ] Refresh the page mid-run: history-seeded steps render, live steps append for in-flight work, terminal poll reconciles
- [ ] Cancel mid-run: no step corruption; CANCELLED chip + persisted steps render
