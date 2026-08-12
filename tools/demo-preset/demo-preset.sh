#!/usr/bin/env bash
#
# tools/demo-preset/demo-preset.sh — reproduce the two SellerOps demo states with one short command.
#
#   demo-preset.sh status      what the demo org's channel connection looks like right now (READ-ONLY)
#   demo-preset.sh snapshot    capture the current state as the CONNECTED baseline
#   demo-preset.sh fresh       put the channel back before its first connection (auto-snapshots first)
#   demo-preset.sh connected   restore the snapshotted baseline
#
# WHAT THIS IS FOR. On an existing demo account the "connect this channel for the first time" CTA is
# unreachable: /connect reads a channel card's status from the seller account when one exists, so ANY
# account at all — even a never-finished PENDING one with no credential — turns the card's button into
# 연결 관리 / 연결 계속하기 and routes to the account workspace instead of the first-connection page. The
# only way back to the first-connection entry is for the org to genuinely have no API-mode account on that
# channel. That is what `fresh` produces, and `connected` undoes.
#
# SAFETY.
#   - Local/dev only: a non-loopback database host, or a prod-looking environment, fails closed.
#   - Nothing is scoped wider than (demo org × one channel). No TRUNCATE, no unqualified DELETE, no DROP.
#   - Rows carrying real operational content (inquiries, review-import plans, import batches, and by
#     default collected orders) are never deleted — if the account owns any, `fresh` ABORTS and says so.
#   - Every removed row is archived first, inside the database (see preset-lib.sql), so `connected` puts
#     back the same bytes — including the encrypted credential row, whose ciphertext never leaves SQL.
#   - No credential value, ciphertext, IV or key id is ever selected into the shell. Credential state is
#     reported as a yes/no only.
#   - Before/after state is printed for every mutating run, and the org's content-row counts (reviews,
#     inquiries, products, order summaries, collected orders) must be identical across the run.
#
# COUPANG: THE DATABASE IS NOT THE MARKETPLACE. `fresh` resets SellerOps' side only. The seller's WING
# Open API key lives at Coupang and is untouched by anything here. A genuine "first key issuance" demo
# also needs the marketplace-side preconditions in README.md § WING checklist.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_SQL="$HERE/preset-lib.sql"
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT

# ---- configuration (local defaults; override by env) --------------------------------------------------
PGHOST="${SELLEROPS_DEMO_PGHOST:-127.0.0.1}"
PGPORT="${SELLEROPS_DEMO_PGPORT:-5432}"
PGUSER="${SELLEROPS_DEMO_PGUSER:-sellerops}"
PGDATABASE="${SELLEROPS_DEMO_PGDATABASE:-sellerops}"
export PGPASSWORD="${SELLEROPS_DEMO_PGPASSWORD:-${PGPASSWORD:-sellerops_local_pw}}"
DEMO_EMAIL="${SELLEROPS_DEMO_USER_EMAIL:-demo@sellerops.ai}"
CHANNEL_CODE="${SELLEROPS_DEMO_CHANNEL:-COUPANG}"
INCLUDE_ORDERS="false"

die()  { printf 'FAIL-CLOSED: %s\n' "$*" >&2; exit 1; }
say()  { printf '%s\n' "$*"; }
rule() { printf -- '-------------------------------------------------------------------------\n'; }

# The archive DDL is idempotent, so every run would otherwise emit a wall of "already exists, skipping"
# notices that buries the operator-facing output. Warnings and errors are unaffected.
export PGOPTIONS="${PGOPTIONS:-} -c client_min_messages=warning"

psql_base=(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1)
q()   { "${psql_base[@]}" -Atc "$1"; }      # scalar / tab-separated rows, for the script
tbl() { "${psql_base[@]}" -c "$1"; }        # formatted table, for the operator
tx()  { "${psql_base[@]}" -1 -q -f "$1"; }  # a whole file in ONE transaction

# ---- fail-closed environment gate ---------------------------------------------------------------------
guard_environment() {
    case "$PGHOST" in
        127.0.0.1|localhost|::1) : ;;
        *) die "database host '$PGHOST' is not loopback. This preset runs against a local/dev database only." ;;
    esac
    local var value
    for var in SELLEROPS_ENV APP_ENV SPRING_PROFILES_ACTIVE NODE_ENV DEPLOY_ENV; do
        value="$(printf '%s' "${!var:-}" | tr '[:upper:]' '[:lower:]')"
        case "$value" in
            *prod*|*staging*|*stage*)
                die "$var='${!var}' looks like a deployed environment. Demo presets are local/dev only." ;;
        esac
    done
    command -v psql >/dev/null 2>&1 || die "psql not found on PATH."
    [ -f "$LIB_SQL" ] || die "missing $LIB_SQL"
}

# ---- identity resolution (everything below is scoped to exactly these two ids) -------------------------
resolve_ids() {
    local n
    n="$(q "select count(*) from organizations o join users u on u.org_id = o.id where u.email = '$DEMO_EMAIL'")"
    [ "$n" = "1" ] || die "demo user '$DEMO_EMAIL' resolves to $n organizations — refusing to guess. Is this the demo database?"
    ORG_ID="$(q "select o.id from organizations o join users u on u.org_id = o.id where u.email = '$DEMO_EMAIL'")"
    ORG_NAME="$(q "select name from organizations where id = '$ORG_ID'")"

    CHANNEL_ID="$(q "select id from channels where code = '$CHANNEL_CODE'")"
    [ -n "$CHANNEL_ID" ] || die "no channel with code '$CHANNEL_CODE'."
    CHANNEL_NAME="$(q "select name_ko from channels where id = '$CHANNEL_ID'")"

    # The API-mode account (is_file_upload = false) is the one the connect CTA keys off. V36 guarantees at
    # most one per (org, channel); refuse to act if that invariant is somehow broken on this database.
    n="$(q "select count(*) from seller_accounts where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload = false")"
    [ "$n" -le 1 ] || die "$n API-mode accounts exist for this (org, channel); refusing to guess which is the demo one."
    ACCOUNT_ID="$(q "select id from seller_accounts where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload = false")"
}

# ---- reporting ----------------------------------------------------------------------------------------
# Content-row counts for the WHOLE org. These must not move across any preset run; comparing them before
# and after is the mechanical proof that nothing broad was deleted.
content_fingerprint() {
    q "select
         (select count(*) from reviews               where org_id='$ORG_ID') || '/' ||
         (select count(*) from inquiries             where org_id='$ORG_ID') || '/' ||
         (select count(*) from products              where org_id='$ORG_ID') || '/' ||
         (select count(*) from order_daily_summaries where org_id='$ORG_ID') || '/' ||
         (select count(*) from channel_orders        where org_id='$ORG_ID')"
}

print_state() {
    say "[$1] org: $ORG_NAME · channel: $CHANNEL_NAME ($CHANNEL_CODE) · db: $PGDATABASE@$PGHOST:$PGPORT"
    tbl "select
            coalesce((select connection_status from seller_accounts
                       where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload=false),
                     '(no account)')                                                     as api_account,
            (select status from channels where id='$CHANNEL_ID')                          as catalog,
            (select count(*) > 0 from connector_credentials cc
               join seller_accounts sa on sa.id = cc.seller_account_id
              where sa.org_id='$ORG_ID' and sa.channel_id='$CHANNEL_ID')                  as credential,
            (select count(*) from channel_connection_status ccs
               join seller_accounts sa on sa.id = ccs.seller_account_id
              where sa.org_id='$ORG_ID' and sa.channel_id='$CHANNEL_ID')                  as health,
            (select count(*) from sync_jobs    where org_id='$ORG_ID' and channel_id='$CHANNEL_ID') as jobs,
            (select count(*) from sync_cursors where org_id='$ORG_ID' and channel_id='$CHANNEL_ID') as cursors,
            (select count(*) from channel_orders where org_id='$ORG_ID' and channel_id='$CHANNEL_ID') as orders"
    say "org content rows (reviews/inquiries/products/order-summaries/collected-orders): $(content_fingerprint)"
}

archived_rows() { q "select demo_preset.archived_count('$1','$ORG_ID','$CHANNEL_ID')"; }

# READ-ONLY existence probe: never creates the schema, so `status` stays a pure read.
have_snapshot() {
    local reg
    reg="$(q "select coalesce(to_regclass('demo_preset.a_channel_status')::text,'')")"
    [ -n "$reg" ] || return 1
    [ "$(q "select count(*) from demo_preset.a_channel_status
             where preset_org='$ORG_ID' and preset_channel='$CHANNEL_ID'")" = "1" ]
}

# Rows that would be orphaned by removing the account AND that carry real operational content. The preset
# never deletes these — it stops instead.
BLOCKING_TABLES=(inquiries inquiry_work_item inquiry_import_batch inquiry_work_item_dismissal_batch
                 cafe24_oauth_state review_import_launch review_import_plan)

check_blockers() {
    local blocked="" t n
    for t in "${BLOCKING_TABLES[@]}"; do
        n="$(q "select count(*) from $t where seller_account_id='$ACCOUNT_ID'")"
        [ "$n" = "0" ] || blocked="$blocked  $t=$n"
    done
    if [ "$INCLUDE_ORDERS" != "true" ]; then
        n="$(q "select count(*) from channel_orders where seller_account_id='$ACCOUNT_ID'")"
        [ "$n" = "0" ] || blocked="$blocked  channel_orders=$n (re-run with --include-collected-orders to archive+clear them)"
    fi
    [ -z "$blocked" ] || die "the account owns rows this preset will not delete:$blocked"
}

# `fresh` deletes; the snapshot is the only copy. Refuse when the live state carries something the archive
# does not — a different account row, or a credential the archive lacks (what a real live connection during
# the demo produces). `--resnapshot` is the deliberate "make the current state the new baseline" answer.
guard_snapshot_covers_live() {
    local archived_acc live_cred archived_cred
    archived_acc="$(q "select coalesce((select id::text from demo_preset.a_seller_accounts
                        where preset_org='$ORG_ID' and preset_channel='$CHANNEL_ID' limit 1),'')")"
    [ "$archived_acc" = "$ACCOUNT_ID" ] || die \
        "the snapshot holds a different account than the one on the database now. Re-run with --resnapshot to make the current state the baseline (the old snapshot is then gone), or run 'connected' first."
    live_cred="$(q "select count(*) from connector_credentials where seller_account_id='$ACCOUNT_ID'")"
    archived_cred="$(archived_rows connector_credentials)"
    [ "$live_cred" -le "$archived_cred" ] || die \
        "a credential is on file that the snapshot does not hold — clearing now would destroy it with no way back. Re-run with --resnapshot to make the current (connected) state the baseline."
}

# ---- commands -----------------------------------------------------------------------------------------
cmd_status() {
    print_state now
    if have_snapshot; then
        say "snapshot: present (account=$(archived_rows seller_accounts) credential=$(archived_rows connector_credentials) health=$(archived_rows channel_connection_status) jobs=$(archived_rows sync_jobs))"
    else
        say "snapshot: none — 'fresh' takes one automatically before it clears anything."
    fi
}

cmd_snapshot() {
    local f="$TMPD/snap.sql" self acct chan_or_acct orders events
    if [ -n "$ACCOUNT_ID" ]; then
        self="id = ''$ACCOUNT_ID''"
        acct="seller_account_id = ''$ACCOUNT_ID''"
        chan_or_acct="org_id = ''$ORG_ID'' and (channel_id = ''$CHANNEL_ID'' or seller_account_id = ''$ACCOUNT_ID'')"
        orders="$acct"
        events="channel_order_id in (select id from channel_orders where seller_account_id = ''$ACCOUNT_ID'')"
    else
        # No account to snapshot: archive the channel-scoped collection state only, and record an empty
        # account slot so `connected` restores the same "no account" baseline it was told to keep.
        self="false"; acct="false"; orders="false"; events="false"
        chan_or_acct="org_id = ''$ORG_ID'' and channel_id = ''$CHANNEL_ID''"
    fi
    {
        echo "select demo_preset.snap('seller_accounts',            '$ORG_ID','$CHANNEL_ID', '$self');"
        echo "select demo_preset.snap('connector_credentials',      '$ORG_ID','$CHANNEL_ID', '$acct');"
        echo "select demo_preset.snap('channel_connection_status',  '$ORG_ID','$CHANNEL_ID', '$acct');"
        echo "select demo_preset.snap('connector_alerts',           '$ORG_ID','$CHANNEL_ID', '$acct');"
        echo "select demo_preset.snap('sync_schedules',             '$ORG_ID','$CHANNEL_ID', '$acct');"
        echo "select demo_preset.snap('account_session_slot',       '$ORG_ID','$CHANNEL_ID', '$acct');"
        echo "select demo_preset.snap('sync_jobs',                  '$ORG_ID','$CHANNEL_ID', '$chan_or_acct');"
        echo "select demo_preset.snap('sync_cursors',               '$ORG_ID','$CHANNEL_ID', '$chan_or_acct');"
        echo "select demo_preset.snap('channel_orders',             '$ORG_ID','$CHANNEL_ID', '$orders');"
        echo "select demo_preset.snap('channel_order_status_events','$ORG_ID','$CHANNEL_ID', '$events');"
        echo "delete from demo_preset.a_channel_status where preset_org='$ORG_ID' and preset_channel='$CHANNEL_ID';"
        echo "insert into demo_preset.a_channel_status (preset_org, preset_channel, channel_status, archived_at)"
        echo "select '$ORG_ID','$CHANNEL_ID', status, now() from channels where id='$CHANNEL_ID';"
    } > "$f"
    tx "$LIB_SQL" >/dev/null
    tx "$f" >/dev/null
    say "snapshot captured: account=$(archived_rows seller_accounts) credential=$(archived_rows connector_credentials) health=$(archived_rows channel_connection_status) jobs=$(archived_rows sync_jobs) cursors=$(archived_rows sync_cursors) orders=$(archived_rows channel_orders)"
}

cmd_fresh() {
    local before after f="$TMPD/fresh.sql"
    before="$(content_fingerprint)"
    print_state before
    rule

    if [ -z "$ACCOUNT_ID" ]; then
        # Already past the account half; still make sure the catalog row reads 연결하기 rather than 관리,
        # which a stale catalog status would otherwise leave on the card.
        tx "$LIB_SQL" >/dev/null
        q "update channels set status='AVAILABLE', updated_at=now() where id='$CHANNEL_ID' and status <> 'AVAILABLE'" >/dev/null
        say "no API-mode $CHANNEL_CODE account exists — already FRESH."
    else
        check_blockers
        if have_snapshot; then
            # The snapshot is the ONLY copy of whatever `fresh` is about to delete. If the live state has
            # moved on since it was taken — a different account, or a credential that the archive does not
            # hold (exactly what a real live connection during the demo produces) — deleting now would
            # destroy state nothing can restore. Stop and make the operator choose.
            guard_snapshot_covers_live
            say "keeping the existing snapshot (pass --resnapshot to make the CURRENT state the baseline)."
        else
            tx "$LIB_SQL" >/dev/null
            cmd_snapshot
        fi

        {
            if [ "$INCLUDE_ORDERS" = "true" ]; then
                echo "delete from channel_order_status_events where org_id='$ORG_ID' and channel_order_id in (select id from channel_orders where seller_account_id='$ACCOUNT_ID');"
                echo "delete from channel_orders            where org_id='$ORG_ID' and seller_account_id='$ACCOUNT_ID';"
            fi
            echo "delete from connector_credentials     where org_id='$ORG_ID' and seller_account_id='$ACCOUNT_ID';"
            echo "delete from channel_connection_status where org_id='$ORG_ID' and seller_account_id='$ACCOUNT_ID';"
            echo "delete from connector_alerts          where org_id='$ORG_ID' and seller_account_id='$ACCOUNT_ID';"
            echo "delete from sync_schedules            where org_id='$ORG_ID' and seller_account_id='$ACCOUNT_ID';"
            echo "delete from account_session_slot      where org_id='$ORG_ID' and seller_account_id='$ACCOUNT_ID';"
            echo "delete from sync_jobs                 where org_id='$ORG_ID' and (channel_id='$CHANNEL_ID' or seller_account_id='$ACCOUNT_ID');"
            echo "delete from sync_cursors              where org_id='$ORG_ID' and (channel_id='$CHANNEL_ID' or seller_account_id='$ACCOUNT_ID');"
            echo "delete from seller_accounts           where org_id='$ORG_ID' and id='$ACCOUNT_ID' and is_file_upload = false;"
            echo "update channels set status='AVAILABLE', updated_at=now() where id='$CHANNEL_ID' and status <> 'AVAILABLE';"
        } > "$f"
        say "clearing (ONE transaction, every statement scoped to org=$ORG_NAME · channel=$CHANNEL_CODE):"
        sed 's/^/    /' "$f"
        tx "$f" >/dev/null
    fi

    resolve_ids
    rule
    print_state after
    after="$(content_fingerprint)"
    verify_content "$before" "$after"
    verify_fresh
}

cmd_connected() {
    local before after f="$TMPD/restore.sql"
    have_snapshot || die "no snapshot for this (org, channel). Run 'demo-preset.sh snapshot' while the channel is in the state you want to return to."
    before="$(content_fingerprint)"
    print_state before
    rule

    # Parents before children: seller_accounts, then everything that references it; channel_orders before
    # its append-only status events.
    {
        echo "select demo_preset.restore('seller_accounts',          '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('connector_credentials',    '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('channel_connection_status','$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('connector_alerts',         '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('sync_schedules',           '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('account_session_slot',     '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('sync_jobs',                '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('sync_cursors',             '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('channel_orders',           '$ORG_ID','$CHANNEL_ID');"
        echo "select demo_preset.restore('channel_order_status_events','$ORG_ID','$CHANNEL_ID');"
        echo "update channels c set status = a.channel_status, updated_at = now()"
        echo "  from demo_preset.a_channel_status a"
        echo " where c.id='$CHANNEL_ID' and a.preset_org='$ORG_ID' and a.preset_channel='$CHANNEL_ID'"
        echo "   and c.status <> a.channel_status;"
    } > "$f"
    tx "$LIB_SQL" >/dev/null
    tx "$f" >/dev/null

    resolve_ids
    rule
    print_state after
    after="$(content_fingerprint)"
    verify_content "$before" "$after"
    verify_connected
}

# ---- verification -------------------------------------------------------------------------------------
verify_content() {
    [ "$1" = "$2" ] || die "content row counts MOVED: before=$1 after=$2. Investigate before demoing."
    say "VERIFY content untouched: $1 (reviews/inquiries/products/order-summaries/collected-orders) OK"
}

verify_fresh() {
    local acc cred cat
    acc="$(q "select count(*) from seller_accounts where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload=false")"
    cred="$(q "select count(*) from connector_credentials cc join seller_accounts sa on sa.id=cc.seller_account_id
                where sa.org_id='$ORG_ID' and sa.channel_id='$CHANNEL_ID'")"
    cat="$(q "select status from channels where id='$CHANNEL_ID'")"
    [ "$acc"  = "0" ] || die "VERIFY FRESH: an API-mode account still exists — the first-connection CTA will not appear."
    [ "$cred" = "0" ] || die "VERIFY FRESH: a credential is still on file — the page would skip issuance."
    [ "$cat" = "AVAILABLE" ] || die "VERIFY FRESH: catalog status is '$cat', so the card would read '관리' instead of '연결하기'."
    say "VERIFY FRESH: no API-mode account, no credential, catalog=AVAILABLE OK"
    say "-> /connect shows $CHANNEL_NAME with 연결하기, which lands on the first-connection page."
}

verify_connected() {
    local acc
    acc="$(q "select coalesce((select connection_status from seller_accounts
                where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload=false),'(none)')")"
    [ "$acc" != "(none)" ] || die "VERIFY CONNECTED: the account was not restored."
    say "VERIFY CONNECTED: $CHANNEL_CODE account restored at $acc OK"
    say "-> /connect shows the account again; the review/inquiry routine demo runs on the org's existing data."
}

# ---- entry --------------------------------------------------------------------------------------------
usage() {
    cat <<'USAGE'
demo-preset.sh — reproduce the SellerOps demo states (local/dev only)

  demo-preset.sh status      what the demo org's channel connection looks like now (READ-ONLY)
  demo-preset.sh snapshot    capture the current state as the CONNECTED baseline
  demo-preset.sh fresh       put the channel back before its first connection (auto-snapshots first)
  demo-preset.sh connected   restore the snapshotted baseline

options
  --channel CODE                 channel to reset (default COUPANG, or SELLEROPS_DEMO_CHANNEL)
  --resnapshot                   overwrite an existing snapshot with the current state before clearing
  --include-collected-orders     also archive+clear the account's collected orders (needed to re-run
                                 `fresh` after a real sync); off by default, so `fresh` stops instead

environment
  SELLEROPS_DEMO_PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSWORD   database (loopback host only)
  SELLEROPS_DEMO_USER_EMAIL                                   demo login that identifies the org
USAGE
}

CMD="${1:-}"; shift || true
RESNAPSHOT="false"
while [ $# -gt 0 ]; do
    case "$1" in
        --channel) CHANNEL_CODE="${2:?--channel needs a code}"; shift 2 ;;
        --resnapshot) RESNAPSHOT="true"; shift ;;
        --include-collected-orders) INCLUDE_ORDERS="true"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option '$1'" ;;
    esac
done

case "$CMD" in
    status)    guard_environment; resolve_ids; cmd_status ;;
    snapshot)  guard_environment; resolve_ids; tx "$LIB_SQL" >/dev/null; cmd_snapshot ;;
    fresh)
        guard_environment; resolve_ids
        if [ "$RESNAPSHOT" = "true" ] && [ -n "$ACCOUNT_ID" ]; then tx "$LIB_SQL" >/dev/null; cmd_snapshot; fi
        cmd_fresh ;;
    connected) guard_environment; resolve_ids; cmd_connected ;;
    ""|-h|--help) usage ;;
    *) usage; die "unknown command '$CMD'" ;;
esac
