-- 네이버 톡톡 챗봇 API 레인 (2026-11-16, docs/inquiry/talktalk_chat_lane_v1.md).
--
-- 커머스 API 문의가 아니다: 톡톡은 파트너센터에 등록한 webhook으로 이벤트를 받고 보내기 API로 답한다.
-- 공식 문서에 수신 서명·이벤트 ID·순서 번호가 없으므로 exactly-once를 주장하는 키를 두지 않는다 — 같은 본문의 재수신은
-- 지워지지 않고 「중복 의심」으로 남는다. 원 본문은 지문만, 고객 식별값은 봉인해서만 저장한다.

create table talktalk_connections (
    id                  uuid primary key,
    org_id              uuid         not null references organizations (id),
    partner_id          varchar(64)  not null,
    send_key_sealed     bytea        not null,
    send_key_key_id     varchar(80)  not null,
    webhook_token_hash  varchar(64)  not null,
    transport_mode      varchar(8)   not null,
    status              varchar(16)  not null,
    -- 연결·재연결·주소 재발급·해제마다 1씩 오른다. 이벤트·발신은 만들어진 세대에 묶이고, 세대가 다르면 보내지 않는다.
    generation          integer      not null default 1,
    first_trusted_event_at timestamptz,
    last_event_at       timestamptz,
    connected_by        uuid,
    disconnected_at     timestamptz,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    constraint uq_talktalk_connections_org unique (org_id),
    constraint uq_talktalk_connections_token unique (webhook_token_hash),
    constraint ck_talktalk_connections_mode check (transport_mode in ('FAKE', 'LIVE')),
    constraint ck_talktalk_connections_status check (status in ('CONNECTED', 'DISCONNECTED'))
);

create table talktalk_events (
    id              uuid primary key,
    org_id          uuid         not null references organizations (id),
    connection_id   uuid         not null references talktalk_connections (id),
    connection_generation integer not null,
    event_type      varchar(24)  not null,
    body_hash       varchar(64)  not null,
    user_hash       varchar(64),
    user_sealed     bytea,
    user_key_id     varchar(80),
    text_content    text,
    input_type      varchar(24),
    standby         boolean      not null default false,
    source_id       bigint,
    thread_owner_id bigint,
    handover_control varchar(24),
    handover_target bigint,
    product_name    varchar(300),
    trusted_source  boolean      not null default false,
    state           varchar(16)  not null,
    note            varchar(60),
    received_at     timestamptz  not null,
    processed_at    timestamptz,
    purged_at       timestamptz,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint ck_talktalk_events_state check (state in ('ACCEPTED', 'PROCESSING', 'PROCESSED', 'IGNORED', 'FAILED'))
);
create index ix_talktalk_events_state on talktalk_events (state, received_at);
create index ix_talktalk_events_org on talktalk_events (org_id, received_at);

create table talktalk_conversations (
    id                 uuid primary key,
    org_id             uuid         not null references organizations (id),
    connection_id      uuid         not null references talktalk_connections (id),
    -- 대화는 한 톡톡 계정(파트너)의 것이다. 다른 파트너로 다시 연결하면 옛 대화로는 보내지 않는다.
    partner_id         varchar(64)  not null,
    user_hash          varchar(64)  not null,
    user_sealed        bytea        not null,
    user_key_id        varchar(80)  not null,
    alias              varchar(16)  not null,
    thread_owner       varchar(8)   not null,
    thread_owner_at    timestamptz,
    automation_note    varchar(60),
    needs_human        boolean      not null default false,
    human_reason       varchar(60),
    last_product_name  varchar(300),
    last_inbound_at    timestamptz,
    last_outbound_at   timestamptz,
    last_auto_at       timestamptz,
    left_at            timestamptz,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint uq_talktalk_conversations_user unique (org_id, connection_id, partner_id, user_hash),
    constraint ck_talktalk_conversations_owner check (thread_owner in ('BOT', 'PARTNER'))
);
create index ix_talktalk_conversations_org on talktalk_conversations (org_id, last_inbound_at);

create table talktalk_messages (
    id                uuid primary key,
    org_id            uuid         not null references organizations (id),
    conversation_id   uuid         not null references talktalk_conversations (id),
    event_id          uuid,
    outbound_id       uuid,
    author            varchar(16)  not null,
    text_content      text,
    input_type        varchar(24),
    standby           boolean      not null default false,
    duplicate_suspect boolean      not null default false,
    body_hash         varchar(64),
    occurred_at       timestamptz  not null,
    purged_at         timestamptz,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint ck_talktalk_messages_author check (author in ('CUSTOMER', 'SERVICE', 'PARTNER_CENTER', 'BOT_ECHO'))
);
create index ix_talktalk_messages_conversation on talktalk_messages (conversation_id, occurred_at);

create table talktalk_shipping_guidance (
    id                 uuid primary key,
    org_id             uuid         not null references organizations (id),
    version            integer      not null,
    status             varchar(12)  not null,
    cutoff_hour        integer,
    cutoff_minute      integer      not null default 0,
    dispatch_days      varchar(40)  not null,
    closed_dates       text         not null,
    confirmed_through  date,
    fingerprint        varchar(64)  not null,
    created_by         uuid,
    confirmed_by       uuid,
    confirmed_at       timestamptz,
    retired_at         timestamptz,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint uq_talktalk_guidance_version unique (org_id, version),
    constraint ck_talktalk_guidance_status check (status in ('DRAFT', 'CONFIRMED', 'RETIRED')),
    constraint ck_talktalk_guidance_cutoff check (cutoff_hour is null or (cutoff_hour between 0 and 23)),
    constraint ck_talktalk_guidance_minute check (cutoff_minute between 0 and 59)
);
-- 한 org에 확정 기준은 하나뿐이다.
create unique index uq_talktalk_guidance_confirmed on talktalk_shipping_guidance (org_id) where status = 'CONFIRMED';

create table talktalk_delegations (
    id                   uuid primary key,
    org_id               uuid         not null references organizations (id),
    enabled              boolean      not null,
    scope                varchar(40)  not null,
    guidance_id          uuid references talktalk_shipping_guidance (id),
    guidance_fingerprint varchar(64),
    preview_fingerprint  varchar(64),
    actor_user_id        uuid         not null,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    constraint ck_talktalk_delegations_scope check (scope in ('HOLIDAY_AND_CUTOFF_GUIDANCE'))
);
create index ix_talktalk_delegations_org on talktalk_delegations (org_id, created_at);

create table talktalk_drafts (
    id                  uuid primary key,
    org_id              uuid         not null references organizations (id),
    conversation_id     uuid         not null references talktalk_conversations (id),
    trigger_message_id  uuid,
    version             integer      not null,
    author_kind         varchar(16)  not null,
    text_content        text,
    content_fingerprint varchar(64)  not null,
    basis               varchar(24)  not null,
    hold_reasons        varchar(400),
    evidence_json       text         not null,
    guidance_id         uuid,
    created_by          uuid,
    purged_at           timestamptz,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    constraint uq_talktalk_drafts_version unique (conversation_id, version),
    constraint ck_talktalk_drafts_author check (author_kind in ('GUIDANCE', 'MODEL', 'SELLER_EDIT')),
    constraint ck_talktalk_drafts_basis check (basis in ('GROUNDED', 'NEEDS_HUMAN', 'SELLER_WRITTEN'))
);

create table talktalk_outbound (
    id                   uuid primary key,
    org_id               uuid         not null references organizations (id),
    conversation_id      uuid         not null references talktalk_conversations (id),
    kind                 varchar(16)  not null,
    text_content         text,
    content_fingerprint  varchar(64)  not null,
    command_id           varchar(80)  not null,
    draft_id             uuid,
    draft_version        integer,
    trigger_message_id   uuid,
    guidance_id          uuid,
    guidance_fingerprint varchar(64),
    delegation_id        uuid,
    actor_user_id        uuid,
    transport_mode       varchar(8)   not null,
    connection_generation integer     not null,
    status               varchar(16)  not null,
    failure_code         varchar(60),
    result_code          varchar(16),
    acknowledged_unknown_of uuid,
    -- 판매자가 직접 고친 문장의 확인 필요 표시를 보고 그대로 보낸다고 확인한 코드들(없으면 비어 있음).
    hold_confirmed       varchar(400),
    echo_seen_at         timestamptz,
    claimed_at           timestamptz,
    finished_at          timestamptz,
    purged_at            timestamptz,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    constraint uq_talktalk_outbound_command unique (org_id, command_id),
    constraint ck_talktalk_outbound_kind check (kind in ('MANUAL_REPLY', 'AUTO_GUIDANCE', 'HANDOVER_PASS',
        'HANDOVER_TAKE')),
    constraint ck_talktalk_outbound_mode check (transport_mode in ('FAKE', 'LIVE')),
    constraint ck_talktalk_outbound_status check (status in ('QUEUED', 'SENDING', 'FAKE_RECORDED', 'API_ACCEPTED',
        'FAILED', 'UNKNOWN', 'CANCELLED', 'BLOCKED'))
);
create index ix_talktalk_outbound_status on talktalk_outbound (status, created_at);
create index ix_talktalk_outbound_conversation on talktalk_outbound (conversation_id, created_at);

comment on table talktalk_delegations is '톡톡 자동 안내 위임 원장(append-only). 마지막 행이 현재 상태. 기준 버전·지문이 현재 확정 기준과 같을 때만 켜진 것이다.';
comment on column talktalk_outbound.status is 'FAKE_RECORDED 모의 기록 · API_ACCEPTED 톡톡 보내기 API가 success=true·00으로 접수. 어느 것도 고객 수신·읽음이 아니다.';
comment on column talktalk_events.body_hash is '원 본문의 SHA-256. 공식 문서에 이벤트 ID가 없어 중복 판단의 근거일 뿐 유일 키가 아니다.';
