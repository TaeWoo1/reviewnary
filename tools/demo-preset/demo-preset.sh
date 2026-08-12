#!/usr/bin/env bash
#
# tools/demo-preset/demo-preset.sh — reproduce the two SellerOps demo states with one short command.
#
#   demo-preset.sh doctor      is everything ready to demo? (READ-ONLY)
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

# ---- doctor (READ-ONLY) -------------------------------------------------------------------------------
# One screen answering "can I demo right now?". It creates nothing — not even the archive schema — and
# says CHECK MANUALLY for everything SellerOps genuinely cannot observe, rather than guessing.
BACKEND_URL="${SELLEROPS_DEMO_BACKEND_URL:-http://127.0.0.1:8080}"
FRONTEND_URL="${SELLEROPS_DEMO_FRONTEND_URL:-http://localhost:5173}"
BRIDGE_URL="${SELLEROPS_DEMO_BRIDGE_URL:-http://127.0.0.1:47615}"     # collector local agent (VITE_BRIDGE_URL)
RUNTIME_URL="${SELLEROPS_DEMO_AGENT_RUNTIME_URL:-http://127.0.0.1:8787}"  # /agent page only
DOCTOR_FAILED="false"

line()  { printf '  %-13s %-32s %-8s %s\n' "$1" "$2" "$3" "${4:-}"; }
fail()  { DOCTOR_FAILED="true"; }

# HTTP reachability only — no auth, no write, no marketplace call.
probe() { curl -fsS -m 3 -o /dev/null "$1" 2>/dev/null; }

cmd_doctor() {
    say "SellerOps demo doctor — db: $PGDATABASE@$PGHOST:$PGPORT · org: $ORG_NAME"
    say ""
    say "services"
    if probe "$BACKEND_URL/health"; then line backend "$BACKEND_URL" OK
    else line backend "$BACKEND_URL" DOWN "백엔드가 없으면 아무 시나리오도 안 됩니다"; fail; fi
    if probe "$FRONTEND_URL/"; then line frontend "$FRONTEND_URL" OK
    else line frontend "$FRONTEND_URL" DOWN "UI 없음"; fail; fi
    if probe "$BRIDGE_URL/bridge/health"; then line "local agent" "$BRIDGE_URL" OK "Coupang 최초 연결 워크스루용"
    else line "local agent" "$BRIDGE_URL" DOWN "fresh 시나리오의 키 발급 워크스루가 페어링되지 않습니다"; fail; fi
    if probe "$RUNTIME_URL/health"; then line "agent runtime" "$RUNTIME_URL" OK "/agent 페이지 전용"
    else line "agent runtime" "$RUNTIME_URL" "-" "/agent 페이지 전용 · 오늘 시나리오에는 불필요"; fi

    say ""
    say "backend <-> this database"
    doctor_backend_matches_db

    say ""
    say "demo state"
    local acc cat cred
    acc="$(q "select coalesce((select connection_status from seller_accounts
               where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload=false),'none')")"
    cat="$(q "select status from channels where id='$CHANNEL_ID'")"
    cred="$(q "select count(*) from connector_credentials cc join seller_accounts sa on sa.id=cc.seller_account_id
                where sa.org_id='$ORG_ID' and sa.channel_id='$CHANNEL_ID'")"
    if [ "$acc" = "none" ] && [ "$cat" = "AVAILABLE" ] && [ "$cred" = "0" ]; then
        line "$CHANNEL_CODE" "FRESH" OK "최초 연결 CTA로 진입 가능"
    else
        line "$CHANNEL_CODE" "account=$acc catalog=$cat cred=$cred" "-" "'fresh'로 최초 연결 상태 진입"
    fi
    if have_snapshot; then
        line snapshot "account=$(archived_rows seller_accounts) credential=$(archived_rows connector_credentials)" OK "'connected'로 복원 가능"
    else
        line snapshot "none" WARN "지금 'connected'는 실패합니다 — 'snapshot'을 먼저 찍으세요"
    fi

    say ""
    say "routine demo data (connected 시나리오)"
    doctor_channel_data NAVER  reviews   reviews
    doctor_channel_data CAFE24 inquiries inquiries

    say ""
    say "CHECK MANUALLY — SellerOps는 알 수 없는 항목 (사람이 눈으로 확인)"
    say "  · WING 로그인 세션이 에이전트 브라우저 프로필에 살아 있는지 (CAPTCHA/2FA 우회는 없습니다)"
    say "  · WING에 자체개발 Open API 키가 이미 있는지 — 있으면 '최초 발급' 화면이 나오지 않습니다"
    say "  · 애플리케이션의 주문 API 그룹 권한"
    say "  · API 호출 IP 허용목록에 이 머신의 공인 egress IP가 등록되어 있는지"
    say "  · 라이브 Coupang 호출은 tools/coupang-local 하네스 + 단회 승인이 별도로 필요합니다"
    say "  (자세한 내용: tools/demo-preset/README.md § 4)"

    say ""
    if [ "$DOCTOR_FAILED" = "true" ]; then
        say "RESULT: 준비되지 않은 항목이 있습니다 (위 DOWN 확인)."
        exit 1
    fi
    say "RESULT: 자동 확인 가능한 항목은 모두 통과. 위 CHECK MANUALLY 목록은 직접 확인하세요."
}

# Does the running backend actually serve THIS database? Compares the COUPANG card the API returns with
# what the database says. Needs a login, so it is opt-in: without SELLEROPS_DEMO_PASSWORD it reports
# CHECK MANUALLY rather than embedding a password in the tool.
doctor_backend_matches_db() {
    local pw="${SELLEROPS_DEMO_PASSWORD:-}" token api db_label
    if [ -z "$pw" ]; then
        line "/api/channels" "not checked" "MANUAL" "SELLEROPS_DEMO_PASSWORD 설정 시 자동 대조"
        return 0
    fi
    command -v python3 >/dev/null 2>&1 || { line "/api/channels" "python3 없음" "MANUAL" ""; return 0; }
    token="$(curl -fsS -m 5 -X POST "$BACKEND_URL/api/auth/login" -H 'Content-Type: application/json' \
             -d "{\"email\":\"$DEMO_EMAIL\",\"password\":\"$pw\"}" 2>/dev/null \
             | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
    if [ -z "$token" ]; then line "/api/channels" "login failed" WARN "백엔드/계정 확인"; fail; return 0; fi
    api="$(curl -fsS -m 5 "$BACKEND_URL/api/channels" -H "Authorization: Bearer $token" 2>/dev/null \
           | python3 -c "
import sys, json
for c in json.load(sys.stdin):
    if c['code'] == '$CHANNEL_CODE':
        print(c['status'] + '/' + c['actionLabel'])
        break
" 2>/dev/null || true)"
    db_label="$(q "select coalesce((select connection_status from seller_accounts
                    where org_id='$ORG_ID' and channel_id='$CHANNEL_ID' and is_file_upload=false),
                   (select status from channels where id='$CHANNEL_ID'))")"
    if [ -z "$api" ]; then
        line "/api/channels" "no answer" WARN "" ; fail
    elif [ "${api%%/*}" = "$db_label" ]; then
        line "/api/channels" "$CHANNEL_CODE = $api" OK "DB와 일치 — 같은 데이터베이스입니다"
    else
        line "/api/channels" "api=$api db=$db_label" FAIL "백엔드가 다른 DB를 보고 있습니다"; fail
    fi
}

# Is the connected-scenario data actually there for a channel?
doctor_channel_data() {
    local code="$1" tbl_name="$2" label="$3" ch acc n
    ch="$(q "select id from channels where code='$code'")"
    if [ -z "$ch" ]; then line "$code" "채널 없음" FAIL ""; fail; return 0; fi
    acc="$(q "select coalesce((select connection_status from seller_accounts
               where org_id='$ORG_ID' and channel_id='$ch' and is_file_upload=false),'none')")"
    n="$(q "select count(*) from $tbl_name where org_id='$ORG_ID' and channel_id='$ch'")"
    if [ "$acc" = "CONNECTED" ] && [ "$n" -gt 0 ]; then
        line "$code" "account $acc · $label $n" OK
    elif [ "$n" -gt 0 ]; then
        line "$code" "account $acc · $label $n" WARN "데이터는 있으나 계정이 연결 상태가 아닙니다"
    else
        line "$code" "account $acc · $label $n" FAIL "루틴 시연용 데이터가 없습니다"; fail
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

  demo-preset.sh doctor      is everything ready to demo? services + state + data (READ-ONLY)
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
  SELLEROPS_DEMO_PASSWORD                                     doctor only, optional: log in and prove the
                                                              running backend serves THIS database
  SELLEROPS_DEMO_BACKEND_URL / _FRONTEND_URL / _BRIDGE_URL / _AGENT_RUNTIME_URL   doctor probe targets
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
    doctor)    guard_environment; resolve_ids; cmd_doctor ;;
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
