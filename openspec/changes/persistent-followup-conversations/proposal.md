# Proposal: Persistent Follow-Up Conversations

## Why (Motivation)
Follow-up questions today are stateless and ephemeral:

1. **No persistence** — `ResearchOrchestratorService.submitFollowUp` answers from the final report only; nothing is stored. Reloading the page loses every Q&A pair.
2. **No conversation context** — each follow-up is answered in isolation, so a second question can't refer to the answer of the first ("what about the alternative you mentioned?").
3. **One-shot UI** — `followup-form` renders a single question box; after one ask, the form is replaced by a `<pre>` dump of the answer. There is no thread, no history, no markdown rendering.

The LLM call already flows through `LlmGateway.complete(sessionId, ...)` so it gets `sessionId` attribution in `llm_logs` — that part is done and must be preserved.

## What Changes

### Added
- **`FollowUpExchange`** (embedded entity) — `id`, `question`, `answer`, `createdAt`; stored as an embedded list on the `research_session` document (`ResearchSession.followUps`), same pattern as embedded steps: no new collection, atomic document replace on save.
- **`FollowUpExchangeDTO`** — wire shape for the exchange; added to `ResearchSessionDetailDTO.followUps` so a history-detail load returns the full thread.
- **`GET /api/research/{sessionId}/followups`** — dedicated endpoint returning the stored thread (lets the chat view load/refresh just the conversation).
- **`followup-thread` component** — threaded chat rendered under the report: user questions as right-aligned bubbles, LLM answers as left-aligned markdown-rendered bubbles (ngx-markdown), input form at the bottom, per-message loading spinner and error state. Initial exchanges come from the session detail; new exchanges are appended client-side after a successful submit.

### Modified
- **`ResearchOrchestratorService.submitFollowUp`** — builds full conversation context (topic + final report + complete prior Q&A transcript) and passes it to `llmGateway.complete(sessionId, systemPrompt, userMessage, TEMP_FOLLOWUP)`; persists the new exchange on the session and **adopts the saved instance** (`session = sessionRepo.save(session)`) before returning it. Returns the `FollowUpExchange` instead of a raw String.
- **`ResearchController.submitFollowUp`** — response changes from raw `String` to `FollowUpExchangeDTO` (HTTP 201).
- **Frontend** — `ResearchModels.FollowUpResponse` replaced by `FollowUpExchange`; `ResearchService.submitFollowUp` returns the exchange object and gains `getFollowUps(sessionId)`; `history-detail` swaps `followup-form` for `followup-thread`.

### Removed
- **`followup-form` component** — superseded by `followup-thread` (its only consumer is history-detail).

## Impact
- **Affected specs**: `followup-conversations` (new capability).
- **Storage**: no migration needed — MongoDB auto-creates the embedded field; existing session documents simply lack `followUps` (maps to empty list).
- **API compatibility**: the follow-up POST response shape changes (`String` → JSON object). The only consumer is this app's frontend, updated in the same change.
- **LLM logs**: unchanged mechanism — each follow-up answer is one document in `llm_logs` attributed to the session (already true today; preserved).
