-- 담당자 구글 로그인 — docs/vendor_server_contract_v1.md §2 (D3 개정, 오너 결정 2026-10-26).
--
-- 담당자는 둘 중 한 방법으로만 들어온다: 비밀번호 + 인증 앱(기존), 또는 초대받은 주소의 구글 계정. 구글로 등록한 사람은
-- 비밀번호 · TOTP 비밀이 없고 구글이 주는 계정 번호(sub)로만 찾는다 — 주소는 바뀔 수 있어도 sub는 바뀌지 않는다.
alter table delegates add column google_sub varchar(255);
create unique index delegates_google_sub_idx on delegates (google_sub);
alter table delegates alter column password_hash drop not null;
alter table delegates alter column totp_secret drop not null;
alter table delegates add constraint delegates_one_sign_in check (
    (google_sub is not null and password_hash is null and totp_secret is null)
    or (google_sub is null and password_hash is not null and totp_secret is not null));

-- 구글로 다녀오는 한 번. 쿠키가 없는 서버라 브라우저 묶음은 binding이 맡는다: 시작한 탭이 sessionStorage에 둔 값의
-- 해시만 여기 있고, 돌아온 탭이 그 값을 내야 끝난다(남이 보낸 콜백 링크로는 끝나지 않는다). state · handoff · binding은
-- 해시만 둔다. code_verifier(PKCE)와 nonce는 구글에 보내야 하므로 그대로 두되 10분 뒤 지운다(Retention).
-- 구글이 확인해 준 계정(google_sub · google_email)은 끝내기 전까지만 여기 있다.
create table delegate_google_attempts (
    state_hash    char(64) primary key,
    purpose       varchar(8) not null,
    invite_hash   char(64),
    binding_hash  char(64) not null,
    nonce         varchar(64) not null,
    code_verifier varchar(128) not null,
    created_at    timestamp with time zone not null,
    expires_at    timestamp with time zone not null,
    google_sub    varchar(255),
    google_email  varchar(254),
    handoff_hash  char(64),
    finished_at   timestamp with time zone
);
create unique index delegate_google_attempts_handoff_idx on delegate_google_attempts (handoff_hash);
