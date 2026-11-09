-- 2026-11-02, fifth review: a held draft carries which try it was. A rewrite held for its check and resumed later was
-- counted as a first try again, so a refusal after the resume wrote a third draft, and the hold was reported as
-- 「다시 쓴 뒤 거절」. attempt: 1 = the first draft, 2 = the one rewrite. rewrite_because: why the first was refused.
alter table draft_review_pending add column attempt integer not null default 1;
alter table draft_review_pending add column rewrite_because varchar(32);
