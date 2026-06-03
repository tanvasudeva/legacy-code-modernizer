-- Stores the service decomposition output from Louvain clustering.
-- Each row represents one proposed microservice and the classes assigned to it.
CREATE TABLE IF NOT EXISTS service_boundaries (
    id           BIGSERIAL    PRIMARY KEY,
    job_id       BIGINT       NOT NULL REFERENCES migration_jobs(id) ON DELETE CASCADE,
    service_name VARCHAR(255) NOT NULL,
    class_fqns   JSONB        NOT NULL DEFAULT '[]',
    description  TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_service_boundary UNIQUE (job_id, service_name)
);

CREATE INDEX IF NOT EXISTS idx_svc_boundaries_job ON service_boundaries (job_id);
