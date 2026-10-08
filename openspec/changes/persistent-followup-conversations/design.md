# Design: Persistent Follow-Up Conversations

## Decision 1: embedded list on the session document (not a new collection)

Follow-up exchanges are stored as an embedded `List<FollowUpExchange>` inside the existing
`research_session` document — the exact pattern already used for steps.

- Every `save()` is one atomic document replace; there is no join, no cascade delete to manage
  (deleting a session already removes its embedded children), and no new index.
- A follow-up thread is small (a handful of exchanges per session); document-size limits are not a concern.
- `ResearchSession.followUps` gets `@JsonIgnore` + `@ToString.Exclude`, mirroring `steps`: the
  history **list** endpoints serialize the entity directly and must not bloat every row with full
  answers. The detail DTO and the dedicated thread endpoint build explicit DTOs instead.

Existing documents have no `followUps` field; Spring Data maps that to an empty list — no migration.

## Decision 2: pass full context, not a summary

The follow-up prompt includes, in order: the session topic, the **full final report**, and the
**complete prior Q&A transcript** (every stored exchange), then the new question.

- The deployment target is a local LLM served over an OpenAI-compatible endpoint — there is no
  per-call cost to optimize, and reports are at most a few thousand tokens. A summarization pass
  would add latency, a second LLM call, and lossy context for zero benefit.
- Transcripts grow linearly with user behavior (a dozen exchanges ≈ a couple of KB), so full
  context stays cheap even for long threads.

## Decision 3: prompt shape and temperature

- **System prompt**: role instructions — answer strictly from the provided research findings, be
  concise, say clearly when the report doesn't contain the answer, reference earlier answers in
  the conversation where relevant.
- **User message**: the assembled context (topic / report / transcript / question). This matches
  how `LlmGateway.complete(sessionId, systemPrompt, userMessage, temperature)` is used elsewhere
  and keeps the logging advisor's rendering meaningful (context lives in the user turn).
- **Temperature**: new constant `TEMP_FOLLOWUP = 0.3` — same grounded, low-creativity regime as
  planning; follow-up answers should stay faithful to the report.

## Decision 4: synchronous POST, no SSE for follow-ups

Follow-up answers are short (a paragraph to a page). The existing one-shot
`POST /{sessionId}/followup` contract is kept — only the response body changes from raw `String`
to `FollowUpExchangeDTO` (HTTP 201). No new SSE event types, no stream plumbing in
`ResearchStreamingService`. Streaming an answer is a possible later enhancement; it would require
new SSE events and reconnection semantics for a small UX gain.

## Decision 5: persist only successful exchanges

The exchange is saved **after** the LLM call succeeds, then the returned session instance is
adopted (`session = sessionRepo.save(session)`) per the project's save-adoption rule. A failed LLM
call throws (→ HTTP 500 via the existing handler) and stores nothing: a thread should never contain
a question with no answer, and retrying means simply resubmitting.

## Decision 6: frontend — threaded chat component replaces `followup-form`

- New `followup-thread` component (shared): inputs are `sessionId` and the initial `exchanges`
  list; it renders the thread (user bubbles right, assistant bubbles left with markdown rendering
  via ngx-markdown) plus a bottom input form. On submit it posts the question, appends the
  returned exchange to its local signal list, and scrolls into view. No page reload, no refetch —
  the server is the source of truth and returns the exact stored object (id + timestamp).
- `history-detail` renders `<followup-thread>` under the report for COMPLETED sessions, passing
  `session.followUps` as the initial exchanges.
- `followup-form` is deleted (single consumer replaced). `ResearchModels.FollowUpResponse` is
  replaced by `FollowUpExchange`; `submitFollowUp` returns the exchange object.

## API contract

```
POST /api/research/{sessionId}/followup
  Request:  { "question": "..." }
  Response: 201 { "id": "...", "question": "...", "answer": "...", "createdAt": "..." }
  Errors:   400 (not COMPLETED / blank question), 404, 500 (LLM failure — nothing stored)

GET /api/research/{sessionId}/followups
  Response: 200 [ { "id", "question", "answer", "createdAt" }, ... ]   (chronological)
```

`ResearchSessionDetailDTO` additionally gains `followUps: [...]` so a detail load carries the thread.

## Testing strategy

- **Unit** (`ResearchOrchestratorServiceTest`): submitFollowUp persists the exchange on the
  adopted session, builds a prompt containing report + prior transcript, and returns the stored
  exchange; LLM failure → exception, no save.
- **Integration** (`OrchestratorPersistenceIntegrationTest` or new focused test against local
  Mongo): round-trip — submit two exchanges, reload the document, both present in order.
- **Frontend**: manual verification (no npm test script exists); component is standalone with
  signal state, consistent with the rest of the app.
