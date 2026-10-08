-- 「여기까지 빠짐없이 확인했다」 — 화면 읽기가 실제로 덮은 기간을, 그 읽기 옆에 적는다.
--
-- 2026-10-08 네이버 live READ가 45건을 가져왔고, 그 성공이 freshness를 10-08로 전진시켰다. 그런데 그 읽기가
-- 덮은 기간은 10-02~10-08 뿐이었다 — 9/2 이후의 공백은 그대로 남아 있었는데, 저장소에는 그 사실을 적을 칸이
-- 없었다. sync_jobs.error_message의 'BOUNDED_WINDOW_DAYS_7'이 유일한 흔적이고, 그건 기간이 아니라 길이다.
--
-- 그래서 두 날짜와 하나의 수용량을 읽기 옆에 적는다. 수용량은 「이 기간에 더 있을 수 있음을 배제할 수 있었나」를
-- 나중에 다시 판단할 수 있게 하려고 남긴다 — 행 수만으로는 45가 전부였는지 잘린 45였는지 알 수 없다.
--
-- 과거 행은 채우지 않는다. 기간을 모르는 읽기에 기간을 지어 넣는 것이 이 패키지가 막으려는 바로 그 일이다.
alter table scheduled_aside_job
  add column window_start date,
  add column window_end date,
  add column observed_capacity integer;

comment on column scheduled_aside_job.window_start is
  '이 읽기가 덮은 기간의 시작(KST 날짜). 화면이 말한 기간이며, 백엔드 시계로 계산하지 않는다. null = 기간을 모르는 읽기.';
comment on column scheduled_aside_job.window_end is
  '이 읽기가 덮은 기간의 끝(KST 날짜).';
comment on column scheduled_aside_job.observed_capacity is
  '한 번에 읽어낼 수 있었던 최대 행 수. observed_count가 여기에 닿으면 「더 있을 수 있음」을 배제할 수 없다.';

-- coverage를 계산할 때 읽는 축: 조직 × recipe × 기간. settled된 것만 후보다.
create index if not exists idx_aside_job_coverage
  on scheduled_aside_job (org_id, recipe, window_end desc)
  where window_end is not null;
