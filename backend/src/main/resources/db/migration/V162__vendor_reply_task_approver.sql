-- 「답글 등록 요청」을 누른 사람 — docs/standing/server_operation_boundary_v1.md §3 (2026-10-31 외부 검토: 승인 기록에 실제 로그인
-- 사용자가 남지 않았다. 공급자 서버에는 매핑된 판매자 계정만 남아, 한 조직에 직원이 여럿이면 누가 승인했는지 알 수 없다).
--
-- approved_by_user_id   승인을 누른 이 서버의 사용자(users.id). 이 화면에서 누른 승인만 채운다 — 휴대폰 페이지 · 공급자 서버
--                       쪽에서 일어난 승인은 비어 있다(누가 했는지 이 서버가 모른다).
-- approved_locally_at   이 서버가 그 승인을 공급자 서버에서 받아들여진 것으로 확인한 시각.
-- 승인한 문장은 approved_fingerprint(기존), 그 버전은 approved_draft_version(기존)이다. 조직은 org_id(기존)다.
alter table vendor_reply_task add column approved_by_user_id uuid;
alter table vendor_reply_task add column approved_locally_at timestamptz;
