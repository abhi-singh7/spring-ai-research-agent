# Tasks: Persistent Follow-Up Conversations

## Backend
- [ ] Add `FollowUpExchange` embedded entity (`id`, `question`, `answer`, `createdAt`) and `List<FollowUpExchange> followUps` on `ResearchSession` (`@JsonIgnore`, `@ToString.Exclude`, mirroring `steps`)
- [ ] Add `FollowUpExchangeDTO`; add `followUps` to `ResearchSessionDetailDTO`
- [ ] Rewrite `ResearchOrchestratorService.submitFollowUp`: full context (topic + report + prior transcript) via `llmGateway.complete(sessionId, system, user, TEMP_FOLLOWUP=0.3)`; persist exchange on success, adopt saved session, return the `FollowUpExchange`
- [ ] `ResearchController`: POST `/followup` returns 201 `FollowUpExchangeDTO`; add GET `/{sessionId}/followups`
- [ ] Update `ResearchSessionDetailDTO` mapping in `getHistoricalSession`

## Frontend
- [ ] Models: replace `FollowUpResponse` with `FollowUpExchange { id, question, answer, createdAt }`; add `followUps?: FollowUpExchange[]` to `ResearchSession`
- [ ] `ResearchService`: `submitFollowUp` returns `Observable<FollowUpExchange>`; add `getFollowUps(sessionId)`
- [ ] New `followup-thread` component: threaded chat (user right / assistant left, markdown answers via ngx-markdown), bottom input form, loading + error states, appends returned exchange locally
- [ ] `history-detail`: render `followup-thread` under the report for COMPLETED sessions with initial `exchanges` from session detail
- [ ] Delete `followup-form` component

## Tests & Verification
- [ ] Unit: submitFollowUp persists on adopted session, prompt contains report + prior transcript, LLM failure → no save (extend `ResearchOrchestratorServiceTest`)
- [ ] Integration: two-exchange round-trip against local MongoDB (extend persistence test)
- [ ] `mvn test` green; frontend builds (`npx ng build`)
