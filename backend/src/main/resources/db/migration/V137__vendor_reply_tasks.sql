-- 판매자 PC 쪽 답글 대행 작업의 사본 — docs/vendor_server_contract_v1.md §4. 상태의 정본은 공급자 서버이고, 이 표는
-- PC가 tasks/changes로 받아 둔 마지막 모습이다. 열린 작업이 있는 리뷰에는 이 PC의 승인 · 가이드 등록 길이 닫힌다.
--
-- vendor_review_ref.no_reply_reported_at: 판매자가 이 PC에서 '답글 안 함'(분류 NO_ACTION, 사람이 정한 것만)으로
-- 정한 리뷰를 서버에 알린 시각(2026-10-21 오너 결정 — AI 판정은 보내지 않는다).
alter table vendor_review_ref add column no_reply_reported_at timestamptz;

alter table vendor_store_binding add column task_cursor bigint not null default 0;

create table vendor_reply_task (
    id                    uuid primary key,
    org_id                uuid        not null references organizations (id),
    review_id             uuid        not null references reviews (id) on delete cascade,
    vendor_task_id        uuid        not null unique,
    vendor_store_id       uuid        not null references vendor_store_binding (vendor_store_id),
    state                 varchar(20) not null,
    state_version         integer     not null,
    draft_version         integer     not null,
    draft_fingerprint     char(64)    not null,
    approved_fingerprint  char(64),
    edited_on_mobile      boolean     not null,
    outcome_recorded_at   timestamptz,
    created_at            timestamptz not null,
    updated_at            timestamptz not null
);

create index idx_vendor_reply_task_review on vendor_reply_task (org_id, review_id);
