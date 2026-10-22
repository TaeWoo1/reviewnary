-- 보류 사유 — docs/vendor_server_contract_v1.md §4.2 (2026-10-23 오너 확정).
-- basis_note: 서버가 들고 있는 그 작업의 보류 사유(JSON) 사본 — 바뀐 근거 · 근거 없는 약속 · 영향받는 문장. 판매자 화면이 읽는다.
alter table vendor_reply_task add column basis_note varchar(4000);
