-- 조직 ↔ 판매자 계정 연결의 1:1을 계정 쪽에서도 DB가 지킨다 — docs/standing/server_operation_boundary_v1.md §5
-- (2026-10-31 외부 검토: 조회 뒤 생성만으로는 같은 계정을 두 조직에 동시에 이을 때 사전 검사를 둘 다 통과할 수 있다).
-- Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode (the hermetic test database).
--
-- workspace_account_id  살아 있는 WORKSPACE 연결에서만 account_id와 같고, 그 밖에는 비어 있다. 유일 색인이 걸려 있어서 한 계정은
--                       살아 있는 조직 연결을 하나만 가진다. 조건부 색인(where …)은 H2가 못 쓰므로 칸을 따로 둔다 —
--                       조직 쪽 workspace_org_id(V13)와 같은 방식이다. 연결을 끊으면 둘 다 비운다.
alter table installs add column workspace_account_id uuid;

update installs set workspace_account_id = account_id
 where kind = 'WORKSPACE' and workspace_org_id is not null and revoked_at is null;

create unique index installs_workspace_account_uq on installs (workspace_account_id);
