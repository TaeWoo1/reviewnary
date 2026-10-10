-- Learning & Outcome Loop v1, package B — 적용, and what happened afterwards (2026-10-10)
--
-- ── WHAT WAS BROKEN ─────────────────────────────────────────────────────────────────────────────────────────────
--
-- Two ledgers about the same remediation, and neither knew the other existed.
--
--   `improvement_opportunity`  : ACCEPTED / DISMISSED — the seller's decision about a SUGGESTION.
--   `review_issues.lifecycle_state`: OBSERVING → ACTING → VERIFYING → RESOLVED — the seller's record about the
--                                    PROBLEM.
--
-- Accepting an opportunity moved no issue; moving an issue mentioned no opportunity. And 적용 — the seller
-- actually saving the prepared draft into their own library — was recorded NOWHERE: `OpportunityCard` called
-- `createOrgKnowledge` / `createProductKnowledgeSource` straight from the browser and the opportunity row never
-- learned it had been carried out. So the chain 반복 문제 → 제안 → 채택 → 적용 → 관찰 → 결과 broke twice: once at
-- 적용, which nothing stored, and once at 결과, which nothing measured.
--
-- ── WHY AN OUTCOME IS A ROW AND NOT A JUDGEMENT ─────────────────────────────────────────────────────────────────
--
-- `IssueChangeKind.IMPROVED` already exists and is derived on every read from a SLIDING window — the trailing 4
-- weeks against the 4 before them, evaluated against today. That answers «is this declining now». It cannot
-- answer «did what we did on 9월 12일 work», because by the time 해결됨 arrives (4 quiet weeks after remediation)
-- the baseline the remediation would have been measured against has slid out of the window entirely.
--
-- An outcome therefore needs an ANCHOR, and the anchor has to be frozen at the moment of 적용: the day, the
-- window, the evidence count before it, and — the part that decides whether the whole measurement means
-- anything — how many reviews were coming in at all. A 4-week silence after a remediation is improvement only
-- if reviews kept arriving; otherwise it is a collection gap wearing a success report, which is the exact
-- failure `docs/slices/attention-coverage-false-calm-v1.md` is about.
--
-- ── WHAT AN OUTCOME IS NOT ──────────────────────────────────────────────────────────────────────────────────────
--
-- NOT a claim of causation. `verdict` says what the evidence did in a named window after a named act; nothing
-- here says the act caused it. The vocabulary (`OutcomeVerdict`) has 판단 보류 for a reason and uses it often.
-- NOT a rule, a policy or a guidance. Nothing in this schema is read by `ReviewTriageRules`, by
-- `seller_operations_policy`, or by any deterministic case decision; an outcome reaches the next judgement only
-- as EVIDENCE an investigation may cite (package C), which is where a fact about the past belongs.
-- NOT a trigger. No row here moves an issue, closes a case, or writes a seller policy.

-- ── 적용: the thing the seller did, recorded where the decision was ─────────────────────────────────────────────
--
-- On the opportunity row rather than in a new table, because it is the same subject the other three decisions
-- are about and `improvement_opportunity_event` already carries the trail. `status` gains APPLIED; the three
-- columns below say when, by whom, and what it landed in.
alter table improvement_opportunity add column applied_at timestamptz;
alter table improvement_opportunity add column applied_by uuid;

-- Where the prepared text actually went, when it went somewhere this product can name: the id of the org
-- knowledge row or the product knowledge source the seller saved. Null for a draft the seller carried off on
-- their clipboard — and that null is load-bearing: an application with an artifact is a change this product can
-- point at, and one without it is the seller's own word. No FK, deliberately: the memory of what was applied
-- must outlive the row being retired, the same reason `answer_memory.origin_review_id` carries none.
alter table improvement_opportunity add column applied_ref uuid;
-- ORG_KNOWLEDGE | PRODUCT_KNOWLEDGE | SELLER_DECLARED. Closed in code (`AppliedArtifact`).
alter table improvement_opportunity add column applied_ref_kind text;

alter table improvement_opportunity drop constraint if exists ck_improvement_opportunity_applied;
alter table improvement_opportunity add constraint ck_improvement_opportunity_applied check (
    -- 적용 is a dated act or it did not happen. A status of APPLIED with no instant would be a claim with no
    -- moment, and an instant under any other status would be a leftover from a decision later taken back.
    (status = 'APPLIED') = (applied_at is not null)
    and (applied_ref is null or applied_ref_kind is not null)
);

comment on column improvement_opportunity.applied_at is
    'When the seller carried the prepared action out. Set iff status = APPLIED (Learning & Outcome Loop v1).';

-- The trail gains its fifth word. V103 pinned the vocabulary in a check constraint, and APPLIED belongs in it
-- for the same reason the other four do: it is a thing the seller DID, not something that happened to the
-- problem. `OpportunityEvent`'s own javadoc draws that line — «these are decisions, not outcomes» — and 적용 is
-- on the decision side: it says the seller carried the prepared action out, and says nothing about whether it
-- worked. What happened afterwards is `improvement_outcome`, below, and it is a different table precisely so
-- the two can never be read as one sentence.
alter table improvement_opportunity_event drop constraint if exists ck_improvement_opportunity_event;
alter table improvement_opportunity_event add constraint ck_improvement_opportunity_event
    check (event in ('ACCEPTED', 'EDITED', 'DISMISSED', 'REOPENED', 'APPLIED'));

-- ── 결과: one anchored measurement per applied opportunity ───────────────────────────────────────────────────────
create table improvement_outcome (
    id                 uuid        primary key,
    org_id             uuid        not null references organizations (id) on delete cascade,
    -- One outcome per application. The unique index is the statement: a second measurement of the same act
    -- under a different window is how two numbers about one remediation come to disagree.
    opportunity_id     uuid        not null references improvement_opportunity (id) on delete cascade,
    issue_id           uuid        not null references review_issues (id) on delete cascade,

    -- WHICH population the evidence is counted in. An ORG-scoped remediation (a shipping rule) is measured
    -- against the company's reviews; a PRODUCT-scoped one (a detail-page note) against that product's. Counting
    -- a product's fix against the whole company dilutes it into 변화 없음; counting a company rule against one
    -- product credits it with a change it may not have made.
    scope              text        not null,
    product_id         uuid        references products (id) on delete cascade,

    -- ── frozen at 적용, never updated (trigger below) ──
    applied_on         date        not null,
    baseline_from      date        not null,
    baseline_to        date        not null,
    baseline_evidence  integer     not null,
    -- How many reviews arrived in the baseline window at all. The denominator that makes a later silence
    -- readable: without it, «0 complaints» and «0 reviews» are the same number.
    baseline_reviews   integer     not null,
    observe_days       integer     not null,

    -- ── written once, when the window closes ──
    observed_evidence  integer,
    observed_reviews   integer,
    observed_through   date,
    verdict            text        not null,
    reason             text        not null,
    evaluated_at       timestamptz,

    created_at         timestamptz not null,
    updated_at         timestamptz not null,

    constraint ck_improvement_outcome_scope check (
        (scope = 'PRODUCT' and product_id is not null) or (scope = 'ORG' and product_id is null)),
    constraint ck_improvement_outcome_window check (baseline_from <= baseline_to and observe_days > 0),
    constraint ck_improvement_outcome_counts check (
        baseline_evidence >= 0 and baseline_reviews >= 0
        and (observed_evidence is null or observed_evidence >= 0)
        and (observed_reviews is null or observed_reviews >= 0)),
    constraint ck_improvement_outcome_verdict check (
        verdict in ('OBSERVING', 'IMPROVED', 'UNCHANGED', 'WORSENED', 'INCONCLUSIVE')),
    -- A settled verdict names the window it was settled on. OBSERVING is the only one that may stand with
    -- nothing observed yet.
    constraint ck_improvement_outcome_settled check (
        (verdict = 'OBSERVING') = (evaluated_at is null))
);

create unique index uq_improvement_outcome_opportunity
    on improvement_outcome (opportunity_id);

create index ix_improvement_outcome_org_issue
    on improvement_outcome (org_id, issue_id);

-- The two reads that exist: «which windows are due» (the automatic pass) and «what did this product's
-- remediations do» (an investigation's evidence).
create index ix_improvement_outcome_due
    on improvement_outcome (org_id, verdict, applied_on);
create index ix_improvement_outcome_org_product
    on improvement_outcome (org_id, product_id, applied_on desc);

-- ── the anchor cannot move ──────────────────────────────────────────────────────────────────────────────────────
--
-- Six columns are the measurement's own premise. A baseline edited after the fact turns any number into
-- whatever the editor wanted it to be, and nothing downstream could tell. Refused at the database rather than
-- only in the service, because the property belongs to the row and not to the path that reached it — the same
-- reason `triage_feedback_snapshot` is append-only and `seller_operations_policy_audit` is.
create or replace function improvement_outcome_anchor_frozen() returns trigger as $$
begin
    if new.applied_on is distinct from old.applied_on
        or new.baseline_from is distinct from old.baseline_from
        or new.baseline_to is distinct from old.baseline_to
        or new.baseline_evidence is distinct from old.baseline_evidence
        or new.baseline_reviews is distinct from old.baseline_reviews
        or new.observe_days is distinct from old.observe_days
        or new.opportunity_id is distinct from old.opportunity_id
        or new.scope is distinct from old.scope
        or new.product_id is distinct from old.product_id then
        raise exception 'improvement_outcome anchor is frozen: a baseline edited after the fact measures nothing';
    end if;
    return new;
end;
$$ language plpgsql;

drop trigger if exists trg_improvement_outcome_anchor_frozen on improvement_outcome;
create trigger trg_improvement_outcome_anchor_frozen
    before update on improvement_outcome
    for each row execute function improvement_outcome_anchor_frozen();

comment on table improvement_outcome is
    'One anchored measurement of what happened after the seller applied one improvement (Learning & Outcome Loop v1 package B). Says what the evidence did; never that the act caused it.';
