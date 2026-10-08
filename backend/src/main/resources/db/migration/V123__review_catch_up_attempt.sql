-- 로그인 벽 뒤의 재개가 창을 다시 책상에 올릴 수 있게 하는 시도 차수.
--
-- `queue()`가 창마다 결정적인 clientJobId를 만들고(`cu-<run>-<start>-<end>`), `dispatch()`는 그 키로 기존
-- job을 찾으면 그대로 돌려준다. 같은 창을 두 번 큐에 넣지 않으려는 멱등 장치인데, 이미 SETTLED인 job을
-- 다시 열지는 않는다. 그래서 2026-10-09에 AUTH로 멈춘 창을 재개하면 run은 RUNNING이 되고, 낡은 SETTLED
-- row를 받아 들고, 아무것도 큐에 올리지 않은 채 끝났다 — 책상은 비었고 run은 영원히 RUNNING이었다.
--
-- 차수를 키에 넣으면 재개는 새 row를 만들고, 벽에 막힌 row는 audit으로 남고, 한 시도 안에서의 중복
-- dispatch는 여전히 막힌다. 기존 row는 1로 시작한다 — 그것이 그 row들이 만들어진 차수다.
alter table review_catch_up_run
    add column attempt integer not null default 1;

comment on column review_catch_up_run.attempt is
    'Resume attempt. Part of each child job''s client id, so a window walled by a sign-in can be queued again without overwriting the row that recorded the wall.';
