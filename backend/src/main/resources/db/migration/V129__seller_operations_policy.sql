-- Seller-declared Operations Policy v1 (2026-10-10)
--
-- «앞으로 같은 문제도 이렇게 처리» — the seller's own standing rule for one repeated problem, declared explicitly and
-- read by the DETERMINISTIC case layer. It is the first asset in this repository where something a seller typed
-- changes what a judgement concludes, so every column below is a fence as much as a field.
--
-- ── WHAT IT IS NOT ──────────────────────────────────────────────────────────────────────────────────────────────
--
-- NOT a promotion of a correction, a guidance or a memory. `seller_guidance` keeps its exact meaning («다음에도
-- 참고» — context a later draft or investigation may be SHOWN, never a rule); `review_triage_corrections` keeps
-- its; `answer_memory` keeps its. Nothing here is written by any of them, and nothing reads them to synthesise a
-- policy. A policy row exists only because the seller chose this action for this problem on a screen that says so.
-- The write path has no argument that could carry a correction id.
--
-- NOT a triage rule. `ReviewTriageRules.tier` and its SQL twin `ReviewRepository.TRIAGE_TIER_RANK` are untouched:
-- a policy cannot move a review between 확인 필요 / 지켜보기 / 참고, cannot change a queue's ordering or counts, and
-- cannot reach `review.reply_state`. The go/no-go in contracts/review-eval/naver/v1/RUBRIC.md §5 is not involved.
--
-- NOT an evidence-priority rule. `KnowledgeAuthority` ranks are untouched. This is also already forbidden from the
-- other direction: `AnswerStyleSafetyFloor.CURRENT_EVIDENCE_PRIORITY` refuses a seller instruction that reorders
-- evidence, and `OperationsPolicyFence` refuses one here for the same reason.
--
-- It sets ONE field: `proactive_case.recommended_action_type` — what to do about work that still comes to the
-- seller. See `OperationsPolicyFence` for the five things it may not reach.
--
-- ── THE KEY ─────────────────────────────────────────────────────────────────────────────────────────────────────
--
-- `aspect` × `problem`, the closed `IssueVocabulary` the issue memory already runs on, and `signature_key` is
-- exactly `review_issues.signature_key` (`IssueSignature.signatureKey()`, e.g. 배송:지연) — derived, not a second
-- identity. That is why «같은 문제» means the same thing on the policy screen as on the repeated-problem screen:
-- there is one vocabulary and one key, and matching a case to a policy is an indexed lookup over rows the
-- extraction after every ingest already wrote.

create table seller_operations_policy (
    id            uuid         primary key,
    org_id        uuid         not null references organizations (id) on delete cascade,

    -- ORG or PRODUCT, and the seller SAID which. The check makes the pair inseparable, which is the point:
    -- a PRODUCT policy with no product would be an org-wide rule wearing a product's label, and that widening is
    -- exactly what the service refuses rather than performs (`OperationsPolicyService.declare`). Before this,
    -- `seller_guidance` silently fell back to ORG when the case's product had no operator name — a seller who
    -- chose 「이 상품만」 got a company-wide row and was never told.
    product_id    uuid         references products (id) on delete cascade,
    scope         varchar(16)  not null,

    -- The closed IssueVocabulary pair, stored readable for the same reason `review_issues` stores it readable:
    -- both components come from a closed vocabulary and carry no customer content, so hashing buys no privacy
    -- while making the unique index, a failing test and a support question all harder to read.
    aspect        varchar(40)  not null,
    problem       varchar(40)  not null,
    signature_key varchar(64)  not null,

    -- One of RecommendedActionType, and HUMAN-authority values only (the fence refuses the two AUTO ones). The
    -- policy says WHAT to do about work the seller still has to carry; it can never decide that nobody needs to.
    action        varchar(32)  not null,

    -- The seller's own sentence about why. Optional, capped at write time, and DATA everywhere downstream: it is
    -- shown beside the recommendation and is never concatenated into a prompt's rule section, never parsed for
    -- facts, never matched on.
    note          text,

    -- Which revision this is. Bumped only when the action or the note changes, exactly like
    -- `org_knowledge_sources.version`: a case decided last month must be readable against the policy revision it
    -- was decided under, and a version that moved on a no-op edit cannot do that.
    version       integer      not null default 1,

    -- Retiring, not deleting — the lifecycle `org_knowledge_sources` has and `seller_guidance` never got. A
    -- retired policy stops deciding new cases and stays answerable for the ones it decided.
    active        boolean      not null default true,

    declared_by   uuid,
    declared_at   timestamptz  not null,
    retired_by    uuid,
    retired_at    timestamptz,

    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,

    constraint ck_seller_operations_policy_scope check (scope in ('ORG', 'PRODUCT')),
    -- The scope and the product are one fact stated twice; neither half may stand alone.
    constraint ck_seller_operations_policy_scope_shape check (
        (scope = 'PRODUCT' and product_id is not null) or (scope = 'ORG' and product_id is null)),
    -- Only a retired row carries a retirement, and every retired row carries its time.
    constraint ck_seller_operations_policy_retired check (
        (active = true and retired_at is null) or (active = false and retired_at is not null))
);

-- One live policy per (scope, key). Two partial indexes rather than one, because Postgres treats NULLs as
-- distinct: a single UNIQUE over (org_id, product_id, signature_key) would let an org accumulate unlimited
-- contradictory ORG rows for 배송:지연, and «which of my rules applies» would have no answer.
create unique index uq_seller_operations_policy_org
    on seller_operations_policy (org_id, signature_key)
    where product_id is null and active;
create unique index uq_seller_operations_policy_product
    on seller_operations_policy (org_id, product_id, signature_key)
    where product_id is not null and active;

-- The overlay's own read: every standing policy for one org, narrowed by key in code so one query serves a page
-- of cases. Bounded by the vocabulary — aspects × problems is a closed, small product.
create index idx_seller_operations_policy_lookup
    on seller_operations_policy (org_id, signature_key, active);

comment on table seller_operations_policy is
    'Seller-declared standing rule for one aspect:problem — the handling to recommend. Never written from a correction, a guidance or a memory.';
comment on column seller_operations_policy.action is
    'RecommendedActionType, HUMAN authority only. A policy chooses the handling; it never decides that no human is needed.';

-- ── The trail ───────────────────────────────────────────────────────────────────────────────────────────────────
--
-- Append-only, one row per thing the seller did — the third instance of the pattern V99 named
-- (`review_reply_approval_audit`, `review_triage_correction_audit`), not a fourth pattern and not a generic event
-- platform. `action_from` is null on a DECLARED row because there was no standing policy to leave, and on a
-- RETIRED row `action_to` is null because a retirement states no action. Enforced by the same trigger shape
-- `operations_case_event` uses, so «append-only» is a property of the database and not of the writer's manners.
create table seller_operations_policy_audit (
    id            uuid         primary key,
    org_id        uuid         not null references organizations (id) on delete cascade,
    policy_id     uuid         not null references seller_operations_policy (id) on delete cascade,
    kind          varchar(16)  not null,
    action_from   varchar(32),
    action_to     varchar(32),
    version_to    integer      not null,
    actor_id      uuid,
    decided_at    timestamptz  not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint ck_seller_operations_policy_audit_kind check (
        (kind = 'DECLARED' and action_to is not null and action_from is null)
        or (kind = 'CHANGED' and action_to is not null)
        or (kind = 'RETIRED' and action_to is null))
);

create index idx_seller_operations_policy_audit_policy
    on seller_operations_policy_audit (policy_id, decided_at);

create or replace function seller_operations_policy_audit_append_only() returns trigger as $$
begin
    raise exception 'seller_operations_policy_audit is append-only';
end;
$$ language plpgsql;

drop trigger if exists trg_seller_operations_policy_audit_append_only on seller_operations_policy_audit;
create trigger trg_seller_operations_policy_audit_append_only
    before update or delete on seller_operations_policy_audit
    for each row execute function seller_operations_policy_audit_append_only();

comment on table seller_operations_policy_audit is
    'Append-only trail of the seller''s own policy decisions. A retirement does not delete what a policy once decided.';

-- ── decided_by gains its third value ───────────────────────────────────────────────────────────────────────────
--
-- V107 wrote `check (decided_by in ('RULE', 'AGENT'))`, and that two-value shape is what made «the seller's own
-- standing rule decided this» unsayable: a case the seller pre-decided read as RULE, indistinguishable from one
-- the rating settled. SELLER is the third and last layer, and it sits between them in authority — it speaks after
-- the rule (which it may not contradict: a rule that already settled a case is not handed to the overlay) and
-- before any model (which it replaces, saving the call).
--
-- `varchar(8)` already fits. Nothing is backfilled: every existing row was decided by the rule or the agent, and
-- restamping any of them SELLER would attribute a decision to a person who never made one.
alter table proactive_case drop constraint if exists ck_proactive_case_decider;
alter table proactive_case add constraint ck_proactive_case_decider check (
    decided_by is null or decided_by in ('RULE', 'AGENT', 'SELLER'));
