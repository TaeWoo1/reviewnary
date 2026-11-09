-- 정책 · 지식 · 문구가 바뀐 뒤 다시 검사해 통과하지 못한 답변 초안 (2026-11-01 외부 검토: 「웹 자동 재검사」 — 정책 변경 시 영향받는
-- 초안을 찾아 재검사, 통과하면 문장 유지, 충돌하면 이유와 함께 확인 요청, 승인 · 등록 직전에도 최신 기준으로 확인, 승인한 답글은
-- 고쳐 쓰지 않고 등록을 보류).
--
-- 초안의 글은 이 표에 없다. 글은 review_reply_draft · inquiry_reply_draft에 그대로 있고, 이 표는 「이 판은 지금 기준으로 다시 봐야
-- 한다」와 그 이유만 갖는다. 한 초안(리뷰 하나 · 문의 작업 하나)에 지금 걸린 보류는 한 행이다. 다시 검사해 통과하면 행이 지워진다.
-- draft_version이 지금 초안의 판이 아니면 그 보류는 지난 판의 것이라 읽는 쪽이 무시한다 -- 판매자가 고쳐 쓴 새 판은 새로 검사받는다.
create table reply_draft_hold (
    id             uuid primary key,
    org_id         uuid not null references organizations(id) on delete cascade,
    -- REVIEW | INQUIRY
    lane           varchar(16) not null,
    -- 리뷰 id 또는 문의 작업 id
    subject_id     uuid not null,
    draft_version  int not null,
    -- 승인된 답글이 걸렸는가. 그렇다면 등록을 멈추고 다시 승인받는다; 글은 고치지 않는다.
    approved       boolean not null default false,
    -- SOURCE_CHANGED · UNSUPPORTED_PROMISE · FORBIDDEN_PHRASE (쉼표로 이음)
    codes          varchar(200) not null,
    -- 판매자에게 보일 이유의 재료: 바뀐 자료 제목 · 근거를 잃은 약속 낱말 · 금지 표현 (JSON). 고객 글은 담지 않는다.
    note           text not null,
    -- 이 검사가 본 기준(ReviewDraftBasis + 문의 답변 스타일)
    basis          varchar(300) not null,
    created_at     timestamptz not null,
    updated_at     timestamptz not null
);

create unique index uq_reply_draft_hold_subject on reply_draft_hold (lane, subject_id);
create index idx_reply_draft_hold_org on reply_draft_hold (org_id);

-- org마다 마지막으로 대기 초안 전체를 다시 본 기준. 기준이 같으면 훑지 않는다.
create table draft_recheck_basis (
    org_id      uuid primary key references organizations(id) on delete cascade,
    basis       varchar(300) not null,
    checked_at  timestamptz not null
);
