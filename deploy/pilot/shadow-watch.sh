#!/usr/bin/env bash
# 72h shadow run observer — Pilot Shadow Monitoring v1.
#
#   PILOT_ENV_FILE=/etc/sellerops/pilot.env deploy/pilot/shadow-watch.sh            # print + emit
#   SHADOW_WATCH_DRY_RUN=1 deploy/pilot/shadow-watch.sh                             # print only, no AWS
#
# <b>Fail-fast observation, not recovery.</b> Nothing here restarts a service, rolls anything back, retries
# a marketplace call or touches a model. It reads, it emits numbers, and it exits. What to do about a
# number is a person's decision — and the alarms exist so a person is told.
#
# Every query is READ-ONLY: SELECT only, on a session the wrapper sets to read-only so a typo cannot
# write. It runs `psql` inside the existing postgres container, so it needs no database port and no
# second credential.
#
# <b>The watchdog's own silence is a signal.</b> Every alarm on these metrics treats missing data as
# breaching (`--treat-missing-data breaching` in install-shadow-watch.sh), so a watcher that dies is
# indistinguishable from a system that is failing — which is the only safe reading of «no metrics».
# That is why ShadowWatchHeartbeat is emitted FIRST and unconditionally, before any query runs: it is
# the metric whose absence means «the observer stopped», and it must not depend on the database being up.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${PILOT_ENV_FILE:-/etc/sellerops/pilot.env}"
NAMESPACE="${SHADOW_WATCH_NAMESPACE:-Reviewnary/Shadow}"
DRY="${SHADOW_WATCH_DRY_RUN:-0}"

# shellcheck disable=SC1090
[[ -f "$ENV_FILE" ]] && { set -a; . "$ENV_FILE"; set +a; }

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$REPO/docker-compose.yml" -f "$REPO/deploy/pilot/docker-compose.pilot.yml")
ORG="${SHADOW_WATCH_ORG_ID:-${RESPONSIBILITY_RUNTIME_ORG_IDS%%,*}}"
T0="${SHADOW_WATCH_T0:-}"
HOST="${PILOT_PUBLIC_HOST:-}"

metrics=()   # Name=Value:Unit, emitted in one put-metric-data call
emit() { metrics+=("$1=$2:${3:-None}"); printf '  %-34s %s\n' "$1" "$2"; }

# ── 0. heartbeat, before anything that can fail ──────────────────────────────────────────────────
emit ShadowWatchHeartbeat 1 Count

# ── 1. backend health, through the edge (the same path a seller uses) ────────────────────────────
backend_up=0
if [[ -n "$HOST" ]]; then
  body="$(curl -sS --max-time 15 "https://$HOST/health" 2>/dev/null || true)"
  [[ "$body" == *'"UP"'* ]] && backend_up=1
fi
emit BackendHealthy "$backend_up" Count

# ── 2. read-only SQL ─────────────────────────────────────────────────────────────────────────────
# `default_transaction_read_only=on` is belt to the braces of «only SELECT is written below»: a future
# edit that forgets which script this is gets an error from Postgres rather than a write.
psqlro() {
  "${COMPOSE[@]}" exec -T postgres psql -qtAX \
    -U "${POSTGRES_USER:-sellerops}" -d "${POSTGRES_DB:-sellerops}" \
    -v ON_ERROR_STOP=1 -c "set default_transaction_read_only = on;" -c "$1" 2>/dev/null | tail -1
}
num() { local v="${1//[[:space:]]/}"; [[ "$v" =~ ^-?[0-9]+$ ]] && printf '%s' "$v" || printf -- '-1'; }

db_up=0
if [[ -n "$ORG" ]] && [[ "$(num "$(psqlro 'select 1')")" == "1" ]]; then db_up=1; fi
emit DatabaseReachable "$db_up" Count

if [[ "$db_up" == "1" ]]; then
  # ── 3. Responsibility: minutes since the last window that actually finished, and failures ──────
  emit ResponsibilityMinutesSinceLastSettled "$(num "$(psqlro "
      select coalesce(floor(extract(epoch from (now() - max(finished_at))) / 60), 99999)::bigint
      from responsibility_run
      where org_id = '$ORG' and status = 'SETTLED'")")" Count
  emit ResponsibilityRunsFailed "$(num "$(psqlro "
      select count(*) from responsibility_run
      where org_id = '$ORG' and status = 'FAILED'")")" Count

  # ── 4. NAVER review export: minutes since the last SUCCEEDED ingest ───────────────────────────
  emit ReviewExportMinutesSinceLastIngested "$(num "$(psqlro "
      select coalesce(floor(extract(epoch from (now() - max(finished_at))) / 60), 99999)::bigint
      from review_import_segment_attempt
      where org_id = '$ORG' and result = 'SUCCEEDED'")")" Count

  # ── 5. NAVER / Cafe24 source health, from the responsibility run's own source rows ─────────────
  # `completeness` is the run's verdict per (channel, data type); a failure_reason is the run naming
  # what it could not do. Only the most recent run is counted — a stale failure is not news.
  for ch in NAVER CAFE24; do
    emit "SourceFailures${ch}" "$(num "$(psqlro "
        select count(*) from responsibility_run_source s
        where s.org_id = '$ORG' and s.channel_code = '$ch'
          and s.failure_reason is not null
          and s.run_id = (select id from responsibility_run
                          where org_id = '$ORG' order by created_at desc limit 1)")")" Count
  done

  # ── 6. THE ONE THAT IS CRITICAL: marketplace WRITE delta since T0 ─────────────────────────────
  # A shadow run is defined by this being zero. Anything above zero means something was sent, and no
  # number of green heartbeats makes that acceptable — the alarm on this metric is the run's stop line.
  if [[ -n "$T0" ]]; then
    inq="$(num "$(psqlro "select count(*) from inquiry_execution where org_id = '$ORG' and created_at > '$T0'")")"
    rev="$(num "$(psqlro "select count(*) from review_reply_execution where org_id = '$ORG' and created_at > '$T0'")")"
  else
    # No T0 means we cannot tell «new» from «pre-existing», and guessing zero would be the one lie this
    # script must not tell. -1 is a value the alarm treats as breaching just like a missing datapoint.
    inq=-1; rev=-1
  fi
  emit MarketplaceWriteDeltaInquiry "$inq" Count
  emit MarketplaceWriteDeltaReview "$rev" Count
fi

# ── 7. emit ──────────────────────────────────────────────────────────────────────────────────────
if [[ "$DRY" == "1" ]]; then
  printf '\ndry run: %d metric(s) not sent\n' "${#metrics[@]}"
  exit 0
fi
command -v aws >/dev/null 2>&1 || { echo "aws cli not found — cannot emit" >&2; exit 1; }
args=()
for m in "${metrics[@]}"; do
  name="${m%%=*}"; rest="${m#*=}"; value="${rest%%:*}"; unit="${rest##*:}"
  args+=("MetricName=$name,Value=$value,Unit=$unit")
done
aws cloudwatch put-metric-data --namespace "$NAMESPACE" \
  --region "${SHADOW_WATCH_REGION:-ap-northeast-2}" --metric-data "${args[@]}" \
  || { echo "put-metric-data failed" >&2; exit 1; }
printf '\nemitted %d metric(s) to %s\n' "${#metrics[@]}" "$NAMESPACE"
