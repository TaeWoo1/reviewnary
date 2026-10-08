-- 「어디서 막혔는가」 — outcome 옆에, 닫힌 단어 하나.
--
-- 2026-10-08 첫 historical catch-up이 CONTROLS 단계에서 멈췄고, 제품이 말할 수 있는 것은
-- SURFACE_UNREADABLE 하나였다. 그 단어는 grid가 없는 것, 읽기가 거절된 것, 그리고 그날의 실제 원인인
-- 「selector가 selector가 아니었던 것」을 모두 덮는다 — 고치는 방법이 셋인데 이름이 하나였다.
--
-- 메시지가 아니라 닫힌 목록이다. 백엔드가 같은 목록으로 검증하므로 helper가 텍스트를 보내는 통로가 되지
-- 않는다. 화면은 이 단어를 한 문장으로 바꾼다.
alter table scheduled_aside_job add column failure_code varchar(48);

comment on column scheduled_aside_job.failure_code is
  '정착이 실패였을 때 어디서 막혔는지 — 닫힌 단어 목록(AsideJobFailureCode). 성공이면 null. 메시지가 아니다.';
