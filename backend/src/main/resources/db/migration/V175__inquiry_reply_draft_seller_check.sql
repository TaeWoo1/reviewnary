-- Which seller record an inquiry reply version was written from (2026-11-03 seller-check review, fifth pass).
--
-- A save time later than the current record does not mean the writer saw it: a draft left open on the PC can be saved
-- after the record was corrected on the phone. So a version names the record its writer had in front of them — the
-- composer the record it read before drafting, the screen the record it was showing when the seller saved — and the
-- recheck compares that id with the record that stands, whoever wrote the version. Null when none was shown.
alter table inquiry_reply_draft add column if not exists seller_check_id uuid;
