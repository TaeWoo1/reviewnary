-- Seller accounts, invites and installs — docs/vendor_server_contract_v1.md §2 (D2: invite-only).
-- Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode (the hermetic test database).
-- Every secret here is stored as a SHA-256 hex digest or a BCrypt hash; no token or code is kept in plain text.

create table seller_accounts (
    id            uuid primary key,
    email         varchar(254) not null unique,
    password_hash varchar(100) not null,
    status        varchar(16)  not null,
    created_at    timestamp with time zone not null,
    suspended_at  timestamp with time zone
);

create table seller_invites (
    code_hash   char(64) primary key,
    email       varchar(254) not null,
    expires_at  timestamp with time zone not null,
    redeemed_at timestamp with time zone
);

-- One row per seller PC. public_key is the install's X25519 public key (32 bytes): Cafe24 relay results are
-- sealed to it (contract §5). The private key never leaves the PC.
create table installs (
    id           uuid primary key,
    account_id   uuid not null references seller_accounts (id),
    token_hash   char(64) not null unique,
    public_key   bytea not null,
    device_name  varchar(80) not null,
    app_version  varchar(40),
    created_at   timestamp with time zone not null,
    expires_at   timestamp with time zone not null,
    revoked_at   timestamp with time zone,
    last_used_at timestamp with time zone
);

create index installs_account_idx on installs (account_id);
