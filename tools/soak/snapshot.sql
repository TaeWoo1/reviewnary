-- Read-only soak snapshot queries. Every row is `section|key|value`, so adding a measurement is adding
-- a SELECT and nothing else has to change.
--
-- Scoped to nothing: a soak runs one organisation, but a count that silently excluded a second one would
-- hide exactly the flood this is looking for. Where an org matters it is the key.

-- ── growth: the scheduler ────────────────────────────────────────────────────────────────────────────
select 'run', status, count(*)::text from responsibility_run group by status;
select 'run_source', coalesce(completeness, 'OPEN'), count(*)::text from responsibility_run_source
  group by coalesce(completeness, 'OPEN');
select 'run_source_method', method, count(*)::text from responsibility_run_source group by method;

-- ── growth: the jobs that put work on a seller's computer ────────────────────────────────────────────
select 'job', recipe || '/' || status || '/' || coalesce(outcome, '-'), count(*)::text
  from scheduled_aside_job group by recipe, status, outcome;
select 'job_trigger', trigger_source, count(*)::text from scheduled_aside_job group by trigger_source;
select 'walk', state || '/' || coalesce(stop_reason, '-'), count(*)::text
  from review_catch_up_run group by state, stop_reason;

-- ── growth: cases ────────────────────────────────────────────────────────────────────────────────────
-- responsibility_id separates the two producers that share this table: not null is the operations lane.
select 'case', case_kind || '/' || status, count(*)::text from proactive_case
  where responsibility_id is not null group by case_kind, status;
select 'case_reason', coalesce(reason, '-'), count(*)::text from proactive_case
  where responsibility_id is not null and status = 'PREPARED' group by reason;
select 'case_event', kind, count(*)::text from operations_case_event group by kind;
select 'legacy_case', status, count(*)::text from proactive_case where responsibility_id is null
  group by status;

-- ── growth: what was actually read ───────────────────────────────────────────────────────────────────
select 'collect', job_type || '/' || status, count(*)::text from sync_jobs group by job_type, status;
select 'rows', 'reviews', count(*)::text from reviews;
select 'rows', 'inquiries', count(*)::text from inquiries;

-- ── marketplace WRITE 0 ──────────────────────────────────────────────────────────────────────────────
-- Absolute counts over every table a send leaves a row in. A soak proves zero by these not moving; a
-- single new row anywhere here is the soak failing, whatever else it measured.
select 'write', 'inquiry_action_intent', count(*)::text from inquiry_action_intent;
select 'write', 'inquiry_approval', count(*)::text from inquiry_approval;
select 'write', 'inquiry_execution', count(*)::text from inquiry_execution;
select 'write', 'inquiry_verification', count(*)::text from inquiry_verification;
select 'write', 'review_reply_approval', count(*)::text from review_reply_approval;
select 'write', 'review_reply_approval_audit', count(*)::text from review_reply_approval_audit;
select 'write', 'review_reply_execution', count(*)::text from review_reply_execution;
select 'write', 'review_reply_outcome', count(*)::text from review_reply_outcome;
select 'write', 'review_reply_submission_ref', count(*)::text from review_reply_submission_ref;

-- ── invariants: every one of these must read 0 ───────────────────────────────────────────────────────
-- Each is a duplicate the product has a rule against, asked of the data rather than of the code: an
-- index that is right and a loop that writes twice look the same in a log and different here.
select 'invariant', 'open_cases_per_subject_over_1', coalesce(sum(n - 1), 0)::text from (
  select count(*) n from proactive_case where status = 'PREPARED'
  group by org_id, subject_kind, subject_id having count(*) > 1) d;
select 'invariant', 'live_jobs_per_device_over_1', coalesce(sum(n - 1), 0)::text from (
  select count(*) n from scheduled_aside_job where status in ('QUEUED', 'CLAIMED')
  group by device_id having count(*) > 1) d;
select 'invariant', 'live_walks_per_account_over_1', coalesce(sum(n - 1), 0)::text from (
  select count(*) n from review_catch_up_run where state in ('RUNNING', 'PAUSED_AUTH')
  group by seller_account_id, data_type having count(*) > 1) d;
-- A walk that is RUNNING with no child queued or claimed is waiting for a report that is not coming.
-- Nothing advances it and uq_review_catch_up_live stops any replacement: the standing lane is over.
select 'invariant', 'stranded_walks', count(*)::text from review_catch_up_run r
  where r.state = 'RUNNING' and not exists (
    select 1 from scheduled_aside_job j
    where j.catch_up_run_id = r.id and j.status in ('QUEUED', 'CLAIMED'));
-- Two runs of one responsibility holding a live lease. uq_responsibility_run_active makes this
-- impossible; it is asked anyway, because that is what an invariant check is for.
select 'invariant', 'concurrent_runs_per_responsibility', coalesce(sum(n - 1), 0)::text from (
  select count(*) n from responsibility_run where status = 'RUNNING'
  group by responsibility_id having count(*) > 1) d;
-- A window materialized twice.
select 'invariant', 'duplicate_windows', coalesce(sum(n - 1), 0)::text from (
  select count(*) n from responsibility_run group by responsibility_id, window_start
  having count(*) > 1) d;

-- ── recovery: where each standing lane is right now ─────────────────────────────────────────────────
-- The whole AUTH/device recovery proof reads from here across snapshots: a pause that is still the same
-- pause six hours later, with next_check_at in the past, is a lane that stopped probing.
select 'standing', data_type || '/' || enabled || '/' || coalesce(paused_reason, 'OK'),
       count(*)::text from review_auto_check where revoked_at is null
  group by data_type, enabled, paused_reason;
select 'standing_overdue', 'enabled_rows_past_due', count(*)::text from review_auto_check
  where revoked_at is null and enabled and next_check_at < now() - interval '90 minutes';
select 'freshness', 'minutes_since_last_settled_job',
       coalesce(round(extract(epoch from (now() - max(settled_at))) / 60)::text, '-')
  from scheduled_aside_job where status = 'SETTLED';
select 'freshness', 'minutes_since_last_finished_run',
       coalesce(round(extract(epoch from (now() - max(finished_at))) / 60)::text, '-')
  from responsibility_run where finished_at is not null;
