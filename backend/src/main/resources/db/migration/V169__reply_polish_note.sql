-- 2026-11-01, sixth review: a draft whose only remaining findings after the one revision are about form (length, a
-- repeated sentence) is saved for the seller to polish instead of being handed over as a judgement. The note says what to
-- polish; the run keeps which form rules it was.
ALTER TABLE review_reply_draft ADD COLUMN polish_note varchar(300);
ALTER TABLE reply_agent_run ADD COLUMN polish_rules varchar(200);
