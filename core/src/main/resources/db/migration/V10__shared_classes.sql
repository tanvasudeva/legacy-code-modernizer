-- DD2: Shared library extraction tracking
-- Stores one row per shared class per job, with cross-service reference metadata.

CREATE TABLE IF NOT EXISTS shared_classes (
    id                   BIGSERIAL    PRIMARY KEY,
    job_id               BIGINT       NOT NULL REFERENCES migration_jobs(id) ON DELETE CASCADE,
    class_fqn            TEXT         NOT NULL,
    service_count        INT          NOT NULL DEFAULT 2,
    referencing_services JSONB,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_shared_classes_job_id ON shared_classes(job_id);
