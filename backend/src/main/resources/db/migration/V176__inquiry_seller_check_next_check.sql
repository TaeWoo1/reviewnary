-- The seller's own next look at an action still in progress (2026-11-03 sixth review).
--
-- Answering the customer and finishing the action are two different facts: a reply that tells the customer the refund is
-- in progress can be registered while the refund itself is still open. When the seller records 「접수·진행 중」 they also
-- say when they will check again; on that day the inquiry comes back to the seller's own list as a check to make.
--
-- It is the SELLER's task, never a promise to the customer: it is not part of the evidence a draft is written from, and the
-- day arriving writes nothing — no draft, no approval, no message. Set only on an IN_PROGRESS record (two days later until
-- the seller picks a day). It is the one column of this table that changes in place: moving the day is the seller's
-- schedule, not a new finding, so the record stays the one a reply was written from and no approved reply is held for it.
alter table inquiry_seller_check add column if not exists next_check_on date;

create index if not exists ix_inquiry_seller_check_open
    on inquiry_seller_check (org_id, outcome) where superseded_at is null;
