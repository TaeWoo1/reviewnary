-- What kind of inquiry this is, as the service read it and as the seller corrected it (2026-11-05 seventh review:
-- 「판매자가 AI의 판단을 방법 안내 / 상태 확인 / 조치 요청으로 고치는 기능」).
--
-- inquiry_work_item.judged_kind — the service's own reading at the last decided draft, before any correction:
-- HOW_TO (answered from the store's policy or product information) · ORDER_STATE (what happened to this order) ·
-- ORDER_ACTION (something to be done to this order). Null before a decided draft. Overwritten by each decided draft
-- that ran without a correction in effect; a correction never writes it.
alter table inquiry_work_item add column if not exists judged_kind varchar(16);

-- The seller's correction, append-only: a new correction supersedes the standing one and is never edited. kind is
-- what the seller said; judged_kind what the screen showed as the service's reading when they said it. While a
-- correction stands, every decision about this inquiry plans its needs under it — the drafting and the read-only
-- assessments alike — so the screen, the draft and the investigation cannot disagree about what was asked.
create table if not exists inquiry_kind_correction (
    id            uuid         primary key,
    org_id        uuid         not null references organizations (id),
    work_item_id  uuid         not null references inquiry_work_item (id),
    kind          varchar(16)  not null,
    judged_kind   varchar(16),
    corrected_by  varchar(120) not null,
    corrected_at  timestamptz  not null,
    superseded_at timestamptz,
    constraint chk_inquiry_kind_correction_kind check (kind in ('HOW_TO', 'ORDER_STATE', 'ORDER_ACTION'))
);
create index if not exists ix_inquiry_kind_correction_standing
    on inquiry_kind_correction (work_item_id) where superseded_at is null;
