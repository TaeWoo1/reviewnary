-- 「표시가 맞지 않음」 — 판매자가 리뷰의 안전 표시(부상 언급 · 걱정 언급 …)가 틀렸다고 남긴 의견 (2026-10-25 시안 ②).
-- 표시는 바꾸지 않는다: 표시는 리뷰 본문에서 그때그때 읽는 신호이고, 이 의견은 그 신호를 고칠 때 시험 자료로만 쓴다.
-- 누를 때 화면에 보이던 표시(종류 · 발생/걱정 · 걸린 표현)를 같이 남겨, 신호가 바뀐 뒤에도 무엇에 대한 의견인지 남는다.
create table review_safety_feedback (
    id          uuid primary key,
    org_id      uuid         not null,
    review_id   uuid         not null,
    kind        varchar(20),
    reading     varchar(12),
    term        varchar(40),
    created_by  varchar(120) not null,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);
create index review_safety_feedback_review_idx on review_safety_feedback (org_id, review_id);
