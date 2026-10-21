-- 세션 회수 — docs/vendor_server_contract_v1.md §2. 서명된 세션(JWT)은 그 자체로 취소되지 않으므로 서버가 두 가지를 기억한다.
--   revoked_sessions      로그아웃한 그 세션 하나(jti). 만료 시각이 지나면 지운다 — 그 뒤에는 서명 검사가 이미 거절한다.
--   sessions_not_before   이 시각 전에 발급된 그 사람의 세션 전부(운영자 end-sessions — 유출 대응). 정지와 달리 계정은
--                         그대로이고, 다시 로그인하면 된다.
create table revoked_sessions (
    jti        varchar(64) primary key,
    expires_at timestamp with time zone not null
);
create index revoked_sessions_expiry on revoked_sessions (expires_at);

alter table seller_accounts add column sessions_not_before timestamp with time zone;
alter table delegates add column sessions_not_before timestamp with time zone;
