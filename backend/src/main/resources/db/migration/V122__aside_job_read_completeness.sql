-- Why a screen read may or may not claim it covered its period — the evidence, beside the verdict.
--
-- `observed_capacity` was carrying two different facts at once: the recipe's own ceiling (500 rows), and a
-- standing assumption that the Seller Center list was showing 500 rows per page. `rows < capacity` was then
-- read as «the whole period was seen», which it never proved. A list set to 50 per page and holding 300
-- reviews satisfies it and has shown the reader a sixth of the period.
--
-- These four columns are what the screen itself stated, so the comparison can be re-checked later rather than
-- believed once: the total the list printed, the page size it was set to, how the rows were obtained, and how
-- many single-month calendar steps it took to reach the period. `completeness_reason` records which test
-- decided, so a PARTIAL window can be explained without re-running it.
alter table scheduled_aside_job
    add column labelled_total       integer,
    add column selected_page_size   integer,
    add column grid_read_mode       varchar(16),
    add column month_moves          integer,
    add column completeness_reason  varchar(48);

comment on column scheduled_aside_job.labelled_total is
    'The total the source screen printed for the period it was showing; null when it did not state one.';
comment on column scheduled_aside_job.selected_page_size is
    'The page size the source list was set to, read from the screen; null when the chosen value was unclear.';
comment on column scheduled_aside_job.grid_read_mode is
    'How the rows were obtained. MODEL = the grid''s own row model, which answers for the whole period.';
comment on column scheduled_aside_job.month_moves is
    'Single-month calendar steps taken to reach the requested period. Audit only.';
comment on column scheduled_aside_job.completeness_reason is
    'Which test decided delivery_completeness. Null for reads made before this was recorded.';
