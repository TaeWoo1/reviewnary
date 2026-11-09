-- What the seller found when they checked an inquiry's order on the marketplace (2026-11-02 seller-check review).
--
-- An order action (refund, cancellation, receipt) is one SellerOps cannot execute, and an order it cannot read is one it
-- cannot report on. The seller can: they check on the seller centre and write down what they found. That record is a
-- ground for THIS inquiry's reply only — it is never company knowledge, never an order binding, and never a claim that
-- the channel itself confirmed anything (confirmed_by says who did).
--
-- A correction is a new row; the old one is closed with superseded_at and kept, so a draft written from it can still
-- say what it was written from.
create table if not exists inquiry_seller_check (
    id             uuid primary key,
    org_id         uuid         not null,
    work_item_id   uuid         not null,
    -- DONE · IN_PROGRESS · CANNOT · NEEDS_CUSTOMER_INFO
    outcome        varchar(24)  not null,
    note           varchar(300) not null,
    -- SELLER today; CHANNEL once an official read or execution can confirm the same thing.
    confirmed_by   varchar(16)  not null,
    recorded_by    varchar(120) not null,
    recorded_at    timestamptz  not null,
    superseded_at  timestamptz
);

-- At most one current record per inquiry.
create unique index if not exists uq_inquiry_seller_check_current
    on inquiry_seller_check (work_item_id) where superseded_at is null;

create index if not exists ix_inquiry_seller_check_work
    on inquiry_seller_check (org_id, work_item_id, recorded_at);
