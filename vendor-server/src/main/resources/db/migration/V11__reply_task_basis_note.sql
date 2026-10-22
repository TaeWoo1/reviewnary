-- 보류 사유 — docs/vendor_server_contract_v1.md §4.2 (2026-10-23 오너 확정).
-- basis_note: PC가 그 버전을 다시 검사하고 남긴 사유(JSON) — 바뀐 근거 · 근거 없는 약속 · 영향받는 문장. 서버는 판정하지 않고,
-- 형식과 길이, 그리고 인용한 문장이 그 작업의 문장 안에 실제로 있는지만 확인해 담는다.
-- basis_note_version: 그 사유가 말하는 초안 버전. 새 버전이 생기면 사유는 지워진다.
-- 상태 HELD(승인 보류 · 다시 승인 필요) · HOLD_REQUESTED(담당자가 착수한 뒤의 보류 — 담당자 확인 대기)는 기존
-- state varchar(20)에 들어간다.
alter table reply_tasks add column basis_note varchar(4000);
alter table reply_tasks add column basis_note_version integer;
