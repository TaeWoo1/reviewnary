-- 공급자 서버 연결을 조직마다 하나로 — docs/standing/server_operation_boundary_v1.md §1 (2026-10-31 오너 결정:
-- 서버 전환 전에 PC 연결 코드의 단일 설치 가정을 해소한다).
--
-- V135는 이 표를 "판매자 PC 설치에서만 쓰이며 한 행 이하"로 만들었다. 그래서 설치 토큰을 꺼낼 때 조직을 묻지 않았고,
-- 한 backend에 조직이 둘이면 두 조직이 한 판매자 계정의 토큰을 같이 썼다. 이제 행마다 조직을 가지며 조직당 한 행이다.
--
-- 기존 행(판매자 PC의 한 행)은 그 PC에서 처음 리뷰 받기를 시작한 조직에 붙인다. 받기를 시작한 적이 없으면 그 PC의
-- 가장 먼저 만든 조직에 붙인다 — 판매자 PC는 한 사람의 설치라 둘은 같은 사람이다. 조직이 하나도 없는 DB에는 이 행이
-- 생길 수 없었으므로(연결은 로그인한 판매자만 시작한다) 붙일 곳이 없는 행은 지운다. 한 PC에서 두 조직이 서로 다른
-- 스토어를 받던 경우, 둘째 조직은 연결이 없는 상태가 되어 「이 PC 연결」을 다시 해야 한다.
alter table vendor_install_link add column org_id uuid references organizations (id);

update vendor_install_link
   set org_id = coalesce(
       (select b.org_id from vendor_store_binding b order by b.created_at, b.id limit 1),
       (select o.id from organizations o order by o.created_at, o.id limit 1));

delete from vendor_install_link where org_id is null;

alter table vendor_install_link alter column org_id set not null;

create unique index vendor_install_link_org_uq on vendor_install_link (org_id);
