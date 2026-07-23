# Proposal: Sub-Topic Persistence Fix

## Why (Motivation)
Research sessions are reporting only 1 SUBTOPIC entry with null content instead of N entries with actual research data. This breaks downstream functionality including report generation, which relies on collecting all sub-topic findings to synthesize a comprehensive final report. Without proper persistence of intermediate results, users lose their research progress and the system cannot produce complete outputs.

## What Changes
- Persist ResearchStep entities immediately after each sub-topic completes (not just at end)
- Improve logging around LLM breakdown response parsing for easier debugging
- Add JSON validation to ensure breakdown responses are parseable before proceeding

### Added
- Intermediate database commits during research processing loop
- Enhanced logging in ResearchOrchestratorService for LLM response inspection
- Validation logic to verify sub-topic count matches expected before processing

### Modified  
- `ResearchOrchestratorService.processResearchAsync()` — add intermediate save calls
- `ResearchController.getStatus()` — fix StepDTO mapping to include content field (if not already correct)

### Removed (if any)
None. No features being deprecated.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | Modified | Medium — changes flow logic and persistence timing |
| `src/main/java/com/researchagent/controller/ResearchController.java` | Verified (no change needed if DTO already correct) | Low |

## Assumptions Made
1. **LLM breakdown parsing is failing** in some cases, causing fallback to single generic sub-topic
2. **Steps are not being persisted until end of research**, so any interruption loses progress
3. **Intermediate persistence is acceptable** — saving after each sub-topic doesn't significantly impact performance for typical 5-10 sub-topics
4. **The DTO mapping in getStatus() may be missing content** — will verify and fix if needed
5. **PostgreSQL DDL-auto is set to `validate` mode**, so schema changes require manual migration (but no schema changes are being made here)

## Open Questions Resolved
- Q: Where is null content observed? A: Assumed direct DB query or history endpoint
- Q: Is intermediate persistence important? A: Yes — steps should be saved immediately after each sub-topic completes
- Q: What's expected behavior if LLM breakdown fails? A: Fallback to single generic sub-topic with improved logging for debugging
