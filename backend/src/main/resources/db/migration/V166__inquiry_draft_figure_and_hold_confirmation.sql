-- 문의 답변의 숫자마다 그 숫자를 받친 근거 (2026-11-01 외부 검토: 「문서가 바뀌었지만 답은 여전히 맞음」과 「검증할 정보가 없어 맞는지
-- 모름」을 구분하라 -- 주문 원문 전체가 아니라 답변에 쓴 수치 · 단위 · 대상 · 조건 · 출처와 당시 판을 남기고, 바뀐 근거로 다시 확인).
--
-- 모델이 쓴 초안이 저장될 때, 답변 속 숫자 하나에 한 행. 근거가 판매자 자료(운영 정책 · 상품 자료 · 상품 등록 정보 · 판매자 지침)면
-- 그 숫자가 적힌 근거의 한 구절과 출처를 남긴다 -- 재검사는 그 구절이 지금 자료에 그대로 있는지, 숫자가 아예 사라졌는지, 둘 다
-- 아닌지(확인 불가)를 가린다. 주문 정보와 고객 문의에서 온 숫자는 값과 단위만 남긴다: 주문 문장과 고객 글은 이 표에 담지 않는다.
-- append-only.
create table inquiry_draft_figure (
    id               uuid primary key,
    org_id           uuid not null references organizations(id) on delete cascade,
    work_item_id     uuid not null references inquiry_work_item(id) on delete cascade,
    draft_version    int not null,
    ordinal          int not null,
    value            varchar(40) not null,
    unit             varchar(16),
    -- 답변에서 그 숫자가 든 구절 (판매자 쪽 글)
    reply_clause     varchar(500) not null,
    -- ORG_POLICY | PRODUCT_KNOWLEDGE | PRODUCT_FACTS | SELLER_GUIDANCE | ORDER | QUESTION | NONE
    ground_kind      varchar(24) not null,
    -- 근거 문서 (운영 정책 · 상품 자료 · 판매자 지침)
    source_id        uuid,
    -- 근거가 상품 등록 정보일 때 그 상품
    product_id       uuid,
    -- 근거에서 그 숫자가 든 한 구절. 판매자 자료일 때만 -- 주문 · 고객 글은 null
    ground_clause    varchar(500),
    -- 근거 문서의 당시 판 (운영 정책만 판 번호를 가진다)
    source_version   int,
    created_at       timestamptz not null
);

create index idx_inquiry_draft_figure_version on inquiry_draft_figure (work_item_id, draft_version);

-- 보류된 초안을 판매자가 이유를 보고 「확인하고 진행」한 기록 (2026-11-01 외부 검토: 작성자와 관계없이 같은 검사, 출처 변경 ·
-- 불확실성은 이유를 보여 주고 명시적 확인과 기록으로 진행, 금지 표현과 적용하면 안 되는 조건은 고치기 전까지 차단).
--
-- 확인은 그 판 · 그 기준 · 그 발견에만 묶인다: 정책이 또 바뀌거나 발견이 달라지면 다시 확인해야 한다. append-only -- 누가 언제
-- 무엇을 보고 진행했는지는 지우지 않는다.
create table reply_hold_confirmation (
    id               uuid primary key,
    org_id           uuid not null references organizations(id) on delete cascade,
    -- REVIEW | INQUIRY
    lane             varchar(16) not null,
    subject_id       uuid not null,
    draft_version    int not null,
    -- 승인한 답글의 등록을 위한 확인인가
    approved         boolean not null,
    -- 확인한 발견의 코드 (쉼표로 이음)
    codes            varchar(200) not null,
    -- 확인한 발견의 지문 (코드 · 바뀐 자료 · 근거 잃은 낱말 · 숫자) -- 같은 발견일 때만 이 확인이 통한다
    finding_key      varchar(64) not null,
    basis            varchar(300) not null,
    confirmed_by     varchar(80) not null,
    created_at       timestamptz not null
);

create index idx_reply_hold_confirmation_subject on reply_hold_confirmation (lane, subject_id, draft_version);
