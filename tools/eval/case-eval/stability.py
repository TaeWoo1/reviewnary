#!/usr/bin/env python3
"""The same cases run several times: how often the result, its basis and what it asks back change — not its wording
(2026-11-01 ninth review: 「같은 문의를 3회씩 실행해 문구가 아니라 최종 처리 · 근거 · 되묻는 내용이 얼마나 달라지는지」).

    stability.py --runs <dir> <dir> [<dir> ...]

Each <dir> is one run_cases.sh output over the same cases.txt. Compares, case by case, closed facts only:
    result     the inquiry's closed result (build_case_sheet.inquiry_outcome)
    basis      answerBasis
    needs      each need the decision found, as (type, status, whether a question goes to the customer), as a multiset
    cited      the passages the saved draft cites (chunk ids), or none
    asksBack   whether the saved draft asks the customer something (a question mark or 「알려 주」 · 「말씀해」)
Writes <first dir>/../stability-<name>.json and prints ids, counts and closed words only — no customer or draft text.
"""
import argparse
import json
import os
import sys
from collections import Counter

from build_case_sheet import inquiry_outcome

ASKS = ("?", "알려 주", "알려주", "말씀해", "확인해 주시", "확인 부탁")


def facts(run, raw):
    body = raw.get("body") or {}
    draft = body.get("draft") or None
    needs = (body.get("knowledgeGap") or {}).get("needs") or []
    text = (draft or {}).get("comments") or ""
    return {
        "result": inquiry_outcome(run, draft),
        "basis": body.get("answerBasis"),
        "needs": sorted(f"{n.get('type')}:{n.get('status')}:{'ask' if (n.get('askCustomer') or '').strip() else '-'}"
                        for n in needs),
        "cited": sorted(e.get("chunkId") or "" for e in (body.get("evidence") or [])) if draft else [],
        "asksBack": any(a in text for a in ASKS) if draft else None,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--name", default="round")
    a = ap.parse_args()
    holds = os.path.realpath(os.path.expanduser("~/workspace/sellerops/holds"))
    dirs = [os.path.realpath(d) for d in a.runs]
    if len(dirs) < 2 or not all(d.startswith(holds) for d in dirs):
        sys.exit("two or more run dirs, all under ~/workspace/sellerops/holds")
    per = {}
    order = []
    for d in dirs:
        for line in open(os.path.join(d, "runs.jsonl"), encoding="utf-8"):
            if not line.strip():
                continue
            run = json.loads(line)
            if run.get("kind") != "inquiry":
                continue
            if run["id"] not in per:
                order.append(run["id"])
            raw = json.load(open(os.path.join(d, "raw", run["id"] + ".json"), encoding="utf-8"))
            per.setdefault(run["id"], []).append(facts(run, raw))
    keys = ("result", "basis", "needs", "cited", "asksBack")
    cases, changed = [], Counter()
    for cid in order:
        seen = per[cid]
        moved = [k for k in keys if len({json.dumps(s[k], sort_keys=True) for s in seen}) > 1]
        for k in moved:
            changed[k] += 1
        cases.append({"id": cid[:8], "runs": len(seen), "results": [s["result"] for s in seen],
                      "needsCount": [len(s["needs"]) for s in seen], "asksBack": [s["asksBack"] for s in seen],
                      "changed": moved})
        print(cid[:8], " ".join(s["result"] for s in seen), "changed:", ",".join(moved) or "-")
    out = {"runs": len(dirs), "cases": len(order), "stable": sum(1 for c in cases if not c["changed"]),
           "changedBy": dict(changed), "byCase": cases}
    os.umask(0o077)
    with open(os.path.join(os.path.dirname(dirs[0]), f"stability-{a.name}.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: out[k] for k in ("runs", "cases", "stable", "changedBy")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
