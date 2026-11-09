-- 업무 서버(Reviewnary backend) 연결 — docs/standing/server_operation_boundary_v1.md §5 (2026-10-31 오너 결정:
-- 웹 판매자는 Reviewnary 계정 하나로 쓰고, 이 서버에 따로 로그인하거나 PC 연결을 승인하지 않는다).
-- Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode (the hermetic test database).
--
-- 업무 서버의 조직 하나와 이 서버의 판매자 계정 하나를 잇는 기록을 installs의 한 종류(WORKSPACE)로 둔다. 그래야 PC가
-- 하던 일 — 계정의 스토어만 보기, 저장 확인(store_review_acks), 답글 작업 — 을 같은 규칙으로 한다. 차이:
--   kind              PC(기존 행 전부) | WORKSPACE
--   workspace_org_id  업무 서버의 조직 ID. WORKSPACE 행만 가지며 살아 있는 행 사이에서 유일하다. 연결을 끊으면 비운다
--                     (행은 저장 확인 기록 때문에 남는다).
--   token_hash        WORKSPACE 행은 갖지 않는다 — 업무 서버는 설치 토큰이 아니라 서비스 자격 + 조직 ID로 묻는다.
--   public_key        WORKSPACE 행은 갖지 않는다 — Cafe24 중계 봉인(계약 §5)은 PC의 일이다.
-- 판매자 계정도 비밀번호 없이 둘 수 있게 한다: 운영자가 업무 서버 조직에 잇기 위해 만든 계정은 이 서버에 로그인하지
-- 않는다(SellerAccounts.login이 비밀번호 없는 계정을 거절한다).
alter table installs add column kind varchar(16) not null default 'PC';
alter table installs add column workspace_org_id uuid;
alter table installs alter column token_hash drop not null;
alter table installs alter column public_key drop not null;
create unique index installs_workspace_org_uq on installs (workspace_org_id);

alter table seller_accounts alter column password_hash drop not null;
