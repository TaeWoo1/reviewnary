-- 답글 에이전트 실행의 판정 기록 (검토 2026-10-28: 「통과한 초안은 검토 결과가 지워져 나중에 규칙별로 다시 볼 수 없다」).
-- 첫 검토가 걸었던 규칙 번호와, 코드 검사가 걸었던 규칙 번호를 실행이 끝난 뒤에도 남긴다. 평가가 규칙별로 다시 읽는다.
alter table reply_agent_run add column first_failed_rules varchar(200);
alter table reply_agent_run add column checks_failed varchar(200);
