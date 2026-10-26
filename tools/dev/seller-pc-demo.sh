#!/usr/bin/env bash
#
# The SELLER-PC DEMO — the vendor server, one seller PC's backend (local-seller profile) and its screen, kept on
# their own databases so they can be stopped and started again with everything still there (owner, 2026-10-25:
# "the final shape of the service, small, and kept"). Nothing here touches the web demo (:8080 / :5173, database
# `sellerops`) or its channel connections.
#
#   tools/dev/seller-pc-demo.sh up       # vendor :18099 · PC backend :18190 · PC screen http://127.0.0.1:5291
#   tools/dev/seller-pc-demo.sh down
#   tools/dev/seller-pc-demo.sh status
#   tools/dev/seller-pc-demo.sh ops …    # a vendor-server operator command against the demo's database
#
# Databases `vendor_demo` and `sellerpc_demo` on the local Postgres (127.0.0.1:5432), created on first `up`.
# State lives in tools/dev/.run/seller-pc-demo/ (gitignored): the generated keys (made once, so sessions, the
# vault and delegates' TOTP keep working across restarts), logs and pids. Nothing secret is printed.
#
# The PC's reply drafts use the model settings of the web demo — only the SELLEROPS_AGENT_DRAFT_* names are taken
# from backend/.env.local, inside the process that starts the PC; no other setting of the web demo comes along.
# SELLER_PC_DEMO=qa runs a second, separate copy beside it — its own databases (vendor_qa, sellerpc_qa), state folder
# and ports (vendor :18499 · PC :18590 · screen :5691) — for trying changes on made-up data without touching the kept
# demo. Google sign-in stays off there; delegates use a password and an authenticator.
# SELLER_PC_DEMO=judge runs only a PC (:18690 · screen :6091) over a copy of the kept demo's PC database
# (sellerpc_judge, copied once on the first `up`) — for judging the reviews' groups and drafts on real reviews without
# writing into the kept demo. No vendor server, no pull, its own keys: whatever the copy holds sealed stays sealed.
#
# Mail runs in dev-outbox mode: a PC-link code lands in vendor.log, never in a mailbox.
#
# Delegates sign in with Google through the web demo's own Google client (SELLEROPS_OAUTH_GOOGLE_* in
# backend/.env.local), or through a client file Google Cloud gives you saved as
# tools/dev/.run/seller-pc-demo/google-client.json, which wins. Either way the client must list the redirect URI
# http://127.0.0.1:18099/v1/delegate/auth/google/callback. Only the id and secret are read, inside the process that
# starts the vendor server; nothing is printed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
NAME="${SELLER_PC_DEMO:-demo}"
case "$NAME" in
  demo) RUN="$HERE/.run/seller-pc-demo"; VENDOR_PORT=18099 PC_PORT=18190 SCREEN_PORT=5291 ;;
  qa) RUN="$HERE/.run/seller-pc-qa"; VENDOR_PORT=18499 PC_PORT=18590 SCREEN_PORT=5691 ;;
  judge) RUN="$HERE/.run/seller-pc-judge"; VENDOR_PORT= PC_PORT=18690 SCREEN_PORT=6091 ;;
  *) echo "SELLER_PC_DEMO is demo, qa or judge"; exit 2 ;;
esac
VENDOR_DB="vendor_$NAME" PC_DB="sellerpc_$NAME"
mkdir -p "$RUN" && chmod 700 "$RUN"
export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17}" PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@15/bin}"
PSQL=("$PG_BIN/psql" -h 127.0.0.1 -p 5432 -U sellerops -tA)
PULL_MS="${SELLER_PC_DEMO_PULL_MS:-30000}"

keys() {
  if [[ ! -f "$RUN/keys.env" ]]; then
    umask 077
    {
      echo "VENDOR_SESSION_SECRET=$(openssl rand -hex 32)"
      echo "VENDOR_TOTP_KEY=$(openssl rand -base64 32)"
      echo "PC_JWT_SECRET=$(openssl rand -hex 32)"
      echo "PC_VAULT_KEY=$(openssl rand -base64 32)"
    } > "$RUN/keys.env"
  fi
  set -a; . "$RUN/keys.env"; set +a
}

vendor_env() {
  export VENDOR_DB_URL="jdbc:postgresql://127.0.0.1:5432/$VENDOR_DB" VENDOR_DB_USER=sellerops VENDOR_DB_PASSWORD=
  export VENDOR_SERVER_PORT=$VENDOR_PORT VENDOR_PUBLIC_URL="http://127.0.0.1:$VENDOR_PORT" VENDOR_MAIL_MODE=dev-outbox
}

# The Google client's id and secret, printed as assignments for the caller to eval — never shown.
google_env() {
  if [[ -f "$RUN/google-client.json" ]]; then
    python3 -I - "$RUN/google-client.json" <<'PY'
import json, shlex, sys
web = json.load(open(sys.argv[1])).get("web", {})
for key, name in (("client_id", "VENDOR_GOOGLE_CLIENT_ID"), ("client_secret", "VENDOR_GOOGLE_CLIENT_SECRET")):
    if web.get(key):
        print(f"export {name}={shlex.quote(web[key])}")
PY
  elif [[ -f "$REPO/backend/.env.local" ]]; then
    ( set -a; . "$REPO/backend/.env.local" >/dev/null 2>&1; set +a
      [[ -n "${SELLEROPS_OAUTH_GOOGLE_CLIENT_ID:-}" ]] && printf 'export VENDOR_GOOGLE_CLIENT_ID=%q\n' "$SELLEROPS_OAUTH_GOOGLE_CLIENT_ID"
      [[ -n "${SELLEROPS_OAUTH_GOOGLE_CLIENT_SECRET:-}" ]] && printf 'export VENDOR_GOOGLE_CLIENT_SECRET=%q\n' "$SELLEROPS_OAUTH_GOOGLE_CLIENT_SECRET"
      true )
  fi
}

jar() { (cd "$REPO/$1" && ls build/libs/*.jar | grep -v plain | head -1 | sed "s#^#$REPO/$1/#"); }

# A running server reads its classes out of its jar for as long as it lives, so it must never run from the file the
# next build overwrites: a class it had not loaded yet — logback's exception class, the first time anything is logged
# with a stack trace — then fails to load, and a scheduled job that hits it dies without a word (the PC stopped
# pulling reviews at 00:58 on 2026-10-26 this way; docs/FINDINGS.md). Each server gets its own copy.
own_jar() { cp "$(jar "$1")" "$RUN/$2.jar" && echo "$RUN/$2.jar"; }

alive() { [[ -f "$RUN/$1.pid" ]] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; }

wait_for() { # url seconds
  for _ in $(seq 1 "$2"); do curl -s -o /dev/null "$1" && return 0; sleep 1; done
  return 1
}

# Only the draft model's names, read inside this subshell; printed as assignments for the caller to eval.
draft_env() {
  [[ -f "$REPO/backend/.env.local" ]] || return 0
  ( set -a; . "$REPO/backend/.env.local" >/dev/null 2>&1; set +a
    for n in ENABLED VENDOR MODEL API_KEY MAX_OUTPUT_TOKENS REASONING_EFFORT; do
      v="SELLEROPS_AGENT_DRAFT_$n"
      [[ -n "${!v:-}" ]] && printf 'export %s=%q\n' "$v" "${!v}"
    done )
}

up() {
  keys
  if [[ "$NAME" == judge ]]; then
    if [[ "$("${PSQL[@]}" -d postgres -c "select 1 from pg_database where datname='$PC_DB'")" != 1 ]]; then
      "${PSQL[@]}" -d postgres -qc "create database $PC_DB"
      "$PG_BIN/pg_dump" -h 127.0.0.1 -p 5432 -U sellerops --no-owner sellerpc_demo \
        | "${PSQL[@]}" -q -d "$PC_DB" >/dev/null
      echo "copied sellerpc_demo into $PC_DB"
    fi
  else
    for db in "$VENDOR_DB" "$PC_DB"; do
      [[ "$("${PSQL[@]}" -d postgres -c "select 1 from pg_database where datname='$db'")" == 1 ]] \
        || "${PSQL[@]}" -d postgres -qc "create database $db"
    done
  fi
  (cd "$REPO/vendor-server" && ./gradlew -q bootJar) && (cd "$REPO/backend" && ./gradlew -q bootJar)
  echo "built $(cd "$REPO" && git log -1 --format=%h)"

  if [[ "$NAME" != judge ]] && ! alive vendor; then
    ( vendor_env; [[ "$NAME" == demo ]] && eval "$(google_env)"; exec java -jar "$(own_jar vendor-server vendor)" ) >> "$RUN/vendor.log" 2>&1 &
    echo $! > "$RUN/vendor.pid"
  fi
  [[ "$NAME" == judge ]] || wait_for "http://127.0.0.1:$VENDOR_PORT/health" 120 \
    || { echo "vendor server did not start (see $RUN/vendor.log)"; exit 1; }

  if ! alive pc; then
    ( eval "$(draft_env)"
      export SELLEROPS_AGENT_DRAFT_ORG_IDS='*'   # one seller on this PC
      # This PC reads no NAVER catalogue yet: its reviews take products made from their own product numbers.
      export SELLEROPS_REVIEW_PRODUCT_DERIVE=true
      export SPRING_PROFILES_ACTIVE=local-seller SERVER_PORT=$PC_PORT \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/$PC_DB" SPRING_DATASOURCE_USERNAME=sellerops \
        SPRING_DATASOURCE_PASSWORD= SELLEROPS_JWT_SECRET="$PC_JWT_SECRET" \
        SELLEROPS_CORS_ORIGIN="http://127.0.0.1:$SCREEN_PORT" SELLEROPS_VAULT_MASTER_KEY="$PC_VAULT_KEY" \
        SELLEROPS_VAULT_KEY_ID=seller-pc-$NAME-1 SELLEROPS_SEED_ENABLED=true \
        SELLEROPS_VENDOR_LINK_BASE_URL="http://127.0.0.1:$VENDOR_PORT" SELLEROPS_VENDOR_REVIEW_PULL_INTERVAL_MS=$PULL_MS
      if [[ "$NAME" == judge ]]; then
        export SELLEROPS_VENDOR_LINK_BASE_URL= SELLEROPS_VENDOR_REVIEW_PULL_ENABLED=false
      fi
      exec java -jar "$(own_jar backend pc)" ) >> "$RUN/pc.log" 2>&1 &
    echo $! > "$RUN/pc.pid"
  fi
  for _ in $(seq 1 240); do
    curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PC_PORT/api/local/vendor-link" | grep -q "40[13]\|200" && break
    sleep 1
  done

  if ! alive screen; then
    ( cd "$REPO/frontend" && VITE_LOCAL_SELLER=true SELLEROPS_BACKEND_ORIGIN="http://127.0.0.1:$PC_PORT" \
        exec npx vite --port $SCREEN_PORT --strictPort --host 127.0.0.1 ) >> "$RUN/screen.log" 2>&1 &
    echo $! > "$RUN/screen.pid"
  fi
  wait_for "http://127.0.0.1:$SCREEN_PORT/" 60 || { echo "PC screen did not start (see $RUN/screen.log)"; exit 1; }
  status
}

down() {
  for s in screen pc vendor; do
    if alive "$s"; then kill "$(cat "$RUN/$s.pid")" 2>/dev/null || true; fi
    rm -f "$RUN/$s.pid"
  done
  pkill -f "vite --port $SCREEN_PORT" 2>/dev/null || true
  echo "stopped (databases kept)"
}

status() {
  if [[ "$NAME" == judge ]]; then
    for s in pc screen; do printf '%-7s %s\n' "$s" "$(alive "$s" && echo up || echo down)"; done
    echo "PC screen      http://127.0.0.1:$SCREEN_PORT (a copy of the kept demo; nothing here reaches it)"
    return
  fi
  for s in vendor pc screen; do printf '%-7s %s\n' "$s" "$(alive "$s" && echo up || echo down)"; done
  printf '%-7s %s\n' google "$(curl -s "http://127.0.0.1:$VENDOR_PORT/v1/delegate/auth/methods" | grep -q '"google":true' \
    && echo "sign-in on" || echo "sign-in off (no Google client in backend/.env.local or $RUN/google-client.json)")"
  echo "PC screen      http://127.0.0.1:$SCREEN_PORT"
  echo "delegate desk  http://127.0.0.1:$VENDOR_PORT/delegate/"
  echo "phone page     http://127.0.0.1:$VENDOR_PORT/replies"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  ops) shift; keys; vendor_env; java -jar "$(jar vendor-server)" "$@" 2>/dev/null | grep -v " INFO " ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
