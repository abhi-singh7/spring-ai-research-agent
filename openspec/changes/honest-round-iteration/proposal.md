# Honest round iteration

## Why

The active-research page only ever shows `Round 1 of 3` (or `Round 1 of 2`) — the round counter never
advances. The UI is honest about what actually happens: **the pipeline never runs a second round.**
`researchOneSubTopic` stops as soon as the coverage heuristic (`>= 3` distinct captured URLs AND `> 400`
chars of findings) is met, and it evaluates that after round 1. A well-behaved first sweep always
cites 3+ pages and writes 400+ chars, so `maxRounds` is a no-op in practice — every session in the
MongoDB corpus has exactly one `## Findings` section per sub-topic regardless of the requested depth,
and the `RESEARCH_FOLLOWUP_PROMPT` that chases `Open Questions` is effectively dead code.

Round 1 already reports its remaining gaps (the note's `Open Questions` section, which real local-LLM
notes routinely list), so the missing signal in the stop rule is "is there anything left to chase?".

## What changes

- `researchRoundText` returns a `RoundOutcome(noteText, openQuestions)` instead of a bare note string:
  the structured attempt hands back the note's `openQuestions` verbatim (`normalizeOpenQuestions` drops
  blanks and "None"-style placeholders), the free-form fallback parses the section out of the note text
  (`parseOpenQuestions`, `null` when the note never states it).
- The stop rule in `researchOneSubTopic` becomes: stop on **coverage AND no open questions**, and stop
  when a round after the first adds no new source URLs. Coverage alone no longer ends the iteration, so
  `maxRounds` (request `maxIterations`, else `app.research.default-max-iterations`) is honoured: a
  sub-topic keeps chasing gaps until it reports none or the requested depth is exhausted. Resume derives its
  depth from the session document — `clampMaxRounds(session.getMaxRounds())` — so a resumed session chases gaps
  at the depth its plan was built with (legacy documents fall back to the configured default, and the 1..MAX_ROUNDS
  ceiling is re-applied).
- The supplementary-research call site (quality gate) keeps its string shape via `.noteText()`.

## Impact

- **Cost**: sessions where round 1 leaves gaps now run one extra research exchange per sub-topic (up to
  the requested depth). With the default depth of 3 that is bounded at 3 rounds/sub-topic — the
  `@Max(6)` / `MAX_ROUNDS` ceiling in `bounded-research-params` still applies; users who want the old
  single-pass behaviour ask for `maxIterations: 1` or lower `app.research.default-max-iterations`.
- Progress events already carry `(round r/max)`, so the UI's per-sub-topic step now legitimately shows
  `Round 2 of 3`, `Round 3 of 3`, `Round 2 of 2`; `ResearchService.handleProgress()` needs no change.
- Free-form fallback notes without an `Open Questions` section are treated as "unknown = gaps remain"
  (never "no gaps"), so iteration can only end on evidence, never on a parsing guess.
- No schema, storage or API change; existing fixtures whose round notes say `Open Questions: None`
  still stop after one round, so `ResearchFlowQualityTest`/`ResearchQualityEvalTest` expectations hold.
