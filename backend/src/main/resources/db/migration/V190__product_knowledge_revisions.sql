-- 상품 자료의 지난 판 (2026-11-16 통합 리허설 — 올린 파일 바꾸기). 운영 정책의 V164와 같은 규칙이다.
--
-- 상품 자료(product_knowledge_sources)에는 판 번호도 지난 본문도 없었다. 그래서 파일을 바꾸거나 본문을 고치면 지난주 초안이
-- 인용한 상품 안내가 그때 무엇이라고 적혀 있었는지 되짚을 수 없었다. 본문이 바뀔 때마다 판 번호를 올리고, 바뀌기 전의 판을
-- 한 행으로 남긴다.
--
-- append-only. 애플리케이션은 이 표에 insert만 한다. 검색은 이 표를 읽지 않는다: 지난 판은 답의 근거가 아니라 기록이다.
-- 상품 자료를 지우면 그 이력도 함께 지워진다(V164와 같음).
alter table product_knowledge_sources add column version int not null default 1;

create table product_knowledge_revisions (
    id            uuid primary key,
    org_id        uuid not null references organizations(id),
    source_id     uuid not null references product_knowledge_sources(id) on delete cascade,
    product_id    uuid not null,
    -- 이 행이 담은 판의 번호.
    version       int not null,
    source_type   varchar(24) not null,
    title         varchar(200) not null,
    body          text not null,
    -- 그 판이 어떤 파일에서 왔는가(직접 입력한 자료면 null).
    document_name varchar(260),
    replaced_at   timestamptz not null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null
);

create unique index uq_product_knowledge_revisions_version on product_knowledge_revisions (source_id, version);
create index idx_product_knowledge_revisions_org on product_knowledge_revisions (org_id);
