#!/bin/zsh
# Run each case through the product's own flow against the EVALUATION backend only (start_eval_backend.sh).
# Usage: run_cases.sh <cases.txt> <out dir>
#   review  — POST /api/agent/reply/start: the reply agent judges, drafts, checks and saves exactly as the review screen's
#             button does. No disposition is set first, so whether to answer is the agent's own judgement (RUBRIC 2).
#   inquiry — POST /api/inquiries/{workItemId}/draft/generate: the inquiry screen's 「초안 생성」.
# Writes <out>/runs.jsonl (ids and closed tokens only) and <out>/raw/<id>.json (the full response — it carries customer
# text, so <out> must be outside the repository). Prints ids and tokens, never text.
set -u
CASES=$1; OUT=$2; BASE=${EVAL_BASE:-http://127.0.0.1:18080}
case "$OUT" in "$HOME/workspace/sellerops/holds/"*) ;; *) echo "out dir must be under ~/workspace/sellerops/holds/"; exit 2;; esac
umask 077; mkdir -p "$OUT/raw"
HERE=${0:A:h}
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"email\":\"${EVAL_LOGIN_EMAIL:-demo@sellerops.ai}\",\"password\":\"${EVAL_LOGIN_PASSWORD:-demo1234}\"}" \
  $BASE/api/auth/login | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
[ -z "$TOKEN" ] && { echo "login failed"; exit 1; }
H="Authorization: Bearer $TOKEN"
python3 "$HERE/../reply-agent/build_sheet.py" fingerprint sellerops_eval ${EVAL_ORG:-} > "$OUT/knowledge-before.txt" || { echo "fingerprint failed"; exit 1; }
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/started-at.txt"
: > "$OUT/runs.jsonl"
while IFS='|' read -r kind set id extra; do
  [ -z "$id" ] && continue
  t0=$(python3 -c "import time; print(time.time())")
  proposed=""
  if [ "$kind" = inquiry ] && [ "${extra:-}" = propose ]; then
    # An inquiry no one has opened yet (OPEN): the screen's proposal step first, as the seller's first look does.
    proposed=$(curl -s -m 120 -o /dev/null -w '%{http_code}' -X POST -H "$H" "$BASE/api/inquiries/$id/proposal")
  fi
  if [ "$kind" = review ]; then
    resp=$(curl -s -m 420 -w '\n%{http_code}' -X POST -H "$H" -H "Content-Type: application/json" \
      -d "{\"reviewId\":\"$id\"}" "$BASE/api/agent/reply/start")
  else
    resp=$(curl -s -m 420 -w '\n%{http_code}' -X POST -H "$H" -H "Content-Type: application/json" -d '{}' \
      "$BASE/api/inquiries/$id/draft/generate")
  fi
  RESP="$resp" T0="$t0" PROPOSED="$proposed" python3 - "$kind" "$set" "$id" "$OUT" >> "$OUT/runs.jsonl" <<'PY'
import json, os, sys, time
kind, s, cid, out = sys.argv[1:5]
body, _, code = os.environ["RESP"].rpartition("\n")
try:
    data = json.loads(body) if body.strip() else {}
except ValueError:
    data = {"_unparsed": True}
with open(os.path.join(out, "raw", cid + ".json"), "w", encoding="utf-8") as f:
    json.dump({"http": int(code or 0), "body": data}, f, ensure_ascii=False)
row = {"kind": kind, "set": s, "id": cid, "http": int(code or 0), "seconds": round(time.time() - float(os.environ["T0"]), 1)}
if kind == "review":
    row.update({k: data.get(k) for k in ("runId", "outcome", "category", "reason", "version", "openQuestions")})
else:
    draft = data.get("draft") or {}
    gap = data.get("knowledgeGap") or {}
    # Closed words only: whether a version was written, by what, on what basis, and why not when it was not.
    row.update({"version": draft.get("version"), "authorKind": data.get("authorKind"),
                "knowledgeState": data.get("knowledgeState"), "answerBasis": data.get("answerBasis"),
                "evidence": len(data.get("evidence") or []),
                "needs": [n.get("status") for n in (gap.get("needs") or [])],
                "unavailable": bool(data.get("unavailableMessage")), "unavailableKind": data.get("unavailableKind"),
                "openQuestions": len(data.get("openQuestions") or []), "rewrite": data.get("rewrite"),
                "proposed": int(os.environ["PROPOSED"]) if os.environ.get("PROPOSED") else None})
print(json.dumps(row, ensure_ascii=False))
PY
  tail -1 "$OUT/runs.jsonl" | python3 -c "import sys,json; r=json.load(sys.stdin); print(' '.join(str(v) for k,v in r.items() if k not in ('id',)), r['id'][:8])"
done < "$CASES"
python3 "$HERE/../reply-agent/build_sheet.py" fingerprint sellerops_eval ${EVAL_ORG:-} > "$OUT/knowledge-after.txt"
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/finished-at.txt"
cmp -s "$OUT/knowledge-before.txt" "$OUT/knowledge-after.txt" || echo "WARNING: knowledge changed during the run — RUBRIC 4 · 4-1 · 5 are withheld"
