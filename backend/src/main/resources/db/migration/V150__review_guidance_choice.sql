-- 「이 리뷰에 맞는 안내」 — 판매자가 리뷰 하나에 대해 안내를 확인하거나, 고르거나, 뺀 기록 (2026-10-27).
-- 고객 표현이 맞아 저절로 붙은 안내는 판매자가 「맞아요」(CONFIRMED) 하기 전까지 후보이고, 판매자가 목록에서 고른 안내는
-- PICKED, 「아니에요」는 REJECTED — 뺀 안내는 그 리뷰의 초안에 다시 쓰이지 않는다. 한 리뷰 · 한 안내에 한 줄.
-- 이 기록은 다음 평가의 정답이기도 하다: 사람이 맞다고 한 안내와 리뷰의 짝.
create table review_guidance_choice (
    id              uuid primary key,
    org_id          uuid         not null,
    review_id       uuid         not null,
    source_scope    varchar(16)  not null,
    source_id       uuid         not null,
    choice          varchar(16)  not null,
    matched_phrase  varchar(200),
    decided_by      uuid,
    decided_by_name varchar(120),
    decided_at      timestamptz  not null,
    constraint uq_review_guidance_choice unique (org_id, review_id, source_id),
    constraint ck_review_guidance_choice_scope check (source_scope in ('PRODUCT', 'ORG')),
    constraint ck_review_guidance_choice_choice check (choice in ('CONFIRMED', 'PICKED', 'REJECTED'))
);
create index ix_review_guidance_choice_source on review_guidance_choice (org_id, source_id);
