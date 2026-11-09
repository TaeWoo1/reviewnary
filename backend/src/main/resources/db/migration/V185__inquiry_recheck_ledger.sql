-- 열린 문의 재확인의 재시도 기록 · 계정별 하루 요청 예산 · 계정별 실행 잠금 (2026-11-08, inquiry_operational_truth_v1 §10.0 · §10.1 · §10.3).
--
-- 재확인 실행부(Cafe24AnswerRecheckRunner)는 지금까지 「실행 한 번」의 요청 한도만 알았다. 서버를 다시 띄우거나 같은 날 두 번
-- 부르면 한도를 새로 받았고, 같은 계정의 실행 둘이 겹치면 같은 글을 두 번 물었다. 그리고 그 문의 탓으로 확인에 실패한 문의가
-- 다음 실행에도 맨 앞에서 다시 요청을 썼다. 이 마이그레이션은 그 셋을 DB에 둔다. 어떤 숫자도 여기서 정하지 않는다 —
-- 하루 상한 · 재시도 간격 · 잠금 유지 시간은 설정값이다(Cafe24RecheckPolicy, 임시값).
--
-- 1) 문의별 연속 실패 — inquiries 두 칸
--   answer_recheck_failures   그 문의 원인(ARTICLE)으로 확인에 연달아 실패한 횟수. 확인에 성공하면 0.
--                             계정 원인 실패(429 · 401/403 · 장애 · 종류 모를 오류) · 한도가 막아 묻지 않은 것은 올리지도
--                             되돌리지도 않는다.
--   answer_recheck_failed_at  그 문의 원인 실패가 마지막으로 난 때. 다음 시도 가능 날짜의 기준(한국 날짜 + 간격).
--                             comment_check_attempted_at은 계정 원인 실패에도 움직이므로 기준으로 쓰지 않는다.
--   기존 문의는 0 · null — 실패 이력을 지어내지 않는다.
--
-- 2) 계정별 하루 요청 수 — inquiry_recheck_daily_usage
--   한국 날짜(usage_day)마다 한 행. 요청을 보내기 전에 한 건씩 「requests < 상한」 조건으로 늘린다 — 늘지 않았으면 보내지
--   않는다. 그래서 실패한 요청도 세고, 끝나고 합산하는 방식이 아니므로 상한을 넘지 않는다. 토큰 갱신 · 글 다시 읽기 · 댓글
--   보유 확인 · 댓글 읽기가 모두 이 칸을 거친다.
--
-- 3) 계정별 실행 잠금 — inquiry_recheck_run_lock
--   계정마다 한 행. 실행은 시작할 때 잡고 끝나면 지운다. 요청마다 lease_until을 늘린다. 프로세스가 죽어 지우지 못했으면
--   lease_until이 지난 뒤 다음 실행이 넘겨받는다 — 영구히 잠기지 않는다. 다른 계정의 행과는 무관하다.
--
-- 고객 문구 · 작성자 · 글 번호는 어디에도 저장하지 않는다.
alter table inquiries
    add column answer_recheck_failures integer not null default 0,
    add column answer_recheck_failed_at timestamptz;

alter table inquiries
    add constraint ck_inquiries_answer_recheck_failures_nonneg
        check (answer_recheck_failures >= 0),
    add constraint ck_inquiries_answer_recheck_failed_pair
        check ((answer_recheck_failures = 0) = (answer_recheck_failed_at is null));

comment on column inquiries.answer_recheck_failures is
    '재확인이 그 문의 원인으로 연달아 실패한 횟수. 성공하면 0. 계정 원인 실패 · 미시도는 바꾸지 않는다.';
comment on column inquiries.answer_recheck_failed_at is
    '그 문의 원인 실패가 마지막으로 난 시각. 재시도 간격의 기준. 실패 횟수가 0이면 null.';

create table inquiry_recheck_daily_usage (
    seller_account_id uuid        not null references seller_accounts (id) on delete cascade,
    usage_day         date        not null,
    requests          integer     not null,
    updated_at        timestamptz not null,
    primary key (seller_account_id, usage_day),
    constraint ck_inquiry_recheck_daily_usage_nonneg check (requests >= 0)
);

comment on table inquiry_recheck_daily_usage is
    '열린 문의 재확인이 계정마다 한국 날짜 하루에 보낸 채널 HTTP 요청 수. 보내기 전에 센다.';

create table inquiry_recheck_run_lock (
    seller_account_id uuid        primary key references seller_accounts (id) on delete cascade,
    holder            uuid        not null,
    acquired_at       timestamptz not null,
    lease_until       timestamptz not null
);

comment on table inquiry_recheck_run_lock is
    '계정마다 재확인 실행 하나. lease_until이 지나면 다음 실행이 넘겨받는다(비정상 종료 복구).';
