-- 2026-11-01, fourth review: a run's small evidence bundle keeps room for the store's policies, and records how many
-- in-use passages it had no room for — a draft written without all of them says so.
ALTER TABLE reply_agent_run ADD COLUMN bundle_policies_left integer NOT NULL DEFAULT 0;
ALTER TABLE reply_agent_run ADD COLUMN bundle_product_left integer NOT NULL DEFAULT 0;
