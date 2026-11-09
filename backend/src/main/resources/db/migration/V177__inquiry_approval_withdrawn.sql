-- An approved inquiry answer the seller took back before it was registered (2026-11-05 seventh review: 「승인 뒤 기록이
-- 바뀌어 등록이 막힌 답변의 복구 경로」). One approval stands per work item (uq_inquiry_approval_work_item, and one intent
-- and one execution with it), so taking it back moves the whole approval here, field for field, before the standing rows
-- are cleared and the reply goes back to the seller. Nothing in this table is ever updated or deleted: it is the record
-- that this answer was approved, by whom and when, what the send said at that moment, and why it was taken back.
--
-- written_from_check_id is the seller record the approved version was written from (null when it had none);
-- standing_check_id is the record that stood when it was taken back — they differ, which is why it was.
-- seller_confirmed_not_registered: the seller said they had not put it on the seller centre by hand. The service cannot
-- read a hand copy; the channel read at channel_read_at had no answer.
create table if not exists inquiry_approval_withdrawn (
    id                              uuid         primary key,
    org_id                          uuid         not null references organizations (id),
    work_item_id                    uuid         not null references inquiry_work_item (id),
    approval_id                     uuid         not null,
    approved_draft_version          integer      not null,
    approved_fingerprint            varchar(64)  not null,
    approval_command_id             varchar(120) not null,
    approver                        varchar(120) not null,
    approved_at                     timestamptz  not null,
    seller_account_id               uuid,
    channel_id                      uuid,
    target_external_id              varchar(200),
    source_subtype                  varchar(32),
    action_kind                     varchar(40),
    execution_status                varchar(32),
    execution_failure_reason        varchar(40),
    written_from_check_id           uuid,
    standing_check_id               uuid         not null,
    reason                          varchar(40)  not null,
    seller_confirmed_not_registered boolean      not null,
    channel_read_at                 timestamptz,
    withdrawn_by                    varchar(120) not null,
    withdrawn_at                    timestamptz  not null
);
create index if not exists ix_inquiry_approval_withdrawn_work_item
    on inquiry_approval_withdrawn (work_item_id, withdrawn_at);
