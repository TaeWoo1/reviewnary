-- 2026-11-06, thirteenth review: a question the seller's completed action made moot is closed by the system as
-- 「조치 완료로 판단 불필요」, never as answered — and the record it rests on is named, so the closing can be read back
-- and undone by a later record. Nullable: every other close has no record behind it.
ALTER TABLE open_question ADD COLUMN IF NOT EXISTS basis_ref varchar(120);
ALTER TABLE open_question_event ADD COLUMN IF NOT EXISTS basis_ref varchar(120);
