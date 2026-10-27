-- 「고객은 이렇게 말해요」 — 판매자가 안내마다 적어 두는 고객 표현 (2026-10-27 사장님 결정).
-- 리뷰에 이 표현이 들어 있으면 그 안내가 초안의 근거로 붙는다. 글자 맞추기 검색이 고객 말과 안내 문장의 단어가 달라
-- 놓치던 것을 판매자가 직접 이어 준다. 한 줄에 표현 하나, 비어 있으면 지금까지와 같다.
-- 제안(knowledge_candidate)에도 같은 칸을 둔다: 제안이 등록되면 그 표현이 지식으로 옮겨 간다.
alter table product_knowledge_sources add column customer_phrases text;
alter table org_knowledge_sources add column customer_phrases text;
alter table knowledge_candidate add column customer_phrases text;
