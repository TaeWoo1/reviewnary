-- 판매자 PC가 공급자 서버의 리뷰 피드를 기존 리뷰 레인으로 받아 오는 기록 — docs/vendor_server_contract_v1.md §3.2.
--
-- vendor_store_binding: 공급자 서버의 스토어 하나를 이 PC의 조직 하나에 묶는다. 판매자가 이 PC에서 직접 받아 오기를
-- 누를 때 그 판매자의 조직으로만 생기고(추측으로 묶지 않는다), review_cursor는 이 PC가 저장을 끝낸 마지막 사건 번호다.
-- vendor_review_ref: 로컬 리뷰 한 건과 공급자 서버의 리뷰 한 건의 짝, 그리고 리뷰 레인에 칸이 없는 사실(서버가 아는
-- 본문 지문 · 전시 상태 · 첨부 주소 1개). 답글 작업을 서버에 낼 때 서버의 리뷰 ID와 본문 지문이 필요하다(§4.3).
create table vendor_store_binding (
    id               uuid primary key,
    vendor_store_id  uuid         not null unique,
    org_id           uuid         not null references organizations (id),
    channel_id       uuid         not null references channels (id),
    store_label      varchar(200) not null,
    review_cursor    bigint       not null default 0,
    last_pulled_at   timestamptz,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);

create table vendor_review_ref (
    id               uuid primary key,
    review_id        uuid         not null unique references reviews (id) on delete cascade,
    vendor_store_id  uuid         not null references vendor_store_binding (vendor_store_id),
    vendor_review_id uuid         not null unique,
    body_fingerprint char(64)     not null,
    display_state    varchar(40),
    attachment_url   varchar(1000),
    attachment_kind  varchar(16),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);
