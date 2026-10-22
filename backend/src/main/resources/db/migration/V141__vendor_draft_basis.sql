-- 판매자의 자료 · 응대 정책 · 말투가 바뀌면 승인 전 초안을 다시 검사한다 — docs/vendor_server_contract_v1.md §4.2
-- (2026-10-22 외부 검토, 인터뷰 질문 34 「재검증 방식」).
-- reported_draft_basis: 이 PC가 이 스토어에 마지막으로 보고한(policy-check) 세 지문. 바뀌었을 때만 다시 보고한다.
-- rechecked_version · rechecked_basis: 되돌아온 작업을 이 PC가 어느 버전 · 어느 지문에서 검사했는지. 같은 버전을 같은
-- 지문으로 두 번 검사하지 않는다(판매자에게 넘긴 건이 받기마다 다시 돌지 않게).
alter table vendor_store_binding add column reported_draft_basis varchar(200);
alter table vendor_reply_task add column rechecked_version integer;
alter table vendor_reply_task add column rechecked_basis varchar(200);
