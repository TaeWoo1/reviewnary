-- 검토 대기 (사장님 2026-10-29: 「예산 부족으로 검토하지 못한 초안은 검토 대기로 남긴다」).
-- AI 사용량 상한 때문에 근거 대조를 못 한 문의 초안을 버리지도, 검토 없이 보이지도 않고 보관한다.
-- 같은 근거로 다시 만들 때 새로 쓰지 않고 이 본문을 검토부터 잇는다. 작업 하나에 한 줄.
create table draft_review_pending (
    id uuid primary key,
    org_id uuid not null,
    work_item_id uuid not null,
    category varchar(40),
    title varchar(300),
    body text not null,
    inputs_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_draft_review_pending_item unique (org_id, work_item_id)
);
