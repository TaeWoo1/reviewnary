-- 「밀린 리뷰를 확인하고 있습니다」 — 한 번의 누름이 여러 기간을 차례로 읽는다.
--
-- 왜 새 부모가 필요한가. scheduled_aside_job.run_id는 Responsibility 실행의 것이고, AsideDispatch는
-- trigger=OPERATOR인 job이 run_id를 들고 오는 것을 거절한다 — 판매자가 누른 읽기를 자율 실행이 요청한 것으로
-- 감사 기록이 말하게 되기 때문이다. 그 규칙은 옳고, 그래서 operator가 소유하는 부모를 따로 둔다.
--
-- 이 행은 「무엇을 하려 했는가」와 「어디서 멈췄는가」를 들고 있다. 멈춤은 정상이다: 로그인 벽, 포화된 하루,
-- 상한 도달 — 셋 다 실패가 아니고, 셋 다 다른 다음 행동을 뜻한다. 그래서 하나의 FAILED로 뭉치지 않는다.
create table review_catch_up_run (
    id                 uuid primary key,
    org_id             uuid        not null,
    seller_account_id  uuid        not null,
    channel_id         uuid        not null,
    data_type          varchar(16) not null,
    state              varchar(24) not null,
    -- 메우려고 나선 구간. 계획이 바뀌어도 「무엇을 하려 했는가」는 남는다.
    requested_from     date        not null,
    requested_through  date        not null,
    -- 다음에 읽을 날. 완전한 창이 하나 끝날 때마다 전진한다.
    cursor_day         date        not null,
    -- 다음 창의 길이. 포화되면 반으로 줄고, 창이 완전하게 끝나면 기본값으로 돌아간다.
    step_days          integer     not null,
    -- 포화로 쪼개는 중인 창의 끝. null이면 쪼개는 중이 아니다.
    split_through      date,
    windows_done       integer     not null default 0,
    rows_observed      integer     not null default 0,
    days_covered       integer     not null default 0,
    -- 멈춘 자리와 이유. 로그인 벽이면 그 창의 시작일이 여기 남아, 재개가 그 창부터 정확히 다시 시작한다.
    paused_window_start date,
    stop_reason        varchar(32),
    started_at         timestamptz not null,
    updated_at         timestamptz not null,
    finished_at        timestamptz,
    -- 한 번의 누름은 한 개의 intent. 더블클릭이 두 개를 만들지 않는다.
    client_request_id  varchar(64) not null,
    constraint review_catch_up_state_known check (state in (
        'RUNNING', 'PAUSED_AUTH', 'COMPLETE', 'STOPPED_SATURATED', 'STOPPED_LIMIT', 'FAILED')),
    constraint review_catch_up_window_sane check (requested_through >= requested_from and step_days >= 1)
);

create unique index uq_review_catch_up_request on review_catch_up_run (org_id, client_request_id);
-- 한 계정 × 한 자료에 살아 있는 intent는 하나다. 둘이면 두 개가 서로의 창을 다시 읽는다.
create unique index uq_review_catch_up_live on review_catch_up_run (seller_account_id, data_type)
    where state in ('RUNNING', 'PAUSED_AUTH');

-- 자식 job: 어느 intent의 것이고, 어느 기간을 읽도록 요청받았는지.
--
-- requested_window_*는 window_*(실제로 덮은 기간)와 다른 칸이다. 전자는 요청이고 후자는 화면이 보여준
-- 사실이며, 둘이 어긋나면 ingest하지 않는다 — 같은 칸에 두면 「요청했으니 그 기간을 읽었다」가 된다.
alter table scheduled_aside_job
  add column catch_up_run_id uuid references review_catch_up_run (id),
  add column requested_window_start date,
  add column requested_window_end date;

create index idx_aside_job_catch_up on scheduled_aside_job (catch_up_run_id, created_at)
  where catch_up_run_id is not null;

comment on column scheduled_aside_job.requested_window_start is
  '이 job이 읽도록 요청받은 기간의 시작(KST). null = 화면이 보여주는 기간을 읽으라는 뜻.';
comment on column scheduled_aside_job.requested_window_end is
  '요청받은 기간의 끝(KST).';
