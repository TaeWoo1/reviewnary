-- 리뷰 응대 기준 — 판매자마다 어떤 리뷰에 답글 초안을 미리 써 둘지 (product-owner, 2026-10-28: 「리뷰에는 응대가 필요한
-- 경우 초안을 두기로. 판매사마다 응대 기준이 다르니 custom이 돼야」).
-- 별점대 셋(★1–2 · ★3 · ★4–5)마다 AHEAD(자동 확인이 미리 쓴다) 또는 ON_REQUEST(판매자가 누를 때 쓴다). 줄이 없는 org은
-- 기본값 — 셋 다 AHEAD — 을 쓴다. 미리 쓴 초안은 초안일 뿐이다: 승인은 판매자가 하고, 그 승인이 「대응 필요」 기록이다.
create table review_response_rule (
    org_id      uuid primary key,
    low_mode    varchar(16) not null,
    mid_mode    varchar(16) not null,
    high_mode   varchar(16) not null,
    updated_by  uuid,
    updated_at  timestamptz not null,
    constraint ck_review_response_rule_low check (low_mode in ('AHEAD', 'ON_REQUEST')),
    constraint ck_review_response_rule_mid check (mid_mode in ('AHEAD', 'ON_REQUEST')),
    constraint ck_review_response_rule_high check (high_mode in ('AHEAD', 'ON_REQUEST'))
);
