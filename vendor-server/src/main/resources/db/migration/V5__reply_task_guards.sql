-- Two guards on review-reply tasks — docs/vendor_server_contract_v1.md §4.2. Written to run unchanged on PostgreSQL and
-- on H2 in PostgreSQL mode.

-- The review's display state when the task was opened. Hiding or removing a review shows only as a changed 전시상태
-- (§3.1), so a delegate's last look before posting compares against this.
alter table reply_tasks add column review_display_state varchar(40);

update reply_tasks set review_display_state =
    (select r.display_state from store_reviews r where r.id = reply_tasks.review_id);

-- The draft fingerprints a store's PC last reported (policy-check). A draft made under others cannot be approved
-- until the PC drafts it again under these; editing the text on the phone does not count as that.
create table store_draft_policies (
    store_id     uuid primary key references stores (id),
    knowledge_fp varchar(64) not null,
    policy_fp    varchar(64) not null,
    voice_fp     varchar(64) not null,
    checked_at   timestamp with time zone not null
);
