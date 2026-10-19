# Tasks — step-label-vocabulary

## 1. Cover the emitted vocabulary

- [x] 1.1 Add `QUALITY_EVAL: 'Quality Check'` to the `getStepName` map.
- [x] 1.2 Keep the raw-token fallback for unknown types (visible degradation, no crash).

## 2. Still open

- [ ] 2.1 Label the `FINAL_REPORT` step by its status ('Generating Report' while `IN_PROGRESS`,
      'Final Report' once `COMPLETED`) — the label is currently status-blind.
- [ ] 2.2 Generate the map from the shared `StepType` model export so the UI cannot drift again
      (`core/models/research.model.ts` already declares the enum).
