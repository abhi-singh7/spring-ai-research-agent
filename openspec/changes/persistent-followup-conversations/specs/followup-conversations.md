# Requirements: Follow-Up Conversations (delta)

## ADDED Requirements

### Requirement: Follow-up Q&A pairs are persisted on the session
Each answered follow-up question SHALL be stored as a `FollowUpExchange` (`id`, `question`,
`answer`, `createdAt`) in an embedded list on the research session document, appended in
chronological order. Exchanges SHALL survive page reloads and be returned by the session detail
endpoint and a dedicated thread endpoint. Only successful exchanges are stored — a failed LLM
call SHALL NOT persist a question without its answer.

#### Scenario: Exchange survives reload
- **WHEN** a user asks a follow-up question on a COMPLETED session and receives an answer
- **AND THEN** the exchange is saved in the session document, and reloading the session detail returns it in `followUps`

#### Scenario: Failed LLM call stores nothing
- **WHEN** the follow-up LLM call throws
- **AND THEN** the API returns 500 and the session's `followUps` list is unchanged

### Requirement: Follow-up answers are grounded in full conversation context
The follow-up prompt SHALL include the session topic, the full final report, and the complete
prior Q&A transcript (all stored exchanges in order), followed by the new question. The call
SHALL be made through `LlmGateway` with the session id as first parameter so it is attributed to
the session in `llm_logs`.

#### Scenario: Second question references the first answer
- **WHEN** a user asks a follow-up that refers to an earlier answer ("what about the alternative you mentioned?")
- **AND THEN** the prompt sent to the LLM contains both the prior question and its answer

#### Scenario: Attribution in llm_logs
- **WHEN** a follow-up is answered
- **AND THEN** a new `llm_logs` document exists with the session's `sessionId`, containing the context-bearing user message and the answer as response

### Requirement: Threaded chat UI under the report
The history-detail view SHALL render a threaded conversation for COMPLETED sessions: prior
exchanges as chat bubbles (user questions right-aligned, LLM answers left-aligned with markdown
rendering) above an input form. Submitting a question SHALL append the returned exchange to the
thread without a page reload.

#### Scenario: Thread renders on load
- **WHEN** a session with stored exchanges is opened in history detail
- **AND THEN** all prior Q&A pairs are visible as a thread under the report, in chronological order

#### Scenario: New answer appended live
- **WHEN** the user submits a follow-up question and the answer arrives
- **AND THEN** both the question bubble and the markdown-rendered answer bubble appear at the bottom of the thread, with a loading indicator shown while waiting
