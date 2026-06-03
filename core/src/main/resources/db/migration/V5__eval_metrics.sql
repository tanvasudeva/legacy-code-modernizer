-- Evaluation metrics per artifact: compilation success, test pass rate, semantic similarity, etc.
CREATE TABLE IF NOT EXISTS eval_metrics (
    id           BIGSERIAL      PRIMARY KEY,
    job_id       BIGINT         NOT NULL REFERENCES migration_jobs(id) ON DELETE CASCADE,
    artifact_id  BIGINT         REFERENCES artifacts(id) ON DELETE SET NULL,
    metric_name  VARCHAR(100)   NOT NULL,  -- COMPILATION_SUCCESS | TEST_PASS_RATE | SIMILARITY_SCORE | HALSTEAD_COMPLEXITY
    metric_value DECIMAL(10, 4),
    metadata     JSONB,
    measured_at  TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_eval_metrics_job      ON eval_metrics (job_id);
CREATE INDEX IF NOT EXISTS idx_eval_metrics_artifact ON eval_metrics (artifact_id);
CREATE INDEX IF NOT EXISTS idx_eval_metrics_name     ON eval_metrics (metric_name);
