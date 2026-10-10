-- Learning & Outcome Loop v1, package A — the snapshot MANIFEST (2026-10-10)
--
-- `TriageFeedbackService.disposition` / `freezeSnapshot` / `freezeSilverSnapshot` have existed and been tested
-- since the triage feedback spine shipped, and until today **nothing in `main/` called any of them**: no
-- controller, no runner, no schedule. So every correction a seller ever made sat un-dispositioned, no snapshot
-- was ever cut, and `snapshot_version` was stamped by test code only. The design in
-- `docs/slices/production-triage-feedback-draft-v1.md` §3 was complete and had no door.
--
-- This table is the door's record. Three things were unsayable without it:
--
--   1. **That a snapshot exists.** The only trace of a cut was the version string stamped onto member rows, so
--      «what has been cut» could only be answered by scanning three tables and grouping, and a cut that stamped
--      zero rows left no trace at all.
--   2. **That a version is used.** `freezeSnapshot(orgId, "silver/1")` took the version as a free string.
--      Re-using one silently MERGED two cuts into one name, and §3 is explicit that an evaluation set which
--      changes under a metric makes the metric meaningless. The unique index below is that sentence as a
--      constraint.
--   3. **That the two kinds never share a name.** §7.4: «a silver snapshot may never be merged into a correction
--      snapshot». The uniqueness is deliberately over (org, version) and NOT over (org, kind, version) — one
--      version string names one cut of one kind, so `silver/3` and a correction `silver/3` cannot coexist and
--      be mistaken for the same set later.
--
-- Deliberately NOT here: the rows. A member row is identified by the stamp it already carries
-- (`review_correction_dispositions.snapshot_version`, `review_triage_actions.snapshot_version`,
-- `review_triage_behavior_events.snapshot_version`). A membership table would be a second copy of the same
-- fact, and the copy is what drifts.
--
-- Deliberately NOT here: anything a model could read. Nothing in this schema is consulted at classification
-- time; `SellerFeedbackCorpusFenceTest` asserts that the package which reads it cannot reach the classifier,
-- the prompt, or the stored tier.
create table triage_feedback_snapshot (
    id                 uuid         primary key,
    org_id             uuid         not null references organizations (id) on delete cascade,

    -- CORRECTION (dispositioned CLASSIFIER_ERROR rows — strong evidence, §3) or SILVER (actions and behaviour,
    -- §7.4). Closed in code (`SnapshotKind`), stored as text like every other vocabulary in this schema.
    kind               text         not null,

    -- The cutter's own version string, quoted wherever the set is used. Free-form on purpose: it is a label a
    -- human writes in a decision record, not a sequence this table owns.
    version            text         not null,

    -- How many rows the cut took. A cut that would take zero is refused rather than recorded, so this is always
    -- positive: a manifest row saying 0 would be a named evaluation set with nothing in it.
    row_count          integer      not null,

    -- What the classifier was when the cut was made, for a CORRECTION cut — the thing the set will be used to
    -- measure a successor against. Null for SILVER (behaviour is not a classifier's answer) and null when no
    -- prediction underlies any member row (a rule-only correction set, which the pilot produces).
    classifier_version text,
    prompt_hash        text,

    cut_by             uuid,
    cut_at             timestamptz  not null,
    -- The cutter's own sentence about why this cut exists. Operator-authored; never customer content.
    note               text,

    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,

    constraint ck_triage_feedback_snapshot_kind  check (kind in ('CORRECTION', 'SILVER')),
    constraint ck_triage_feedback_snapshot_rows  check (row_count > 0)
);

-- One version string names ONE cut, in this org, across both kinds. See (2) and (3) above.
create unique index uq_triage_feedback_snapshot_version
    on triage_feedback_snapshot (org_id, version);

create index ix_triage_feedback_snapshot_org_kind
    on triage_feedback_snapshot (org_id, kind, cut_at desc);

-- A snapshot is cut, numbered, and never reopened (§3). Enforced here and not only in the service, because the
-- property is about the row rather than about the path that wrote it.
create or replace function triage_feedback_snapshot_append_only() returns trigger as $$
begin
    raise exception 'triage_feedback_snapshot is append-only: a cut snapshot is never reopened';
end;
$$ language plpgsql;

drop trigger if exists trg_triage_feedback_snapshot_append_only on triage_feedback_snapshot;
create trigger trg_triage_feedback_snapshot_append_only
    before update or delete on triage_feedback_snapshot
    for each row execute function triage_feedback_snapshot_append_only();

comment on table triage_feedback_snapshot is
    'Manifest of one frozen triage-feedback snapshot (Learning & Outcome Loop v1 package A). Append-only; member rows carry the version stamp.';
