-- Phase 4.3: add system_id to distinguish multi-agent vs single-prompt baselines,
-- and a baseline column to store the comparison value in the same row.
ALTER TABLE eval_metrics
    ADD COLUMN IF NOT EXISTS system_id VARCHAR(50) NOT NULL DEFAULT 'multi-agent',
    ADD COLUMN IF NOT EXISTS baseline  DECIMAL(10, 4);

CREATE INDEX IF NOT EXISTS idx_eval_metrics_system ON eval_metrics (system_id);
