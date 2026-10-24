-- 담당자가 등록했다고 한 답글이 채널에서 오래 확인되지 않음 — docs/vendor_server_contract_v1.md §4 (2026-10-25 오너 결정).
-- 서버가 판정한다(등록 보고 뒤 새 엑셀 2번에도 답글이 없거나 7일). PC는 받은 값을 「운영팀 확인」으로 보여 줄 뿐이고,
-- 시간이 지났다고 끝난 일로 옮기지 않는다.
alter table vendor_reply_task add column confirm_overdue boolean not null default false;
