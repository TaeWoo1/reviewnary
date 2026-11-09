-- The seller's correction of what kind of inquiry this is (V178) that each answer version was written under
-- (2026-11-05 eighth review: 「답변 버전에 ‘작성할 때 본 판단 수정 ID’를 남기고, 현재 ID와 비교」). A model version names the
-- correction it read before drafting; a seller's save names the one the screen was showing. Null: none stood. A version
-- stands under the correction that stands now only when the two are the same — a save time proves nothing (the same
-- rule as seller_check_id, V175). Versions written before this column carry null and stand only while no correction does.
alter table inquiry_reply_draft add column if not exists kind_correction_id uuid;
