-- Delegates, stores and assignments — docs/vendor_server_contract_v1.md §2 (D2: invite-only, D3: TOTP required).
-- Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode (the hermetic test database).
-- Passwords are BCrypt, invite and recovery codes are SHA-256, and the TOTP secret — which has to be read back to
-- check a code — is AES-GCM sealed under VENDOR_TOTP_KEY. Nothing here is a credential in plain text.

-- A seller's store that delegates may be assigned to. The label is the operator's name for it; no channel
-- identifier is kept — the delegate reaches the store with their own invited account, never through this row.
create table stores (
    id         uuid primary key,
    account_id uuid not null references seller_accounts (id),
    channel    varchar(16) not null,
    label      varchar(80) not null,
    created_at timestamp with time zone not null
);

create index stores_account_idx on stores (account_id);

-- One row per person (D3: no shared accounts). role is DELEGATE or ADMIN.
create table delegates (
    id               uuid primary key,
    email            varchar(254) not null unique,
    password_hash    varchar(100) not null,
    totp_secret      bytea not null,
    totp_last_step   bigint,
    role             varchar(16) not null,
    status           varchar(16) not null,
    created_at       timestamp with time zone not null,
    suspended_at     timestamp with time zone
);

-- The invite is not spent when the invitee chooses a password: that only stages a password and a TOTP secret on
-- the invite. It is spent when the first code from the authenticator app proves the secret arrived, and only then
-- does the delegate row exist. Staging again (a lost QR screen) replaces the staged pair.
create table delegate_invites (
    code_hash           char(64) primary key,
    email               varchar(254) not null,
    role                varchar(16) not null,
    expires_at          timestamp with time zone not null,
    staged_password     varchar(100),
    staged_totp_secret  bytea,
    redeemed_at         timestamp with time zone
);

create table delegate_recovery_codes (
    delegate_id uuid not null references delegates (id),
    code_hash   char(64) not null,
    used_at     timestamp with time zone,
    primary key (delegate_id, code_hash)
);

-- An assignment is ended, never deleted, so who could see a store and when stays answerable.
create table store_assignments (
    id          uuid primary key,
    store_id    uuid not null references stores (id),
    delegate_id uuid not null references delegates (id),
    assigned_at timestamp with time zone not null,
    ended_at    timestamp with time zone
);

create index store_assignments_delegate_idx on store_assignments (delegate_id);
create index store_assignments_store_idx on store_assignments (store_id);
