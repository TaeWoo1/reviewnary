-- 답글 에이전트의 단계별 사용량 (product-owner 2026-10-28: 「사용량은 데모 돌려 보면서 가늠해야 할 것 같아. 모니터링 잘 할 수
-- 있도록만 해 줘」). 한 호출이 어느 실행의 어느 단계였고, 무엇이 시작했고, 얼마나 걸렸고, 토큰을 얼마나 썼는지.
-- 모두 nullable: 이 열이 생기기 전의 행과, 단계가 없는 기존 호출(PLAN · JUDGE · DRAFT 등)은 비어 있다.
alter table agent_llm_usage add column step varchar(16);
alter table agent_llm_usage add column trigger_kind varchar(16);
alter table agent_llm_usage add column elapsed_ms integer;
alter table agent_llm_usage add column input_tokens integer;
alter table agent_llm_usage add column output_tokens integer;

create index if not exists ix_agent_llm_usage_org_date_kind on agent_llm_usage (org_id, usage_date, kind);
