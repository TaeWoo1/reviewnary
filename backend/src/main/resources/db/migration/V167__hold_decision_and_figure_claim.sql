-- 2026-11-01, third review and the owner's follow-up.
--
-- reply_hold_confirmation: what the seller decided on a hold. CONFIRM is 「확인하고 진행」 past a changed source or a
-- number nobody can prove still holds; EXCEPTION is 「이번 건만 예외」 for a promise nothing registered backs, and names
-- the words it was decided for. A plain confirmation no longer goes past an unbacked promise. Rows written before this
-- were confirmations.
ALTER TABLE reply_hold_confirmation ADD COLUMN decision varchar(16) NOT NULL DEFAULT 'CONFIRM';
ALTER TABLE reply_hold_confirmation ADD COLUMN excepted varchar(500);

-- inquiry_draft_figure: the claim a number is in, as the claim check read it at generation — what it is about, under
-- which condition, and whose word it is (the seller's material, the order, or the customer's own question) — and how
-- its ground was chosen: MODEL when the claim check named the passage, TEXT when only one clause anywhere states it.
ALTER TABLE inquiry_draft_figure ADD COLUMN about varchar(80);
ALTER TABLE inquiry_draft_figure ADD COLUMN condition_text varchar(120);
ALTER TABLE inquiry_draft_figure ADD COLUMN voice varchar(16);
ALTER TABLE inquiry_draft_figure ADD COLUMN linked_by varchar(16);

-- reply_agent_run: the passages of the run's small evidence bundle the working draft cites, as the backend verified them
-- (named in the bundle, quoted from it), and how many citations it refused. The critique, the save and the recheck
-- read only these.
ALTER TABLE reply_agent_run ADD COLUMN used_passages varchar(200);
ALTER TABLE reply_agent_run ADD COLUMN used_rejected integer NOT NULL DEFAULT 0;
