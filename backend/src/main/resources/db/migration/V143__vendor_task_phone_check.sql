-- 휴대폰에서 고친 문장을 PC가 미리 검사한다 — docs/vendor_server_contract_v1.md §4.2 (2026-10-23).
-- pc_checked_version: 이 PC가 휴대폰 수정본을 검사해 서버에 기록한 마지막 초안 버전. 같은 버전을 받기마다 다시 묻지 않는다.
-- 판매자가 그 결과(근거 없는 약속)를 보고 승인했다면, 나중에 근거가 바뀌어도 같은 약속으로는 다시 보류하지 않는다.
alter table vendor_reply_task add column pc_checked_version integer;
