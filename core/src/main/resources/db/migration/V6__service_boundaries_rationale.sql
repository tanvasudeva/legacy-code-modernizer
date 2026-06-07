-- Adds architect-agent rationale to service_boundaries rows.
ALTER TABLE service_boundaries ADD COLUMN IF NOT EXISTS rationale TEXT;
