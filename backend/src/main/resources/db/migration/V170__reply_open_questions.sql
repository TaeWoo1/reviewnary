-- 2026-11-01, eighth review: a review only partly answerable keeps the reply for the points the store's material answers,
-- and the questions it could not answer are shown to the seller beside it — never counted as a complete answer. The run
-- keeps them too, so an evaluation can tell a partial draft from a whole one.
ALTER TABLE review_reply_draft ADD COLUMN open_questions varchar(500);
ALTER TABLE reply_agent_run ADD COLUMN open_questions varchar(500);
