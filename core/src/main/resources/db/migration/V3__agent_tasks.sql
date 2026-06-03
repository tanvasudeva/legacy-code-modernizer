-- Tracks every LLM agent invocation: analyzer, transformer, reviewer, test-generator.
CREATE TABLE IF NOT EXISTS agent_tasks (
    id            BIGSERIAL    PRIMARY KEY,
    job_id        BIGINT       NOT NULL REFERENCES migration_jobs(id) ON DELETE CASCADE,
    task_type     VARCHAR(50)  NOT NULL,   -- ANALYZE | TRANSFORM | REVIEW | TEST_GEN
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    class_fqn     TEXT,
    input_data    JSONB,
    output_data   JSONB,
    model_used    VARCHAR(100),
    tokens_used   INTEGER,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_tasks_job    ON agent_tasks (job_id);
CREATE INDEX IF NOT EXISTS idx_agent_tasks_status ON agent_tasks (status);
CREATE INDEX IF NOT EXISTS idx_agent_tasks_type   ON agent_tasks (task_type);
