#!/usr/bin/env python3
"""One round's numbers that say whether the seller's time went down (2026-11-01 sixth review: 「저장률과 함께 수정 없이 승인
가능한 비율, 놓친 문제, 적절한 보류, 불필요한 보류, 처리 시간 · 비용」) — for reviews and inquiries alike (eighth review).

    summarize_round.py --run <dir>

Reads <run>/runs.jsonl (what each run did, how long), <run>/judge-output.jsonl (the separate judge, aligned with the
cases in order), <run>/auto.json (each case's work, channel and closed result, and the round's model-call usage) and
<run>/material-need.jsonl when present. Writes <run>/summary.json and prints closed counts only — no customer text, no
draft text.

What each kind of run can end as (eighth review: a review's SAVED is not an inquiry's result):
    review    SAVED · SAVED_PARTIAL (questions left to the seller beside the reply) · HANDOFF · FAILED · SKIPPED
    inquiry   DRAFT · DRAFT_ASKS_CUSTOMER · DRAFT_PARTIAL (part answered, the rest left, not sent until handled) ·
              DRAFT_FALLBACK (the seller's own sentence) · NO_BASIS (the seller writes) ·
              HELD_BY_CHECK (written, refused by a check) · WAITING_FOR_CHECK (the ceiling) · FAILED
A channel's length limit is applied only where one is known for that channel and that kind of work (LIMITS).

The judge's answers are signals beside the owner's grading, never instead of it (RUBRIC). An answer that fails its own
check (run_judge.problems) is counted as an evaluation failure and kept out of every rate.
"""
import argparse
import json
import os
import statistics
import sys

from run_judge import problems

# The registration limits this product knows, by channel and kind of work — the same table as ChannelReplyLimit. The
# inquiry answer APIs (docs/vendor/naver-commerce-api) document none, so an inquiry's length is not judged.
LIMITS = {("NAVER", "REVIEW_REPLY"): 200}

DRAFTED = {"SAVED", "SAVED_PARTIAL", "DRAFT", "DRAFT_ASKS_CUSTOMER", "DRAFT_PARTIAL", "DRAFT_FALLBACK"}
PARTIAL = {"SAVED_PARTIAL", "DRAFT_PARTIAL"}
TO_SELLER = {"HANDOFF", "NO_BASIS"}
HELD = {"HELD_BY_CHECK", "WAITING_FOR_CHECK"}


def _points(verdict, key):
    three = verdict.get("3")
    if not isinstance(three, dict):
        return []
    # 「없음」 · 「누락은 아님」 written into the list is the judge saying nothing is missing.
    return [m for m in (three.get(key) or []) if isinstance(m, str) and m.strip()
            and not m.strip().startswith("없음") and "누락은 아님" not in m]


def missed_points(verdict):
    return _points(verdict, "missed")


def judged_per_question(verdict):
    """Whether the judge sorted each unanswered question itself — left to the seller, or dropped (2026-11-02). An answer
    written before then has no `toSeller` and only a count can be taken off it."""
    three = verdict.get("3")
    return isinstance(three, dict) and isinstance(three.get("toSeller"), list)


def open_count(run):
    n = run.get("openQuestions")
    return len(n) if isinstance(n, list) else n or 0


def outcome_of(run, case):
    if run.get("kind") == "review":
        o = run.get("outcome") or "FAILED"
        return "SAVED_PARTIAL" if o == "SAVED" and open_count(run) > 0 else o
    return ((case or {}).get("did") or {}).get("outcome") or "FAILED"


def count(d, k):
    d[k] = d.get(k, 0) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    a = ap.parse_args()
    if not os.path.realpath(a.run).startswith(os.path.realpath(os.path.expanduser("~/workspace/sellerops/holds"))):
        sys.exit("outputs go under ~/workspace/sellerops/holds only")
    lines = lambda name: [json.loads(l) for l in open(os.path.join(a.run, name), encoding="utf-8") if l.strip()]  # noqa: E731
    runs = lines("runs.jsonl")
    judged = lines("judge-output.jsonl")
    inputs = lines("judge-input.jsonl")
    if not len(judged) == len(runs) == len(inputs):
        sys.exit(f"judge answers {len(judged)} · inputs {len(inputs)} for {len(runs)} runs — not aligned")
    auto = json.load(open(os.path.join(a.run, "auto.json"), encoding="utf-8"))
    material = {}
    if os.path.exists(os.path.join(a.run, "material-need.jsonl")):
        for v in lines("material-need.jsonl"):
            material[v.get("review") or v.get("case")] = v.get("result") or []
    out = summarize(runs, judged, inputs, auto, material)
    os.umask(0o077)
    with open(os.path.join(a.run, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(out, ensure_ascii=False))


def summarize(runs, judged, inputs, auto, material):
    """The round's counts from its parts — kept apart from the files so the rules can be tested."""
    cases = {c["id"]: c for c in auto.get("cases") or []}
    out = {"cases": len(runs), "byKind": {}, "byOutcome": {}, "drafted": 0, "partial": 0, "asksCustomer": 0,
           "toSeller": {}, "held": 0, "failed": 0, "approvableAsIs": 0, "missedProblems": 0,
           "questions": {"asked": 0, "missed": 0, "leftToSeller": 0, "byCount": 0},
           "toSellerAppropriate": 0, "toSellerUnneeded": 0, "draftedButAPersonsCase": 0, "judgeInvalid": 0,
           "lengthNotJudged": 0, "notApprovableBecause": {}, "causes": {},
           # The one rewrite (2026-11-02, fourth review): a first try and a second are counted apart; its cost is the
           # REDRAFT · REDRAFT_CHECK rows of the usage table.
           # A rewrite held for its check's budget is waiting, not refused (fifth review): pendingAfterRewrite.
           "rewrite": {"draftedFirstTry": 0, "draftedAfterRewrite": 0, "refusedAfterRewrite": 0,
                       "pendingAfterRewrite": 0, "notWritten": 0}}
    for run, verdict, given in zip(runs, judged, inputs):
        case = cases.get(run["id"]) or {}
        kind = run.get("kind")
        outcome = outcome_of(run, case)
        count(out["byKind"], kind)
        count(out["byOutcome"], f"{kind}:{outcome}")
        drafted, to_seller = outcome in DRAFTED, outcome in TO_SELLER
        if drafted:
            out["drafted"] += 1
        if outcome in PARTIAL:
            out["partial"] += 1
        if outcome == "DRAFT_ASKS_CUSTOMER":
            out["asksCustomer"] += 1
        if kind == "inquiry":
            rw = run.get("rewrite")
            if drafted and rw is None:
                out["rewrite"]["draftedFirstTry"] += 1
            elif rw == "PASSED":
                out["rewrite"]["draftedAfterRewrite"] += 1
            elif rw == "REFUSED":
                out["rewrite"]["refusedAfterRewrite"] += 1
            elif rw == "PENDING":
                out["rewrite"]["pendingAfterRewrite"] += 1
            elif rw == "NOT_WRITTEN":
                out["rewrite"]["notWritten"] += 1
        if to_seller:
            count(out["toSeller"], run.get("reason") or outcome)
        if outcome in HELD:
            out["held"] += 1
        if outcome == "FAILED":
            out["failed"] += 1
        found = material.get(run["id"][:8], [])
        if problems(verdict):
            out["judgeInvalid"] += 1
            continue
        person = verdict.get("2") == "나"
        missed = missed_points(verdict)
        if drafted:
            three = verdict.get("3") if isinstance(verdict.get("3"), dict) else {}
            out["questions"]["asked"] += len(three.get("asked") or [])
        if judged_per_question(verdict):
            # The judge was shown the seller's list and sorted each question against it: what it left out of the list
            # is what nobody was told about.
            if drafted:
                out["questions"]["leftToSeller"] += len(_points(verdict, "toSeller"))
        elif outcome in PARTIAL:
            # Before 2026-11-02 the judge never saw the seller's list, so only its count could be taken off — by
            # position, which says nothing about WHICH question was left. Counted apart so it is never mistaken for one.
            missed = missed[open_count(run):]
            out["questions"]["byCount"] += 1
        if drafted:
            out["questions"]["missed"] += len(missed)
        if drafted:
            if missed:
                out["missedProblems"] += 1
            # 「As is」 means the seller could press approve, the channel would take it, and nothing the customer asked
            # is left (seventh and eighth review).
            why = []
            if verdict.get("4") != "맞음":
                why.append("근거와 다름")
            if verdict.get("5") != "아니오":
                why.append("잘못된 약속")
            if missed:
                why.append("놓친 문제")
            if outcome in PARTIAL:
                why.append("답하지 못한 질문 남음")
            if verdict.get("6") != "좋음":
                why.append("문체")
            limit = LIMITS.get((case.get("channel"), case.get("work")))
            if limit is None:
                out["lengthNotJudged"] += 1
            elif len(given.get("reply") or "") > limit:
                why.append("채널 글자 수 초과")
            if "WRONG" in found:
                why.append("쓰면 안 되는 자료")
            if person:
                why.append("사람이 볼 건")
                out["draftedButAPersonsCase"] += 1
            if not why:
                out["approvableAsIs"] += 1
            for w in why:
                count(out["notApprovableBecause"], w)
        if to_seller:
            # A person's case, or a store that has no answer: handing it over was right. Material that existed and the
            # run did not reach is a retrieval failure, not a reason.
            appropriate = person or ("ABSENT" in found and "MISSED" not in found)
            out["toSellerAppropriate" if appropriate else "toSellerUnneeded"] += 1
        # Step 4 of the eighth review: no material, material not found, or found and written wrong.
        if found:
            if "ABSENT" in found:
                count(out["causes"], "자료 없음")
            if "MISSED" in found:
                count(out["causes"], "있었는데 못 찾음")
            if "FOUND" in found and outcome in HELD:
                count(out["causes"], "찾았는데 검사에서 막힘")
            if ("FOUND" in found or "NOT_NEEDED" in found) and drafted and (
                    verdict.get("4") != "맞음" or verdict.get("5") != "아니오" or missed):
                count(out["causes"], "찾고도 답을 잘못 씀")
    seconds = [r.get("seconds") for r in runs if isinstance(r.get("seconds"), (int, float))]
    out["seconds"] = {"median": round(statistics.median(seconds), 1) if seconds else None,
                      "max": max(seconds) if seconds else None}
    if material:
        found = {}
        for result in material.values():
            for r in result:
                found[r] = found.get(r, 0) + 1
        out["material"] = found
    usage = auto.get("usage") or []
    out["modelCalls"] = sum(u.get("calls") or 0 for u in usage)
    out["tokens"] = {"input": sum(u.get("input_tokens") or 0 for u in usage),
                     "output": sum(u.get("output_tokens") or 0 for u in usage)}
    return out


if __name__ == "__main__":
    main()
