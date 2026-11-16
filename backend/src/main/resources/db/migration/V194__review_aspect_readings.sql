-- 강점·약점·SWOT AI 개선 (오너 결정 2026-11-17, 정본 §7 「데모 전 추가 목표」 A).
-- 리뷰 글을 AI가 측면 · 평가 · 근거로 읽은 결과. 같은 가게의 같은 글은 한 번만 읽는다(본문 지문으로 묶음).
-- 읽은 방식(프롬프트 · 모델)이 바뀌면 다른 읽기이므로 reader_version이 키에 들어간다.
create table review_aspect_readings (
    id              uuid primary key,
    org_id          uuid          not null references organizations (id),
    body_sha256     varchar(64)   not null,
    reader_version  varchar(200)  not null,
    opinions        text          not null,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    constraint uq_review_aspect_reading unique (org_id, body_sha256, reader_version)
);

-- 관찰된 강점·약점 근거로 AI가 쓴 기회·위협. 근거(입력)가 바뀌면 지문이 달라져 다시 써야 한다.
create table product_outlook_writings (
    id                 uuid primary key,
    org_id             uuid          not null references organizations (id),
    product_id         uuid          not null references products (id),
    input_fingerprint  varchar(64)   not null,
    writer_version     varchar(200)  not null,
    cells              text          not null,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    constraint uq_product_outlook_writing unique (org_id, product_id, input_fingerprint, writer_version)
);
