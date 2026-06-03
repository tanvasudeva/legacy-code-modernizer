CREATE TABLE IF NOT EXISTS migration_jobs (
    id               BIGSERIAL    PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    source_directory TEXT         NOT NULL,
    status           VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    metadata         JSONB,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_migration_jobs_status ON migration_jobs (status);
