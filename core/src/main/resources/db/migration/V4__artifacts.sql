-- Stores generated code artifacts: original source, transformed code, tests, config files.
CREATE TABLE IF NOT EXISTS artifacts (
    id            BIGSERIAL   PRIMARY KEY,
    job_id        BIGINT      NOT NULL REFERENCES migration_jobs(id) ON DELETE CASCADE,
    task_id       BIGINT      REFERENCES agent_tasks(id) ON DELETE SET NULL,
    artifact_type VARCHAR(50) NOT NULL,   -- ORIGINAL | TRANSFORMED | TEST | CONFIG
    class_fqn     TEXT        NOT NULL,
    file_path     TEXT,
    content       TEXT,
    checksum      VARCHAR(64),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_artifacts_job       ON artifacts (job_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_task      ON artifacts (task_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_class_fqn ON artifacts (class_fqn);
CREATE INDEX IF NOT EXISTS idx_artifacts_type      ON artifacts (artifact_type);
