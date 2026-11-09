-- 승인한 사람 — docs/standing/server_operation_boundary_v1.md §3 (2026-10-31 외부 검토: 업무 서버가 맡긴 승인에는 이 서버의
-- 판매자 계정만 남아, 한 조직에 사람이 여럿이면 누가 승인했는지 알 수 없다).
-- Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode (the hermetic test database).
--
-- approved_by_ref  승인을 보낸 쪽이 알려 준 승인자 참조. 업무 서버는 'workspace-user:<그 서버의 사용자 ID>'를 보낸다.
--                  이 서버는 그 사람을 모르고, 이름 · 이메일을 받지 않는다 — 불투명한 참조만 남긴다. 승인의 정본 기록
--                  (사용자 · 조직 · 문장 버전 · 시각)은 업무 서버에 있다. approved_by(계정)는 그대로다.
alter table reply_tasks add column approved_by_ref varchar(80);
