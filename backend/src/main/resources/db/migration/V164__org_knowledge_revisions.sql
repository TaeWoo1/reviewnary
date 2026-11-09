-- 운영 정책의 지난 판 — 본문이 바뀔 때마다 바뀌기 전의 판을 한 행으로 남긴다 (2026-11-01 판매자 결정: 영문 교환 정책을
-- unused에서 unopened로 고치고, 기존 버전은 변경 이력으로 보관).
--
-- org_knowledge_sources.version은 초안이 어느 판을 근거로 삼았는지만 말하고, 그 판의 본문은 남기지 않았다(V72). 그래서
-- 지난주 초안이 인용한 정책이 그때 무엇이라고 적혀 있었는지 되짚을 수 없었다. 이 표가 그 본문을 갖는다.
--
-- append-only. 애플리케이션은 이 표에 insert만 한다 -- 지난 판을 고치면 그것은 이력이 아니다. 검색은 이 표를 읽지 않는다:
-- 지난 판은 답의 근거가 아니라 기록이다. 정책을 지우면 그 이력도 함께 지워진다(판매자가 지운 정책의 본문을 남겨 둘 이유가 없다).
create table org_knowledge_revisions (
    id               uuid primary key,
    org_id           uuid not null references organizations(id),
    source_id        uuid not null references org_knowledge_sources(id) on delete cascade,
    -- 이 행이 담은 판의 번호. 그 판이 현재 판이던 동안 인용한 초안은 같은 번호를 갖는다.
    version          int not null,
    knowledge_type   varchar(32) not null,
    title            varchar(200) not null,
    body             text not null,
    source_url       varchar(1000),
    customer_phrases text,
    -- 이 판이 언제 다음 판으로 바뀌었는가. created_at과 같지만, 읽는 쪽이 그 뜻을 추측하지 않도록 따로 둔다.
    replaced_at      timestamptz not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create unique index uq_org_knowledge_revisions_version on org_knowledge_revisions (source_id, version);
create index idx_org_knowledge_revisions_org on org_knowledge_revisions (org_id);
