#!/bin/zsh
# The same cases, N rounds, each over a freshly started evaluation backend (2026-11-01 ninth · tenth review: 「같은 문의를
# 3회씩 실행해 … 얼마나 달라지는지」). The inquiry decision remembers an identical request for five minutes in memory, so
# rounds over one backend would hand rounds 2..N round 1's judgement and show no variation at all.
# Usage: repeat_rounds.sh <cases.txt> <out prefix> <rounds>
#   writes <prefix>-r1 … <prefix>-rN (run_cases.sh outputs), <prefix>-server (the backend's log and pids), and
#   stability-<prefix name>.json beside them via stability.py. The env run_cases.sh and start_eval_backend.sh read (EVAL_ORG,
#   EVAL_LOGIN_*, EVAL_EXTRA_ORG_IDS, EVAL_SYNTHETIC_VISIBLE) passes through. Stops the backend at the end.
set -u
CASES=$1; PREFIX=$2; ROUNDS=$3
case "$PREFIX" in "$HOME/workspace/sellerops/holds/"*) ;; *) echo "out prefix must be under ~/workspace/sellerops/holds/"; exit 2;; esac
HERE=${0:A:h}
SERVER=$PREFIX-server

stop_backend() {
  local pids
  pids=$(cat $SERVER/backend.pid $SERVER/runtime.pid 2>/dev/null)
  [ -n "$pids" ] && kill ${=pids} 2>/dev/null
  local wait
  for wait in $(seq 1 30); do lsof -iTCP:18080 -sTCP:LISTEN -n -P >/dev/null 2>&1 || return 0; sleep 1; done
  echo "backend did not stop"; return 1
}

start_backend() {
  $HERE/start_eval_backend.sh $SERVER >/dev/null || return 1
  local wait
  for wait in $(seq 1 90); do
    curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:18080/api/auth/login && return 0
    sleep 2
  done
  echo "backend did not start"; return 1
}

round=0
for round in $(seq 1 $ROUNDS); do
  stop_backend || exit 1
  start_backend || exit 1
  # A case still OPEN is proposed in round 1 only; later rounds read the same item as the seller would, already proposed.
  if [ $round = 1 ]; then f=$CASES; else f=$PREFIX-cases-again.txt; cut -d'|' -f1-3 $CASES > $f; fi
  $HERE/run_cases.sh $f $PREFIX-r$round || exit 1
done
stop_backend
runs=()
for round in $(seq 1 $ROUNDS); do runs+=($PREFIX-r$round); done
python3 $HERE/stability.py --runs ${runs[@]} --name ${PREFIX:t}
