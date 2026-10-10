#!/usr/bin/env bash
#
# tools/demo-preset/run-demo-agent.sh — start a FRESH Local Agent for the Coupang first-connection demo.
#
# WHY THIS EXISTS. An issuance agent hosts exactly ONE run for its process lifetime (by design — a guided
# connection is a single onboarding walk, and `CoupangIssuanceGuidanceSession` is constructed once at boot).
# So the first walk uses that run up. Every later press of `쿠팡 연결 안내 시작` then RESYNCS onto the run
# already hosted and adopts it — a finished walk reappears finished, and nothing new starts. On screen that
# reads as "the button does nothing", which is exactly what it looked like before this script existed.
#
# The fix for a demo is not a code change: it is a fresh process per walk. This script is that, in one
# command — stop any running agent, start a new one, wait for the bridge, and say what to do next.
#
# WHAT IT HOSTS. The browser-free FIXTURE carrier (`--dev-action-window-coupang-issuance`): the guided
# walkthrough drives end-to-end in the product UI, but NO real WING window is opened, NO Coupang call is
# made, and NO credential is read. It needs no live approval. The REAL WING walk is a different carrier
# (`--action-window-coupang-issuance-live`) behind the tools/coupang-local harness and its own WRITE-mode
# single-use approval — this script deliberately cannot start it.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
COLLECTOR="$REPO_ROOT/collector"
CONNECTIONS="${DEMO_AGENT_CONNECTIONS:-$REPO_ROOT/tools/naver-local/agent-connections.example.json}"
LOG="${DEMO_AGENT_LOG:-$COLLECTOR/.demo-agent.log}"
PORT="${BRIDGE_PORT:-47615}"
FRONTEND_ORIGIN="${DEMO_FRONTEND_ORIGIN:-http://localhost:5173}"
PORT_SUFFIX="${FRONTEND_ORIGIN##*:}"

die() { printf 'FAIL-CLOSED: %s\n' "$*" >&2; exit 1; }

# HARD FENCE: this launcher hosts the browser-free fixture carrier only. A live-walk flag belongs to the
# approval harness, never here.
for arg in "$@"; do
  case "$arg" in
    --auto-approve) AUTO_APPROVE=1 ;;
    --action-window-coupang-issuance-live|--i-understand-this-launches-local-agent-chrome)
      die "'$arg' is a LIVE marketplace path. Use tools/coupang-local/ with its own approval." ;;
    *) die "unknown option '$arg'" ;;
  esac
done

[ -f "$CONNECTIONS" ] || die "no connections file at $CONNECTIONS"

# Stop whatever is on the bridge now — the whole point is a process with an unused run.
if pgrep -f "local-agent.ts" >/dev/null 2>&1; then
  echo "stopping the running Local Agent (its issuance run is already spent)…"
  pkill -f "local-agent.ts" || true
  sleep 1
fi

APPROVE=()
if [ "${AUTO_APPROVE:-0}" = "1" ]; then
  APPROVE=(--dev-insecure-auto-approve)
  echo "pairing: AUTO-APPROVE — the human pairing confirmation is BYPASSED (dev only)"
else
  echo "pairing: interactive — an already-paired browser reconnects on its own; a new one shows 도우미 연결하기"
fi

cd "$COLLECTOR"
NODE_ENV=development \
BRIDGE_PORT="$PORT" \
BRIDGE_ALLOWED_ORIGINS="http://localhost:${PORT_SUFFIX} http://127.0.0.1:${PORT_SUFFIX}" \
nohup npm run local-agent -- --connections "$CONNECTIONS" --dev-action-window-coupang-issuance \
  ${APPROVE[@]+"${APPROVE[@]}"} > "$LOG" 2>&1 &

for _ in $(seq 1 15); do
  sleep 1
  if curl -fsS -m 2 "http://127.0.0.1:${PORT}/bridge/health" >/dev/null 2>&1; then
    echo "bridge healthy on 127.0.0.1:${PORT} — a FRESH issuance run is hosted (log: $LOG)"
    echo
    echo "next: RELOAD the browser tab, then press 쿠팡 연결 안내 시작 at ${FRONTEND_ORIGIN}/connect/coupang"
    echo "      (a tab left open across an agent restart still holds the old, finished run until it reloads)"
    exit 0
  fi
done
die "the agent did not become healthy on :${PORT} — see $LOG"
