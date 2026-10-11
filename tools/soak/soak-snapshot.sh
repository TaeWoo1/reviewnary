#!/usr/bin/env bash
# Read-only soak snapshot for the Operations Daily Loop — Pilot Reliability v1.
#
# WHY THIS EXISTS
# A 72-hour unattended run has to be able to answer five questions afterwards, and none of them is a
# screen: did automatic observation become Cases, did AUTH/device pauses recover, did anything duplicate
# or flood, how fast did the scheduler/job/case tables grow, and were there ZERO marketplace writes.
# Those are all counts over tables the product already keeps, so this tool is counts — no new column,
# no new endpoint, no dashboard. It is also why the proof is a diff of two snapshots rather than a
# single reading: a count is not a rate.
#
# It is strictly READ-ONLY. Every statement is a SELECT, the whole session is held in a read-only
# transaction, and the connection string is never printed.
#
# Usage:
#   soak-snapshot.sh take  [label]      # write a snapshot, print its summary
#   soak-snapshot.sh diff  [a] [b]      # growth between two snapshots (default: oldest, newest)
#   soak-snapshot.sh check [label]      # take, then diff against the previous one — the soak's hourly move
#   soak-snapshot.sh list
#
# Env: DATABASE_URL (default postgresql://localhost:5432/sellerops).
#
# The soak procedure itself — what to arm, in what order, and what each count proves — is
# docs/pilot_reliability_v1.md §3-§4. This file is the counting, not the contract.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB="${DATABASE_URL:-postgresql://localhost:5432/sellerops}"
OUT="$HERE/.run"
mkdir -p "$OUT"

# A read-only transaction, so a typo in the SQL below cannot write even by accident. The queries live
# in a separate file so this script holds no SQL and the SQL holds no shell quoting.
query() {
  psql "$DB" -At -F'|' --no-psqlrc -v ON_ERROR_STOP=1 \
    -c 'begin read only' -f "$HERE/snapshot.sql" -c 'commit' 2>&1
}

take() {
  local label="${1:-}" stamp file
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  file="$OUT/$stamp.json"
  if ! query > "$OUT/.raw.$$"; then
    echo "FAIL: could not read the database (is it up?)" >&2
    sed -n '1,8p' "$OUT/.raw.$$" >&2
    rm -f "$OUT/.raw.$$"
    exit 1
  fi
  python3 "$HERE/soak_snapshot.py" parse "$stamp" "$label" < "$OUT/.raw.$$" > "$file"
  rm -f "$OUT/.raw.$$"
  echo "snapshot $stamp -> ${file#"$HERE/"}"
  python3 "$HERE/soak_snapshot.py" show "$file"
}

snapshots() { ls -1 "$OUT"/*.json 2>/dev/null | sort; }

case "${1:-}" in
  take)  take "${2:-}" ;;
  list)  snapshots ;;
  diff)
    A="${2:-$(snapshots | head -1)}"
    B="${3:-$(snapshots | tail -1)}"
    [ -f "$A" ] && [ -f "$B" ] || { echo "usage: soak-snapshot.sh diff <a.json> <b.json>" >&2; exit 2; }
    python3 "$HERE/soak_snapshot.py" diff "$A" "$B"
    ;;
  check)
    PREV="$(snapshots | tail -1)"
    take "${2:-}" > /dev/null
    NEW="$(snapshots | tail -1)"
    python3 "$HERE/soak_snapshot.py" show "$NEW"
    if [ -n "$PREV" ] && [ "$PREV" != "$NEW" ]; then
      echo
      python3 "$HERE/soak_snapshot.py" diff "$PREV" "$NEW"
    fi
    ;;
  *) echo "usage: soak-snapshot.sh <take|check|diff|list> [args]" >&2; exit 2 ;;
esac
