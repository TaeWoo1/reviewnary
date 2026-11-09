-- 2026-11-05 eleventh review: what a held draft is waiting for. CHECK — the first check never ran (the ceiling); RECHECK
-- — the first check ran and came back without every item, and the one second look is what the ceiling stopped. A resume
-- runs only what is left: a held recheck is that one call, never the first check again, and never a third.
alter table draft_review_pending add column if not exists awaiting varchar(16) not null default 'CHECK';
