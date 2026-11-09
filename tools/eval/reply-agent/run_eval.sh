#!/bin/zsh
# For each case: write the previous single-call draft, then the reply agent's — against the EVAL backend only.
# Usage: run_eval.sh <cases.txt> <out dir>   (expects the eval backend on :18080 and runtime on :18787)
# Writes <out>/runs.jsonl: one line per case naming THIS run's versions (old draft version, agent run id and version) and,
# when a side wrote nothing, why — build_sheet.py reads those versions, never a review's latest draft (review 2026-10-28).
set -u
CASES=$1; OUT=$2; BASE=http://127.0.0.1:18080; ACC=bdccb7a7-99b9-45f7-aa09-8cf2e14c7f03
mkdir -p "$OUT"; umask 077
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" -d '{"email":"demo@sellerops.ai","password":"demo1234"}' \
  $BASE/api/auth/login | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
[ -z "$TOKEN" ] && { echo "login failed"; exit 1; }
H="Authorization: Bearer $TOKEN"
: > "$OUT/runs.jsonl"
# The knowledge the citations will be read back from must hold still for the run: fingerprint it before and after.
HERE=${0:A:h}
python3 "$HERE/build_sheet.py" fingerprint sellerops_eval > "$OUT/knowledge-before.txt" || { echo "fingerprint failed"; exit 1; }
while IFS='|' read -r set id rating; do
  [ -z "$id" ] && continue
  REF="review:$id"
  # 대응 필요 first — the previous drafter's gate. This is the COPY; nothing here reaches a channel.
  curl -s -o /dev/null -X POST -H "$H" -H "Content-Type: application/json" \
    -d "{\"disposition\":\"RESPONSE_NEEDED\",\"commandId\":\"$(uuidgen)\"}" \
    "$BASE/api/seller-accounts/$ACC/attention/items/$REF/triage"
  old=$(curl -s -m 300 -w '\n%{http_code}' -X POST -H "$H" \
        "$BASE/api/seller-accounts/$ACC/attention/items/$REF/reply/draft/generate")
  new=$(curl -s -m 360 -w '\n%{http_code}' -X POST -H "$H" -H "Content-Type: application/json" \
        -d "{\"reviewId\":\"$id\"}" "$BASE/api/agent/reply/start")
  OLD_RESP="$old" NEW_RESP="$new" python3 - "$set" "$id" "$rating" >> "$OUT/runs.jsonl" <<'PY'
import json, os, sys
def parse(raw):
    body, _, code = raw.rpartition("\n")
    try:
        return int(code), json.loads(body) if body.strip() else {}
    except ValueError:
        return int(code or 0), {}
s, rid, rating = sys.argv[1:4]
oc, o = parse(os.environ["OLD_RESP"]); nc, n = parse(os.environ["NEW_RESP"])
draft = (o.get("draft") or {}) if isinstance(o, dict) else {}
old = {"http": oc, "version": draft.get("version"),
       "why": None if draft.get("version") else ("UNAVAILABLE" if o.get("unavailableMessage") else f"HTTP_{oc}"),
       "note": o.get("unavailableMessage")}
new = {"http": nc, "runId": n.get("runId"), "outcome": n.get("outcome"), "category": n.get("category"),
       "reason": n.get("reason"), "version": n.get("version")}
print(json.dumps({"set": s, "reviewId": rid, "rating": rating, "old": old, "new": new}, ensure_ascii=False))
PY
  tail -1 "$OUT/runs.jsonl" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['set'], 'old', r['old']['version'] or r['old']['why'], '| new', r['new']['outcome'], r['new']['reason'] or '', r['new']['version'] or '')"
done < "$CASES"
python3 "$HERE/build_sheet.py" fingerprint sellerops_eval > "$OUT/knowledge-after.txt"
cmp -s "$OUT/knowledge-before.txt" "$OUT/knowledge-after.txt" || echo "WARNING: knowledge changed during the run — fact checks will be withheld"
