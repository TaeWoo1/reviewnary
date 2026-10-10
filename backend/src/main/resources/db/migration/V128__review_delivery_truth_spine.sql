-- Review Delivery Truth Spine v1 — Answer Memory may hold a review reply (2026-10-10).
--
-- Answer Memory already holds exactly one kind of thing: an answer the seller actually stood behind, with the act
-- that proves it (`strength`). Until now only the INQUIRY lane could write one. A review reply the seller approved
-- was readable — `ReviewReplyAdapter` joins the approval to the draft at retrieval time — but it could never carry a
-- strength, because the only columns naming an origin were `origin_inquiry_id` and `origin_work_item_id`. So
-- «리뷰 답글 중 전송이 확인된 것» was not a question this schema could be asked: the fact existed in
-- `review_reply_execution` and the answer existed in `answer_memory`, and nothing joined them.
--
-- ONE nullable column, and the asymmetry it removes is the whole change. A row with origin_review_id is a review
-- reply; a row with origin_inquiry_id is an inquiry answer; a row with neither is a collected channel answer. The
-- three were always three different acts and `origin_ref` already told them apart — this makes the subject itself
-- queryable, which is what a Decision→Action→Outcome dataset needs and a LIKE over origin_ref is not.
--
-- NOT A FOREIGN KEY, deliberately, and for the reason the two inquiry columns are not either: Answer Memory
-- outlives its subject on purpose. A review that is re-acquired under a new id, or deleted from the canonical
-- table, must not take the company's own sentence with it — the memory is what the seller said, and that stays
-- true after the row that prompted it is gone.
--
-- NOTHING IS BACKFILLED. Every approval that predates this migration was recorded by a screen that wrote no
-- memory, and every verified execution likewise. Writing rows for them now would invent `created_at`s and
-- strengths for acts nobody recorded a memory for, and would do it at a strength (USER_APPROVED /
-- EXECUTOR_SENT_VERIFIED) that claims a decision was captured when it was not. The retrieval path for those
-- replies is unchanged — `ReviewReplyAdapter` still reads them from the approval — so nothing becomes less visible
-- here; only the strength-bearing dataset starts empty and fills from the next approval forward.

alter table answer_memory add column if not exists origin_review_id uuid;

comment on column answer_memory.origin_review_id is
    'The review this remembered reply answered. Null on an inquiry answer and on a collected channel answer. Not a foreign key: the memory outlives its subject.';

-- Org-scoped because every read of this table already is, and partial because the inquiry lane will keep writing
-- the overwhelming majority of rows and has no use for this index.
create index if not exists idx_answer_memory_origin_review
    on answer_memory (org_id, origin_review_id)
    where origin_review_id is not null;

-- ── Why no new table for the delivery truth itself ──────────────────────────────────────────────────────────────
--
-- The canonical truth of what happened to an approved review reply is assembled at READ time from the two tables
-- that already own their halves — `review_reply_execution` (V84: what reviewnary did and could confirm) and
-- `review_reply_outcome` (V20: what the operator reported doing) — exactly as the inquiry lane assembles its own
-- from `inquiry_execution` + `inquiry_verification` and holds no third row for the result. A stored merge would be
-- a third copy of two facts that are already append-only, and the copy is the one that drifts. The reader is
-- `ReviewDeliveryTruthReader`; this migration adds no column to either source table and changes no constraint on
-- them, so no marketplace WRITE path is touched.
--
-- `review.reply_state` is likewise untouched and must stay that way: it is a MARKETPLACE OBSERVATION, written only
-- by ingestion from what the channel reports. An execution is reviewnary's own record of its own act, and a
-- product that let its own act overwrite the channel's word would have no way left to notice the two disagreeing.
