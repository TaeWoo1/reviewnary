-- vendor_store_binding.acked_cursor: 공급자 서버가 "이 PC가 여기까지 저장했다"는 알림(ack)을 받았다고 답한 마지막 사건 번호 —
-- docs/vendor_server_contract_v1.md §3.2. review_cursor보다 작으면 다음 받기가 새 사건이 없어도 그 위치를 다시 알린다.
-- 이 알림이 서버 쪽 보관 기한(§6)의 시작이라, 한 번 놓친 알림이 새 리뷰가 올 때까지 미뤄지지 않게 한다.
alter table vendor_store_binding add column acked_cursor bigint not null default 0;
