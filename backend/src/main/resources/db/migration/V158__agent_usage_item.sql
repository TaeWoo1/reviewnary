-- 판매자용 처리 건수와 실제 AI 호출 상한을 나눈다 (사장님 2026-10-29).
-- counts_as_item: 이 줄이 판매자가 맡긴 일 한 건(초안 요청 · 에이전트 실행 · 대화 한 번)의 시작인가.
-- 실제 호출 상한은 판매자 · 자동 실행의 모든 줄(판단 · 검토 · 재시도 · 사실 확인)을 센다.
alter table agent_llm_usage add column counts_as_item boolean not null default false;
