-- 하루 · 조직별 예산 행 (외부 검토 2026-10-29: 「동시 요청에서 호출 상한이 뚫린다」).
-- 호출을 기록하기 전에 이 행을 잠가, 사용량 확인 → 상한 비교 → 기록이 같은 조직 · 같은 날에 한 번에 하나씩만 일어나게 한다.
-- 숫자는 agent_llm_usage가 그대로 센다 — 이 행은 잠금의 자리다.
create table agent_daily_budget (
    id uuid primary key,
    org_id uuid not null,
    usage_date date not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_agent_daily_budget unique (org_id, usage_date)
);
