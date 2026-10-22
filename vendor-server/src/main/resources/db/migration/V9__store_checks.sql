-- 스토어 확인 절차 — docs/vendor_server_contract_v1.md §2. 외부 판매자의 리뷰가 이 서버에 들어오기 전에 관리자(ADMIN) 담당자가
-- 두 가지를 확인하고 누가 · 언제 · 무엇을 보고 확인했는지 남긴다.
--   1. 판매자 소유: 이 스토어가 그 판매자 계정 주인의 스토어다 (stores.owner_*).
--   2. 담당자 배정: 배정된 담당자 본인의 네이버 계정이 판매자센터에서 이 스토어의 부매니저로 들어가 있다 (store_assignments.checked_*).
-- 둘 다 확인되기 전의 배정은 열려 있어도 일하지 못한다 — 스토어 목록 · 업로드 · 작업 어디에도 보이지 않는다. 배정을 끝내고
-- 다시 하면 새 행이라 다시 확인한다. 이미 있는 행은 확인 전으로 남는다(닫힌 쪽으로 실패).
alter table stores add column owner_checked_by uuid references delegates (id);
alter table stores add column owner_checked_at timestamp with time zone;
alter table stores add column owner_check_basis varchar(32);
alter table store_assignments add column checked_by uuid references delegates (id);
alter table store_assignments add column checked_at timestamp with time zone;
