-- 등록 실행이 실제 채널로 갔는지, 개발용 모의 채널로 갔는지를 실행 당시 사실로 남긴다 (2026-11-09, 데모 인계 §7.12 5항).
--
-- 지금까지는 실행 행에 그 구분이 없어서, 과거 등록을 화면에 보일 때 현재 설정(모의 채널 목록)으로 추정하는 수밖에 없었다.
-- 설정이 바뀌면 지난 행의 판정도 함께 바뀌므로 그 추정은 금지다. 이 칸은 행을 만들 때 한 번 쓰고 고치지 않는다.
--
--   REAL       실제 채널 어댑터로 보낸 실행
--   SIMULATED  개발용 모의 어댑터로 보낸 실행 — 실제 채널과 고객에게 나가지 않는다
--   UNKNOWN    이 칸이 생기기 전의 행, 또는 보낼 경로를 정하지 않은 행. 실제나 모의로 일괄 보정하지 않는다
--
-- 기존 행은 모두 UNKNOWN이다. 증거 없이 REAL이나 SIMULATED로 바꾸지 않는다.
alter table inquiry_execution
    add column delivery_mode varchar(16) not null default 'UNKNOWN';
alter table inquiry_execution
    add constraint ck_inquiry_execution_delivery_mode check (delivery_mode in ('REAL', 'SIMULATED', 'UNKNOWN'));

alter table review_reply_execution
    add column delivery_mode varchar(16) not null default 'UNKNOWN';
alter table review_reply_execution
    add constraint ck_review_reply_execution_delivery_mode check (delivery_mode in ('REAL', 'SIMULATED', 'UNKNOWN'));

comment on column inquiry_execution.delivery_mode is
    '실행 당시의 전달 경로: REAL 실제 채널 · SIMULATED 개발용 모의 · UNKNOWN 기록 전 행. 만든 뒤 고치지 않는다.';
comment on column review_reply_execution.delivery_mode is
    '실행 당시의 전달 경로: REAL 실제 채널 · SIMULATED 개발용 모의 · UNKNOWN 기록 전 행 또는 보내지 않은 행. 만든 뒤 고치지 않는다.';
