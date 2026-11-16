-- Intake alerts (카카오톡 접수 알림, night handoff 2026-11-16 unit ③).
--
-- Three tables, one job each:
--   intake_alert_settings   — one seller user's choice to be told, in one org (who, which kinds, when, which number).
--   intake_alert_items      — the durable outbox: one row per (org, original review/inquiry, recipient), never two.
--   intake_alert_deliveries — one message and what the sender said about it.
--
-- Default (owner, 2026-11-16 night: 「일단 초기니까 리뷰/문의가 올 때마다 카톡으로 알리고, 가볍게 이슈/응대 내용 안내하고
-- 링크로 서비스에서 컨펌」): one message per new review/inquiry, as soon as it is found, at any hour. Bundling
-- (delivery_mode = DIGEST) and a receiving window (quiet_hours_enabled) are opt-in settings, off by default; neither
-- ever replaces the per-item default, and a safety report is sent at once whatever the settings.
--
-- The items are written by a reconciler that reads reviews/inquiries themselves (created_at since the setting was
-- turned on), not only by the ingest event — an event lost after the ingest committed is recovered on the next pass.
-- A settings change that changes WHO or WHETHER (off/on, number, consent) bumps generation; queued items and prepared
-- deliveries of an older generation are closed CANCELLED with the reason and are never sent to the new settings.
-- No customer text and no plain phone number is stored here: the number is sealed with the vault key ring and only
-- its last four digits are kept for display.

create table intake_alert_settings (
    id                uuid primary key,
    org_id            uuid         not null references organizations (id),
    user_id           uuid         not null references users (id),
    enabled           boolean      not null default false,
    notify_reviews    boolean      not null default true,
    notify_inquiries  boolean      not null default true,
    delivery_mode     varchar(16)  not null default 'EACH',
    digest_minutes    integer      not null default 60,
    quiet_hours_enabled boolean    not null default false,
    days_mask         integer      not null default 127,
    start_minute      integer      not null default 540,
    end_minute        integer      not null default 1080,
    watch_from        timestamptz,
    reviews_from      timestamptz,
    inquiries_from    timestamptz,
    generation        integer      not null default 0,
    last_digest_at    timestamptz,
    phone_last4       varchar(4),
    phone_sealed      bytea,
    phone_key_id      varchar(80),
    phone_consent_at  timestamptz,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint uq_intake_alert_settings_user unique (org_id, user_id),
    constraint ck_intake_alert_mode check (delivery_mode in ('EACH', 'DIGEST')),
    constraint ck_intake_alert_digest check (digest_minutes between 15 and 1440),
    constraint ck_intake_alert_days check (days_mask between 0 and 127),
    constraint ck_intake_alert_window check (start_minute between 0 and 1439 and end_minute between 1 and 1440
                                             and start_minute < end_minute)
);

create table intake_alert_deliveries (
    id                  uuid primary key,
    org_id              uuid          not null references organizations (id),
    setting_id          uuid          not null references intake_alert_settings (id),
    kind                varchar(16)   not null,
    setting_generation  integer       not null,
    mode                varchar(16)   not null,
    status              varchar(24)   not null,
    review_count        integer       not null default 0,
    inquiry_count       integer       not null default 0,
    urgent_count        integer       not null default 0,
    message_text        varchar(1000) not null,
    link_path           varchar(200)  not null,
    provider_message_id varchar(100),
    failure_code        varchar(60),
    claimed_at          timestamptz,
    finished_at         timestamptz,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    constraint ck_intake_alert_delivery_kind check (kind in ('SINGLE', 'DIGEST')),
    constraint ck_intake_alert_delivery_mode check (mode in ('MOCK', 'LIVE', 'BLOCKED')),
    constraint ck_intake_alert_delivery_status check (status in
        ('PREPARED', 'SENDING', 'MOCK_RECORDED', 'ACCEPTED', 'FAILED', 'UNKNOWN', 'BLOCKED', 'CANCELLED'))
);

create index idx_intake_alert_deliveries_setting on intake_alert_deliveries (setting_id, created_at desc);
create index idx_intake_alert_deliveries_status on intake_alert_deliveries (status, claimed_at);

create table intake_alert_items (
    id                 uuid primary key,
    org_id             uuid         not null references organizations (id),
    setting_id         uuid         not null references intake_alert_settings (id),
    subject_kind       varchar(16)  not null,
    subject_id         uuid         not null,
    channel_code       varchar(40),
    reason             varchar(24)  not null,
    issue_label        varchar(20),
    urgent             boolean      not null default false,
    status             varchar(24)  not null,
    setting_generation integer      not null,
    cancel_reason      varchar(24),
    delivery_id        uuid references intake_alert_deliveries (id),
    source_ingested_at timestamptz  not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint uq_intake_alert_items_subject unique (org_id, setting_id, subject_kind, subject_id),
    constraint ck_intake_alert_item_kind check (subject_kind in ('REVIEW', 'INQUIRY')),
    constraint ck_intake_alert_item_status check (status in ('PENDING', 'INCLUDED', 'DROPPED_HANDLED', 'DROPPED_OLD',
                                                          'CANCELLED'))
);

create index idx_intake_alert_items_pending on intake_alert_items (setting_id, status);
create index idx_intake_alert_items_delivery on intake_alert_items (delivery_id);

-- The reconciler reads "rows that arrived in this org since T" every pass.
create index if not exists idx_reviews_org_created on reviews (org_id, created_at);
create index if not exists idx_inquiries_org_created on inquiries (org_id, created_at);
