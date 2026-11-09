-- 리뷰 한 건에 대한 공통 판단 (2026-10-28, docs/standing/reply_agent_v1.md §3-4).
-- 고객이 말한 것(범주 · 답할 점)과 판매자가 할 일 제안은 AI가 쓰고, 판매자 기준 적용과 처리 상태는 프로그램이 읽는다.
-- 리뷰마다 최신 한 줄. 홈 · 목록 · 초안이 같은 결과를 읽도록 옮겨 가는 동안, 기존 판단과 나란히 비교한다.
create table review_judgement (
    id uuid primary key,
    org_id uuid not null,
    review_id uuid not null,
    judged_at timestamp with time zone not null,
    source varchar(24) not null,
    model_version varchar(200),
    category varchar(2),
    also_categories varchar(40),
    points text,
    actions varchar(80),
    input_hash varchar(64),
    answered boolean not null default false,
    needs_response boolean not null default false,
    unsure_reason varchar(24),
    safety_signal varchar(24),
    handoff_reason varchar(40),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_review_judgement_review unique (org_id, review_id)
);
