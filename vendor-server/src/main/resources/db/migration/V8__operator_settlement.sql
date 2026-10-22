-- 운영자 확인 종결 — docs/vendor_server_contract_v1.md §4.2. 담당자가 정지되면 그가 맡던 작업은 UNKNOWN이 되고, 원래는 그
-- 담당자만 결과를 알릴 수 있다. 정지된 계정은 그대로 막고, 관리자(ADMIN) 담당자가 판매자센터에서 실제 등록 여부와 문장을
-- 확인한 뒤 운영 명령으로 종결한다. 누가 · 언제 · 무엇을 보고(report_method) 종결했는지를 남긴다.
alter table reply_tasks add column settled_by uuid references delegates (id);
