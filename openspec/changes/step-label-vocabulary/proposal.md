# Step labels must cover the whole StepType vocabulary

## Why

`ResearchService.getStepName()` maps backend `StepType` names to human labels for the step list. Its map
has no `QUALITY_EVAL` entry, so the quality-gate step renders as the raw enum token **QUALITY_EVAL** in
the UI — the one step type the pipeline actually emits that the map missed. The map is a hand-maintained
copy of `StepType`, so it drifts every time the vocabulary changes (the enum also carries `SEARCH`,
`READ`, `SYNTHESIS`, which the pipeline does not emit yet).

## What changes

- `getStepName` gains `QUALITY_EVAL: 'Quality Check'` (and keeps the existing entries), so every emitted
  step type has a label.
- Unknown types still fall back to the raw token, so a future enum addition degrades visibly rather than
  crashing.

## Impact

- History detail, active-research, and the step-list component all call this one function, so the fix
  covers every render path.
- No backend change; `StepType` stays the source of truth. The remaining drift risk (a new enum member
  with no label) is documented here rather than papered over.
