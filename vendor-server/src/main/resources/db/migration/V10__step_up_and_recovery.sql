-- 판매자 추가 인증 · 계정 복구 — docs/vendor_server_contract_v1.md §2.
--
-- step_up_codes: 새 PC 연결처럼 계정 밖으로 새 통로를 여는 동작 앞에서 계정 이메일로 보내는 일회용 코드. 코드는 그
-- 계정 · 그 세션(jti) · 그 요청(target_hash = PC 연결 코드의 해시) 하나에 묶이고, 맞힌 뒤 그 동작 한 번에 쓰이면
-- 끝난다(spent_at). 다시 받아도 틀린 횟수는 같은 요청의 코드들을 더해 센다. 코드는 해시만 둔다.
create table step_up_codes (
    id              uuid primary key,
    account_id      uuid not null references seller_accounts (id),
    session_id      varchar(64) not null,
    purpose         varchar(16) not null,
    target_hash     char(64) not null,
    code_hash       char(64) not null,
    issued_at       timestamp with time zone not null,
    expires_at      timestamp with time zone not null,
    failed_attempts integer not null default 0,
    verified_at     timestamp with time zone,
    spent_at        timestamp with time zone
);
create index step_up_codes_account_idx on step_up_codes (account_id, issued_at);

-- 이 주소로 보낸 코드를 처음 맞힌 때. 초대 링크를 받았다는 것은 주소 소유의 증거가 아니다(운영자가 링크를 출력해
-- 직접 전한다) — 이 시각이 그 증거다. 복구로 주소가 바뀌면 다시 비운다.
alter table seller_accounts add column email_verified_at timestamp with time zone;

-- 복구 초대: 기존 계정에 묶인 초대(account_id). 쓰면 새 계정을 만들지 않고 그 계정의 비밀번호(와 바뀐 주소)를 정하고,
-- 그 계정의 세션을 모두 끝낸다. 스토어 · 작업 · PC 연결 기록은 그대로다.
alter table seller_invites add column account_id uuid references seller_accounts (id);
