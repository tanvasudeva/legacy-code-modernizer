-- Phase 4.7 — Compilation repair tracking.
-- repair_attempts: how many LLM repair iterations were needed (0 = first attempt succeeded or repair not yet run)
-- first_attempt_compiled: whether the very first LLM-generated code compiled without any repair
ALTER TABLE agent_tasks ADD COLUMN IF NOT EXISTS repair_attempts       INT     DEFAULT 0;
ALTER TABLE agent_tasks ADD COLUMN IF NOT EXISTS first_attempt_compiled BOOLEAN;
