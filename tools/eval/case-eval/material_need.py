#!/usr/bin/env python3
"""Whether a review's answer needs the store's material, apart from whether retrieval found it (2026-10-31 review:
「이 설정에서 못 찾았다」 is not 「쓸 자료가 없었다」).

    EVAL_JUDGE_API_KEY=… material_need.py --run <dir> [--model claude-sonnet-5-5] [--reached cited]

Reads <run>/material.jsonl (ReviewRetrievalTraceIT with TRACE_MATERIAL_OUT: the redacted review, EVERY active item the
store has for it, and which items reached the model). A separate model, shown all of it, says which items an answer
needs — without being told what retrieval returned — and the script compares the two:

    NOT_NEEDED  the answer needs no store fact, and none was sent              (correct)
    FOUND       every needed item reached the model
    MISSED      a needed item exists and did not reach the model               (retrieval failure)
    ABSENT      the answer needs a fact no item carries                        (material gap)
    WRONG       an item reached the model that the answer must not use

Writes <run>/material-need.jsonl; prints ids and closed answers only. The key comes from the environment of the
subprocess that runs this, never from an argument.
"""
import argparse
import json
import os
import sys

from run_judge import ask

INSTRUCTION = """너는 판매자의 리뷰 답글에 어떤 가게 자료가 필요한지 판단한다. 답글을 쓰지 않는다.

입력: 고객 리뷰 하나(개인정보는 가려짐)와, 이 가게가 이 리뷰에 쓸 수 있게 등록한 자료 전부(id · 종류 · 제목 · 본문).

판단 기준:
- needed: 답글이 고객이 말한 문제나 질문에 제대로 답하려면 반드시 쓰거나 확인해야 하는 자료의 id. 칭찬 · 단순 감사 · 공감으로 충분한 \
리뷰면 비운다. 「있으면 좋다」가 아니라, 빠지면 답이 틀리거나 비게 되는 자료만 넣는다.
- useful: 꼭 필요하지는 않지만 답글을 더 구체적으로 만드는 자료의 id.
- mustNotUse: 비슷해 보이지만 이 리뷰에 쓰면 틀린 약속이나 엉뚱한 안내가 되는 자료의 id. 예: 단순한 배송 불만에 교환 정책을 붙이는 것.
- missingFact: 답에 필요한데 어떤 자료에도 없는 사실을 한 줄로. 없으면 빈 문자열.
- need: NONE(자료가 필요 없음) · PRESENT(필요한 자료가 다 있음) · PARTIAL(일부만 있음) · ABSENT(필요한 자료가 없음) 중 하나.
- reason: 판단 이유 한 문장. 고객 문장을 그대로 옮기지 않는다.

반드시 아래 JSON 한 줄만 출력한다.
{"need":"NONE|PRESENT|PARTIAL|ABSENT","needed":["<id>"],"useful":["<id>"],"mustNotUse":["<id>"],"missingFact":"","reason":""}"""


def instruction_for(lane):
    """The same yardstick for an inquiry: the answer is a reply to the customer's question, not a public review reply."""
    if lane == "review":
        return INSTRUCTION
    return (INSTRUCTION.replace("판매자의 리뷰 답글에", "판매자가 고객 문의에 보낼 답변에").replace("고객 리뷰 하나", "고객 문의 하나")
            .replace("이 리뷰에", "이 문의에").replace("답글이", "답변이").replace("답글을", "답변을")
            .replace("칭찬 · 단순 감사 · 공감으로 충분한 리뷰면", "자료 없이 답할 수 있는 문의(인사 · 단순 확인)면")
            .replace("단순한 배송 불만에 교환 정책을 붙이는 것", "다른 상품 · 다른 옵션의 사양을 이 상품에 쓰는 것"))


NEEDS = ("NONE", "PRESENT", "PARTIAL", "ABSENT")
LISTS = ("needed", "useful", "mustNotUse")


def problems(verdict, item_ids):
    """Why an answer is not one (2026-11-01 fourth review: 「{}」, an unknown need, PRESENT with nothing needed all read as
    NOT_NEEDED). Empty when the answer is complete, uses only the listed values and ids, and agrees with itself."""
    if not isinstance(verdict, dict) or "error" in verdict or verdict.get("unparsed"):
        return ["no answer"]
    out = []
    for k in LISTS:
        v = verdict.get(k)
        if not isinstance(v, list) or not all(isinstance(i, str) for i in v):
            out.append(f"{k} missing or not a list of ids")
    for k in ("missingFact", "reason"):
        if not isinstance(verdict.get(k), str):
            out.append(f"{k} missing")
    need = verdict.get("need")
    if need not in NEEDS:
        out.append(f"need {need!r} not one of {'/'.join(NEEDS)}")
    if out:
        return out
    for k in LISTS:
        unknown = [i for i in verdict[k] if i not in item_ids]
        if unknown:
            out.append(f"{k} names ids the store does not have: {unknown}")
    if set(verdict["needed"]) & set(verdict["mustNotUse"]):
        out.append("an id is both needed and must not be used")
    missing = verdict["missingFact"].strip()
    if need == "NONE" and (verdict["needed"] or missing):
        out.append("NONE with something needed")
    if need == "PRESENT" and (not verdict["needed"] or missing):
        out.append("PRESENT without a needed id, or with a missing fact")
    if need == "PARTIAL" and (not verdict["needed"] or not missing):
        out.append("PARTIAL needs both a needed id and a missing fact")
    if need == "ABSENT" and not missing:
        out.append("ABSENT without the missing fact")
    return out


def classify(verdict, reached):
    needed = set(verdict.get("needed") or [])
    banned = set(verdict.get("mustNotUse") or [])
    got = set(r for r in reached if r[:1] in ("O", "P"))
    out = []
    if got & banned:
        out.append("WRONG")
    if needed - got:
        out.append("MISSED")
    if verdict.get("need") in ("ABSENT", "PARTIAL") and (verdict.get("missingFact") or "").strip():
        out.append("ABSENT")
    if not out:
        out.append("FOUND" if needed else "NOT_NEEDED")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--model", default="claude-sonnet-5-5")
    # 2026-11-01 small bundle: every item reaches the writer, so 「found」 is what the saved version cites.
    ap.add_argument("--reached", default="reachedModel", choices=["reachedModel", "cited"])
    # Read the answers already in <run>/material-need.jsonl again, without a call: which of them are answers at all.
    ap.add_argument("--revalidate", action="store_true")
    # inquiry_material.py writes the same rows for inquiries (2026-11-01 eighth review); the judge is told it is a question.
    ap.add_argument("--lane", default="review", choices=["review", "inquiry"])
    a = ap.parse_args()
    if not os.path.realpath(a.run).startswith(os.path.realpath(os.path.expanduser("~/workspace/sellerops/holds"))):
        sys.exit("outputs go under ~/workspace/sellerops/holds only")
    if a.revalidate:
        revalidate(a)
        return
    key = os.environ.pop("EVAL_JUDGE_API_KEY", "")
    if not key:
        sys.exit("EVAL_JUDGE_API_KEY is not set")
    os.umask(0o077)
    with open(os.path.join(a.run, "material.jsonl"), encoding="utf-8") as f, \
            open(os.path.join(a.run, "material-need.jsonl"), "w", encoding="utf-8") as out:
        for line in f:
            row = json.loads(line)
            # The judge is not told what retrieval returned: its answer is the yardstick, not a second opinion on it.
            case = {a.lane: row["body"], "items": row["items"]}
            ids = {i["id"] for i in row["items"]}
            verdict, attempts = {}, 0
            # One more ask when the first is not an answer; a second miss is an evaluation failure, counted as one.
            while attempts < 2:
                attempts += 1
                try:
                    verdict = ask(key, a.model, instruction_for(a.lane), case)
                except Exception as e:  # noqa: BLE001 — a failed call is a result, not a crash
                    verdict = {"error": type(e).__name__}
                if not problems(verdict, ids):
                    break
            bad = problems(verdict, ids)
            reached = row.get(a.reached) or []
            verdict.update(review=row["review"], rating=row["rating"], reachedModel=row["reachedModel"],
                           cited=row.get("cited"), inBundle=row.get("inBundle"), savedAuthor=row.get("savedAuthor"),
                           knowledgeState=row["knowledgeState"], items=len(row["items"]), judgeModel=a.model,
                           readAs=a.reached)
            verdict["attempts"] = attempts
            verdict["problems"] = bad
            verdict["result"] = ["INVALID"] if bad else classify(verdict, reached)
            out.write(json.dumps(verdict, ensure_ascii=False) + "\n")
            print(row["review"], f"★{row['rating']}", "need:", verdict.get("need"), "needed:", verdict.get("needed"),
                  "mustNotUse:", verdict.get("mustNotUse"), f"{a.reached}:", reached, "→",
                  "/".join(verdict["result"]), verdict.get("error") or "")


def revalidate(a):
    items = {}
    with open(os.path.join(a.run, "material.jsonl"), encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            items[row["review"]] = {i["id"] for i in row["items"]}
    counts = {}
    with open(os.path.join(a.run, "material-need.jsonl"), encoding="utf-8") as f:
        for line in f:
            v = json.loads(line)
            bad = problems(v, items.get(v["review"], set()))
            result = ["INVALID"] if bad else classify(v, v.get(a.reached) or [])
            for r in result:
                counts[r] = counts.get(r, 0) + 1
            print(v["review"], "/".join(result), "; ".join(bad))
    print("counts:", counts)


if __name__ == "__main__":
    main()
