-- The local agent stops being a Responsibility-only feature.
--
-- The recipe, the bound workflow, the store fence and the canonical ingest were always general. The DOOR was
-- not: the only producer of a job row was a responsibility run, so the only way to read a seller's own channel
-- screen was to let an unattended lane do it on a schedule — and a seller pressing 지금 수집 on their own store
-- had no path at all. Three columns open the second door without widening what a job may say.
--
--   trigger_source     who asked, so the audit trail can answer «was a human looking at this?». An OPERATOR row
--                      is authorised by the press itself; a RESPONSIBILITY row by the deployment having named
--                      the organisation AND the account in advance. Both were always true; only one was recorded.
--   seller_account_id  which store this job reads, as a fact ON the row. It had been re-derived at claim and
--                      again at delivery, each time through the deployment allow-list — which is exactly why a
--                      seller-pressed read was inexpressible: the account was never the caller's to state.
--   max_pages          the bound, where the database can refuse anything else.
--
-- What does NOT change: still no column for a URL, a prompt, a script, a credential or a target. Still
-- single-use, leased, TTL-bounded, one live job per device, recipe allow-listed by CHECK. Still read-only —
-- every published recipe opens a route, reads a model and closes, with zero clicks, keystrokes, downloads,
-- marketplace writes and model calls.

alter table scheduled_aside_job
    add column trigger_source    varchar(16),
    add column seller_account_id uuid references seller_accounts (id) on delete cascade,
    add column max_pages         integer not null default 1;

-- Every row that exists was queued by a responsibility run, because nothing else could queue one.
update scheduled_aside_job set trigger_source = 'RESPONSIBILITY' where trigger_source is null;

alter table scheduled_aside_job
    alter column trigger_source set not null;

alter table scheduled_aside_job
    add constraint chk_scheduled_aside_job_trigger
        check (trigger_source in ('OPERATOR', 'RESPONSIBILITY'));

-- A press names a store and belongs to no run. Stated here because it is the shape of the lane, not a
-- convention: a row claiming both a run and a press could not be read as evidence of either.
alter table scheduled_aside_job
    add constraint chk_scheduled_aside_job_operator
        check (trigger_source <> 'OPERATOR' or (seller_account_id is not null and run_id is null));

-- One page, and the day a recipe can turn one, THIS constraint is the migration that says so out loud.
alter table scheduled_aside_job
    add constraint chk_scheduled_aside_job_pages
        check (max_pages = 1);

comment on column scheduled_aside_job.trigger_source is
    'Who asked: OPERATOR (a seller pressed 지금 수집 on their own account — the press is the authorisation) or RESPONSIBILITY (an unattended run; the deployment must have named the org and the account).';
comment on column scheduled_aside_job.seller_account_id is
    'Which of the organisation''s stores this job reads, decided at queue time. Null only on rows written before this column and on the loopback recipe, which reads a surface we serve ourselves.';
comment on column scheduled_aside_job.max_pages is
    'The read''s page bound, carried on the row. Constrained to 1: no published recipe can turn a page.';
