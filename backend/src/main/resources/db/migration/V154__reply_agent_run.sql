-- 답글 에이전트 실행 하나 — 그리고 그 실행에만 쓰는 토큰의 해시 (product-owner 2026-10-28, docs/standing/reply_agent_v1.md §2).
-- 토큰은 실행 하나 · org 하나 · 리뷰나 문의 하나에 묶이고, 몇 분 뒤 만료되며, 실행이 끝나면(finished_at) 더 쓰이지 않는다.
-- 토큰 원문은 저장하지 않는다 — 해시만.
create table reply_agent_run (
    id            uuid primary key,
    org_id        uuid         not null references organizations(id),
    subject_kind  varchar(16)  not null,
    subject_id    uuid         not null,
    trigger_kind  varchar(16)  not null,
    -- 판매자가 누른 실행의 그 판매자. 판매자 없는 실행은 비어 있다.
    started_by    uuid,
    token_hash    varchar(128) not null,
    expires_at    timestamptz  not null,
    finished_at   timestamptz,
    outcome       varchar(24),
    -- 실행 중의 작업 상태. 모델 단계 사이를 runtime이 아니라 백엔드가 들고 있어, 단계의 입력이 언제나 백엔드가 만든 것이다.
    category      varchar(2),
    working_draft text,
    failed_rules  varchar(200),
    critique_note text,
    revisions     integer      not null default 0,
    draft_version integer,
    handoff_reason varchar(40),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint uq_reply_agent_run_token unique (token_hash),
    constraint ck_reply_agent_run_subject check (subject_kind in ('REVIEW', 'INQUIRY')),
    constraint ck_reply_agent_run_trigger check (trigger_kind in ('SELLER', 'INGEST', 'SCHEDULED'))
);
create index ix_reply_agent_run_org_created on reply_agent_run (org_id, created_at desc);
