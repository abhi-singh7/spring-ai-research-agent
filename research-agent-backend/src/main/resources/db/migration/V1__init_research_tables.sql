-- ResearchSession table
CREATE TABLE research_session (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic           VARCHAR(1024) NOT NULL,          -- User's original research query
    status          VARCHAR(32) NOT NULL,            -- PENDING | PROCESSING | COMPLETED | FAILED | CANCELLED
    prompt          TEXT,                            -- System prompt used for this session
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at    TIMESTAMP WITH TIME ZONE,
    final_report    TEXT                             -- Synthesized research report (populated on completion)
);

CREATE INDEX idx_research_session_status ON research_session(status);
CREATE INDEX idx_research_session_created ON research_session(created_at DESC);

-- ResearchStep table
CREATE TABLE research_step (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES research_session(id) ON DELETE CASCADE,
    order_index     INTEGER NOT NULL,                -- Step ordering within a session
    type            VARCHAR(32) NOT NULL,             -- BREAKDOWN | SEARCH | READ | SYNTHESIS | SUBTOPIC | FINAL_REPORT
    content         TEXT,                            -- LLM output or tool result (may be null if step failed before producing content)
    status          VARCHAR(32) NOT NULL,             -- PENDING | RUNNING | COMPLETED | FAILED
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT uq_session_order UNIQUE (session_id, order_index)
);

CREATE INDEX idx_research_step_session ON research_step(session_id);
