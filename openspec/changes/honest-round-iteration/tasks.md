# Tasks — honest-round-iteration

## 1. Round outcome carries its gaps

- [x] 1.1 `private record RoundOutcome(String noteText, List<String> openQuestions)` in
      `ResearchOrchestratorService` (null open-questions list = "the note never stated the section").
- [x] 1.2 `researchRoundText` returns `RoundOutcome`: attempt 1 = structured note →
      `renderRoundNote(note)` + `normalizeOpenQuestions(note.openQuestions())`; later attempts =
      free-form text + `parseOpenQuestions(text)`.
- [x] 1.3 Supplementary-research call site (quality gate) adapts with `.noteText()`.

## 2. Stop rule that honours the requested depth

- [x] 2.1 `gapsRemain(RoundOutcome)` — null or non-empty open questions means chase another round.
- [x] 2.2 `normalizeOpenQuestions` + `isNonePlaceholder` collapse blanks and `None`/`n/a`/`nothing`/…
- [x] 2.3 `parseOpenQuestions` — `OPEN_QUESTIONS_HEADING_PATTERN` matches `## Open Questions`,
      `3. Open Questions —`, `**Open Questions**:`; returns `null` when no section exists.
- [x] 2.4 `researchOneSubTopic`: no-new-evidence stop (round >= 2) first, then
      `isCovered(...) && !gapsRemain(roundOutcome)`. Coverage alone no longer breaks after round 1.

## 3. Behaviour tests

- [x] 3.1 `ResearchFlowQualityTest.coveredRoundWithOpenQuestions_stillChasesThem_andUiSeesTheRoundCounter`
      — a covered round-1 note that still asks a question runs exactly one chase round and emits the
      `Researching: Subtopic 1 (round 1/3)` / `(round 2/3)` progress messages the UI depends on.
## 4. Resume replays the stored depth

- [x] 4.1 `processResumeAsync` derives `maxRounds` with `clampMaxRounds(session.getMaxRounds())` (was
      `session.getMaxRounds() != null ? … : defaultMaxIterations`), so re-researched sub-topics chase gaps at the
      plan's depth and a hand-edited document can never exceed `MAX_ROUNDS`.
- [x] 4.2 `ResearchOrchestratorServiceTest.processResumeAsync_shouldReplayTheStoredRoundDepth_andStreamRoundCounters`
      — stored `maxRounds: 3` with `defaultMaxIterations: 1`: the FAILED sub-topic runs two rounds and emits
      `(round 1/3)` / `(round 2/3)` progress, never `(round 3/3)`; completed sub-topics are still reused verbatim
      (no LLM call for them).
