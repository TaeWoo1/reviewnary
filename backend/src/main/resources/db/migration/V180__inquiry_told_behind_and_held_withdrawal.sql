-- Two follow-ons to V177 (2026-11-05 tenth review).
--
-- 1. An approved answer held at registration for a reason a person must fix (a policy conflict, a forbidden phrase, a
--    changed figure — not only a changed seller record) may be taken back the same way, once nothing was sent. Such an
--    answer may have no seller record at all, so standing_check_id is no longer required, and the hold's codes at that
--    moment are kept beside the reason. Rows already written keep their values.
alter table inquiry_approval_withdrawn alter column standing_check_id drop not null;
alter table inquiry_approval_withdrawn add column if not exists hold_codes varchar(400);

-- 2. 「처리는 완료됐지만 고객에게는 진행 중으로 안내했어요」 — a registered answer written before the seller recorded the action
--    done. The Home shows it until the seller closes it, and the closing names the record it was closed for, so the same
--    record never brings it back and a later one can. closure: TOLD_SEPARATELY (the seller told the customer some other
--    way) · NOT_NEEDED. Nothing is sent by closing, and nothing here is ever updated or deleted.
create table if not exists inquiry_told_behind_closure (
    id           uuid         primary key,
    org_id       uuid         not null references organizations (id),
    work_item_id uuid         not null references inquiry_work_item (id),
    check_id     uuid         not null,
    closure      varchar(20)  not null,
    closed_by    varchar(120) not null,
    closed_at    timestamptz  not null,
    constraint ck_inquiry_told_behind_closure check (closure in ('TOLD_SEPARATELY', 'NOT_NEEDED')),
    constraint uq_inquiry_told_behind_closure unique (work_item_id, check_id)
);
