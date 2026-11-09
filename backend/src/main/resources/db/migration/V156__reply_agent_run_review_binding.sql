-- 검토한 바로 그 초안과 근거만 저장한다 (검토 2026-10-28: 「저장 함수가 검토 통과·검토 뒤 변경 여부를 확인하지 않는다」).
-- inputs_hash: 초안을 쓸 때 본 리뷰·근거·문구의 지문. passed_hash: 검토를 통과한 초안 본문과 그때 근거의 지문.
-- 저장은 지금 초안과 지금 근거의 지문이 passed_hash와 같을 때만 한다.
alter table reply_agent_run add column inputs_hash varchar(64);
alter table reply_agent_run add column passed_hash varchar(64);
-- judged_hash: 범주를 판단할 때 본 리뷰 본문의 지문. 초안을 쓸 때 본문이 달라졌으면 다시 판단해야 한다(2차 검토).
alter table reply_agent_run add column judged_hash varchar(64);
-- also_categories: 첫 범주 말고 리뷰에 함께 있는 범주(버리지 않는다). points: 답글이 다뤄야 할 점(판단 단계가 짚음, G8).
alter table reply_agent_run add column also_categories varchar(40);
alter table reply_agent_run add column points text;
