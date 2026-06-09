-- Phase 4.6: record which model acted as judge for each LLM_JUDGE_SCORE row.
-- Enables readers to verify cross-model evaluation design (multi-agent scored
-- by GPT-4o, GPT-4o baseline scored by Claude, Claude baseline scored by GPT-4o).
ALTER TABLE eval_metrics
    ADD COLUMN IF NOT EXISTS judge_model VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_eval_metrics_judge ON eval_metrics (judge_model);
