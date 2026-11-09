#!/usr/bin/env python3
"""A few cases of a round for the owner to grade by hand (2026-11-01 eighth review: 「승인 가능 2건 · 문체 수정 2건 · 넘김
2건 정도만 판매자가 직접 채점」) — enough to see where the separate judge and the seller part ways.

    owner_sample.py --run <dir> [--approvable 2] [--style 2] [--handoff 2]

Reads <run>/runs.jsonl, judge-output.jsonl and sheet.html (build_case_sheet.py); writes <run>/owner-sample.html — the
same cards, the chosen ones only, with the same 「JSON 저장」 — and <run>/owner-sample.json (which cards, and what the
judge said of each, so the owner's answers can be compared). The choice is the first cases of each group in run order;
it prints keys and closed labels only.
"""
import argparse
import json
import os
import re
import sys

from run_judge import problems
from summarize_round import missed_points


def group_of(run, verdict):
    if problems(verdict):
        return None
    if run.get("outcome") == "HANDOFF":
        return "handoff"
    if run.get("outcome") != "SAVED" or (run.get("openQuestions") or 0) > 0:
        return None
    clean = verdict.get("4") == "맞음" and verdict.get("5") == "아니오" and not missed_points(verdict) \
        and verdict.get("2") != "나"
    if clean and verdict.get("6") == "좋음":
        return "approvable"
    if clean and verdict.get("6") == "고치면 됨":
        return "style"
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    for g in ("approvable", "style", "handoff"):
        ap.add_argument("--" + g, type=int, default=2)
    a = ap.parse_args()
    run_dir = os.path.abspath(a.run)
    if not run_dir.startswith(os.path.expanduser("~/workspace/sellerops/holds/")):
        sys.exit("run dir must be under ~/workspace/sellerops/holds/")
    read = lambda n: [json.loads(l) for l in open(os.path.join(run_dir, n), encoding="utf-8") if l.strip()]  # noqa: E731
    runs, judged = read("runs.jsonl"), read("judge-output.jsonl")
    want = {"approvable": a.approvable, "style": a.style, "handoff": a.handoff}
    chosen = []
    for i, (run, verdict) in enumerate(zip(runs, judged)):
        g = group_of(run, verdict) if run.get("kind") == "review" else None
        if g and sum(c["group"] == g for c in chosen) < want[g]:
            chosen.append({"key": f"C{i + 1:02d}", "group": g, "id": run["id"],
                           "judge": {k: verdict.get(k) for k in ("2", "4", "5", "6")} | {"missed": missed_points(verdict)}})
    page = open(os.path.join(run_dir, "sheet.html"), encoding="utf-8").read()
    sections = {m.group(1): m.group(0) for m in re.finditer(r'<section class=case data-id="[^"]*" data-key="(C\d+)">.*?</section>',
                                                           page, re.S)}
    head, _, rest = page.partition("<section class=case")
    tail = rest[rest.rfind("</section>") + len("</section>"):]
    note = ("<p class=top>사장님 채점용 표본: 자동 채점이 「수정 없이 승인 가능」 · 「문체만 고치면 됨」 · 「사람에게 넘김」으로 본 "
            "사례를 그룹마다 몇 건씩 골랐습니다. 그룹 이름은 일부러 적지 않았습니다. 판정마다 고르고 「JSON 저장」을 눌러 주세요.</p>")
    os.umask(0o077)
    with open(os.path.join(run_dir, "owner-sample.html"), "w", encoding="utf-8") as f:
        f.write(head + note + "".join(sections[c["key"]] for c in chosen) + tail)
    with open(os.path.join(run_dir, "owner-sample.json"), "w", encoding="utf-8") as f:
        json.dump({"chosen": chosen}, f, ensure_ascii=False, indent=1)
    for c in chosen:
        print(c["key"], c["group"], c["id"][:8])


if __name__ == "__main__":
    main()
