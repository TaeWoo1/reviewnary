-- 한 스토어에 붙는 반입 경로 — docs/standing/server_operation_boundary_v1.md §5 「한 스토어, 여러 경로」 (2026-10-31 외부 검토:
-- 담당자 반입용 계정과 공식 API 계정이 같은 채널에 둘 있으면 답할 계정을 고르지 못해 답글 화면이 막힌다).
--
-- review_route  이 계정이 그 스토어에 덧붙은 반입 경로일 때만 그 이름을 갖는다. 지금은 DELEGATE_UPLOAD(담당자가 올린
--               네이버 리뷰를 받으려고 「리뷰 받기 시작」이 만든 계정) 하나다. 비어 있으면 그 스토어의 자기 연결(공식 API ·
--               판매자 파일 업로드)이다. 답할 계정은 자기 연결이 있으면 그것이고, 덧붙은 경로만 있으면 그 경로다.
alter table seller_accounts add column review_route varchar(24);
