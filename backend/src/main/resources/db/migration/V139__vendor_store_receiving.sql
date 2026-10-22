-- 판매자 PC의 '담당자가 올리는 네이버 리뷰' 구역 — docs/vendor_server_contract_v1.md §3.2 (2026-10-22 시안 확인 · 보완 조건).
--
-- paused          판매자가 이 스토어의 받기를 멈췄다. 자동 받기와 「지금 받기」 모두 건너뛴다. 묶음과 커서는 그대로다.
-- last_upload_at  서버가 마지막으로 알려 준, 담당자가 이 스토어의 엑셀을 마지막으로 올린 시각. 'PC가 서버에 물어본 때'
--                 (last_pulled_at)와 따로 보여 준다 — 방금 물어봤어도 담당자가 오래된 파일만 올렸다면 최신 리뷰가 아니다.
-- last_failure    마지막 받기가 이 스토어에서 실패한 이유(닫힌 코드)와 시각. 다음 받기가 성공하면 지운다.
alter table vendor_store_binding add column paused boolean not null default false;
alter table vendor_store_binding add column last_upload_at timestamptz;
alter table vendor_store_binding add column last_failure varchar(32);
alter table vendor_store_binding add column last_failure_at timestamptz;
