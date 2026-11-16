-- 자료끼리 값이 다를 때 무엇이 현행인지 판매자가 정해 두는 곳, 그리고 입고·출고·생산 일정 (2026-11-15, 충돌 2~5단계).
--
-- 업로드 시각이나 마지막 저장 시각으로는 현행을 정하지 않는다(나중에 올린 옛 공지가 이기던 결함). 판매자가 정한 두 가지만 쓴다.
--   knowledge_source_currency  자료 하나의 적용일(언제부터). 빈칸이면 판단에 쓰지 않는다. 자료 행 자체는 고치지 않는다 —
--                              자료의 저장 시각이 움직이면 그 자료를 인용한 답변이 「근거가 바뀜」으로 잡히기 때문이다.
--   knowledge_supersession     「이 자료가 저 자료를 대신함」, 항목(속성)과 조건 하나에만. 같은 문서의 다른 항목은 그대로 남는다.
--   knowledge_schedule         입고·출고·생산 일정 한 줄: 상품·옵션, 종류, 예정/확정, 날짜, 대상·범위, 마지막 확인일.
--
-- 원천 자료 id는 상품 자료(product_knowledge_source)나 회사 자료(org_knowledge_source) 어느 쪽이든 될 수 있어 외래 키를 두지 않는다.
-- 자료를 지워도 이 행은 남고, 현행 판단은 읽을 때 살아 있는 자료만 본다.

create table knowledge_source_currency (
    id              uuid primary key,
    org_id          uuid        not null references organizations (id),
    source_id       uuid        not null,
    effective_from  date,
    updated_by_name varchar(100),
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    constraint uq_knowledge_source_currency unique (org_id, source_id)
);

create table knowledge_supersession (
    id               uuid primary key,
    org_id           uuid        not null references organizations (id),
    newer_source_id  uuid        not null,
    older_source_id  uuid        not null,
    attribute        varchar(40) not null,
    claim_condition  varchar(40) not null default 'GENERAL',
    created_by_name  varchar(100),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    constraint ck_knowledge_supersession_pair check (newer_source_id <> older_source_id),
    constraint ck_knowledge_supersession_attribute check (attribute in ('RETURN_WINDOW', 'EXCHANGE_WINDOW',
        'RETURN_SHIPPING_FEE', 'SHIPPING_FEE', 'FREE_SHIPPING_THRESHOLD', 'DISPATCH_CUTOFF', 'DELIVERY_DAYS',
        'PRODUCTION_DAYS')),
    constraint ck_knowledge_supersession_condition check (claim_condition in ('GENERAL', 'CHANGE_OF_MIND', 'DEFECT',
        'REMOTE_AREA')),
    constraint uq_knowledge_supersession unique (org_id, newer_source_id, older_source_id, attribute, claim_condition)
);
create index ix_knowledge_supersession_org on knowledge_supersession (org_id);

create table knowledge_schedule (
    id              uuid primary key,
    org_id          uuid         not null references organizations (id),
    product_id      uuid references products (id),
    option_name     varchar(200),
    kind            varchar(20)  not null,
    status          varchar(20)  not null,
    schedule_date   date,
    scope_note      varchar(300),
    last_checked_on date,
    active          boolean      not null default true,
    author_name     varchar(100),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint ck_knowledge_schedule_kind check (kind in ('INBOUND', 'OUTBOUND', 'PRODUCTION')),
    constraint ck_knowledge_schedule_status check (status in ('PLANNED', 'CONFIRMED'))
);
create index ix_knowledge_schedule_org_product on knowledge_schedule (org_id, product_id);

-- 초안이 근거로 쓴 자료가 그때 충돌·대체 상태였는지를 초안과 함께 남긴다(설계 2절 ⑤). 화면은 지금 상태를 다시 계산해 보이고,
-- 이 칸은 「그때 무엇을 보고 썼나」의 기록이다. 기존 행은 비어 있다.
alter table inquiry_draft_evidence add column knowledge_state varchar(500);

comment on column knowledge_source_currency.effective_from is '판매자가 적은 적용일(언제부터). 빈칸이면 현행 판단에 쓰지 않는다.';
comment on column knowledge_supersession.attribute is '대체가 적용되는 항목 하나. 같은 문서의 다른 항목은 대체하지 않는다.';
comment on column knowledge_schedule.status is 'PLANNED 예정(바뀔 수 있음) · CONFIRMED 확정. 확정 입고도 개별 주문의 출고·배송 사실이 아니다.';
comment on column inquiry_draft_evidence.knowledge_state is '초안을 쓸 때 이 근거에 붙어 있던 충돌·대체 표시(없으면 비어 있음).';
