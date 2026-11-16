-- 자료 폴더 동기화 (오너 결정 2026-11-17, 정본 §7 「데모 전 추가 목표」 B).
-- 판매자 PC의 폴더를 브라우저가 읽어 바뀐 파일만 올린다. 서버는 폴더 안 상대 경로 하나가 어느 자료가 됐는지와
-- 마지막으로 올린 내용의 지문을 기억한다. 폴더에서 사라진 파일은 자료를 지우지 않고 사용 중지로 바꾼다.

create table knowledge_folder_links (
    id            uuid primary key,
    org_id        uuid          not null references organizations (id),
    folder_name   varchar(200)  not null,
    mode          varchar(10)   not null,
    linked_by     uuid,
    last_scan_at  timestamptz,
    last_scan_by  uuid,
    created_at    timestamptz   not null,
    updated_at    timestamptz   not null,
    constraint uq_knowledge_folder_link_org unique (org_id),
    constraint ck_knowledge_folder_link_mode check (mode in ('WATCH', 'UPLOAD'))
);

create table knowledge_folder_files (
    id               uuid primary key,
    org_id           uuid          not null references organizations (id),
    path             varchar(500)  not null,
    scope            varchar(10)   not null,
    product_id       uuid,
    source_id        uuid,
    content_sha256   varchar(64),
    size_bytes       bigint,
    synced_version   integer,
    state            varchar(10)   not null,
    held_reason      varchar(30),
    held_message     varchar(300),
    use_folder       boolean       not null default false,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    constraint uq_knowledge_folder_file_path unique (org_id, path),
    constraint ck_knowledge_folder_file_scope check (scope in ('ORG', 'PRODUCT')),
    constraint ck_knowledge_folder_file_state check (state in ('SYNCED', 'MISSING', 'HELD'))
);
create index ix_knowledge_folder_files_source on knowledge_folder_files (source_id);
