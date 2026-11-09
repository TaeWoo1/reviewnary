-- 열린 문의 재확인의 계정별 대기 (2026-11-08, inquiry_operational_truth_v1 §10.0 · §10.3).
--
-- 재확인은 이제 정기 수집이 끝날 때마다 불린다(Cafe24AnswerRecheckAfterCollection, 기본 꺼짐). 그런데 재확인이 계정 원인으로
-- 멈추면 — 429 · 401/403 · 토큰 없음 · 채널 장애 — 다음 정기 수집 끝에 바로 다시 불려 같은 실패에 요청을 또 쓴다. 이 표가 그
-- 계정을 정해진 시각까지 쉬게 한다. 어떤 숫자도 여기서 정하지 않는다 — 대기 길이는 설정값이다(Cafe24RecheckPolicy, 임시값).
--
--   failures    계정 원인으로 연달아 멈춘 재확인 실행 수. 1부터. 실제 요청을 보내고 계정 원인 없이 끝난 실행이 행을 지운다.
--   last_stop   마지막으로 멈춘 이유(실행 결과 Stop 이름). 계정 원인 넷만 들어온다.
--   failed_at   마지막으로 멈춘 시각.
--   wait_until  이 시각 전에는 그 계정의 재확인이 요청을 하나도 보내지 않는다.
--
-- 계정마다 한 행, 실패가 이어지는 동안만 있다. 다른 계정과 무관하다. 문의별 연속 실패(V185 inquiries 두 칸)와도 따로다 —
-- 계정 원인 실패는 문의를 기다리게 하지 않고, 계정을 기다리게 한다.
--
-- 고객 문구 · 작성자 · 글 번호 · 채널 응답은 저장하지 않는다.
create table inquiry_recheck_account_wait (
    seller_account_id uuid        primary key references seller_accounts (id) on delete cascade,
    failures          integer     not null,
    last_stop         varchar(32) not null,
    failed_at         timestamptz not null,
    wait_until        timestamptz not null,
    constraint ck_inquiry_recheck_account_wait_failures_pos check (failures >= 1),
    constraint ck_inquiry_recheck_account_wait_stop
        check (last_stop in ('RATE_LIMITED', 'AUTH_FAILED', 'AUTH_REJECTED', 'CHANNEL_FAILED')),
    constraint ck_inquiry_recheck_account_wait_after check (wait_until > failed_at)
);

comment on table inquiry_recheck_account_wait is
    '열린 문의 재확인이 계정 원인(429 · 인증 · 토큰 없음 · 장애)으로 멈춘 계정의 대기. wait_until 전에는 요청을 보내지 않는다.';
