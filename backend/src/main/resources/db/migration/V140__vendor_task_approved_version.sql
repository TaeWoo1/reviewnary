-- 담당자 대행 경로의 승인을 이 PC의 답변 기억에 잇는다 — docs/vendor_server_contract_v1.md §4 (2026-10-22 외부 검토 P2).
-- 판매자가 휴대폰(또는 이 PC의 「승인하고 맡기기」)에서 승인한 문장은 이 PC의 초안 기록에 그 판매자의 버전으로 남고,
-- 그 버전 번호를 여기 적는다. 과거 답변 검색(ReviewReplyAdapter)과 답변 기억(ReviewAnswerMemoryHook)이 이 버전을
-- 판매자가 승인한 답변으로 읽는다 — 이 PC에서 승인한 답글과 같은 무게로.
alter table vendor_reply_task add column approved_draft_version integer;
