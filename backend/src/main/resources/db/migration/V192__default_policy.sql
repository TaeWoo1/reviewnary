-- 매뉴얼 없는 판매자의 기본 설정 (오너 결정 2026-11-17, demo_seller_validation_v1 §7.0).
-- 판매자가 웹 질문지에 답을 적고(작성 중) 항목마다 확인을 누르면(확정) 그 항목만 가게 기준이 된다.
-- 확정 값은 주제별 가게 기준 문서(org_knowledge_sources)로 다시 쓰이고, 어떤 문서가 어느 묶음인지는
-- 아래 두 번째 표가 기억한다. 출고 마감·휴무는 여기 없다 -- 톡톡 배송 기준(talktalk_shipping_guidance)
-- 한 곳에만 있고, 이 기능은 그 값을 읽어 문서에 함께 적는다.

create table org_default_policy_answers (
    id                uuid primary key,
    org_id            uuid          not null references organizations (id),
    item_key          varchar(40)   not null,
    draft_number      integer,
    draft_text        varchar(300),
    confirmed_number  integer,
    confirmed_text    varchar(300),
    confirmed         boolean       not null default false,
    updated_by        uuid,
    confirmed_by      uuid,
    confirmed_at      timestamptz,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    constraint uq_org_default_policy_answer unique (org_id, item_key)
);

create table org_default_policy_sources (
    id          uuid primary key,
    org_id      uuid         not null references organizations (id),
    group_key   varchar(20)  not null,
    source_id   uuid         not null references org_knowledge_sources (id) on delete cascade,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    constraint uq_org_default_policy_source unique (org_id, group_key)
);
