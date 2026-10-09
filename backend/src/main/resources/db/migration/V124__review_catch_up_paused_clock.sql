-- catch-up 상한은 기계가 일한 시간을 재야 한다 — 사람이 로그인하는 시간을 재서는 안 된다.
--
-- `exceeded()`는 maxElapsed를 started_at부터 쟀다. 로그인 벽에서 멈춘 intent는 살아남지만(그건 맞다),
-- 판매자가 로그인에 10분을 쓰면 재개 후 첫 창이 끝나는 순간 STOPPED_LIMIT/MAX_ELAPSED로 죽는다. 6분은
-- 한 번의 누름이 기계를 얼마나 오래 쓰는지를 묶는 상한이고, 그 안에 사람의 시간이 들어가 있었다.
--
-- started_at은 그대로 둔다 — 그 값은 「언제 시작했나」라는 기록이고, 상한 계산을 위해 옮기면 기록이
-- 거짓이 된다. 대신 멈춰 있던 시간을 누적해서 뺀다.
alter table review_catch_up_run
    add column paused_ms    bigint    not null default 0,
    add column paused_since timestamptz;

comment on column review_catch_up_run.paused_ms is
    'Accumulated milliseconds this intent spent waiting on a person (a sign-in wall). Subtracted from elapsed before the bound is applied.';
comment on column review_catch_up_run.paused_since is
    'When the current wait began, or null when the intent is not waiting. Folded into paused_ms on resume.';
