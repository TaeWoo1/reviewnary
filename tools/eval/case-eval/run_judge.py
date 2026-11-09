#!/usr/bin/env python3
"""The separate judge over <run>/judge-input.jsonl (already redacted by the backend's pre-send redaction).

    EVAL_JUDGE_API_KEY=… run_judge.py --run <dir> [--model claude-sonnet-5-5]

A different model from the one that drafted (RUBRIC: the judge is a signal against the owner's answers, never instead of
them). One call per case with judge_prompt.md as the instruction. Writes <run>/judge-output.jsonl; prints ids and
closed answers only. The key comes from the environment of the subprocess that runs this, never from an argument.
"""
import argparse
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def ask(key, model, instruction, case):
    body = json.dumps({"model": model, "max_tokens": 3000, "system": instruction,
                       "messages": [{"role": "user", "content": json.dumps(case, ensure_ascii=False)}]}).encode()
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=body, method="POST", headers={
        "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        out = json.load(r)
    text = "".join(b.get("text", "") for b in out.get("content", []) if b.get("type") == "text").strip()
    start = text.find("{")
    if start < 0:
        return {"unparsed": True}
    try:
        # The first JSON object; anything the model wrote after it is not the answer.
        return json.JSONDecoder().raw_decode(text[start:])[0]
    except ValueError:
        return {"unparsed": True, "stop": out.get("stop_reason")}


ANSWERS = {"2": ("가", "나", "다"), "4": ("맞음", "일부 다름", "자료 없이 말함", "보류"), "6": ("좋음", "고치면 됨", "못 씀")}


def problems(verdict):
    """Why a rubric answer is not one (2026-11-01 fourth review: an incomplete answer must not count as a result)."""
    if not isinstance(verdict, dict) or "error" in verdict or verdict.get("unparsed"):
        return ["no answer"]
    out = []
    for k, allowed in ANSWERS.items():
        # No draft, nothing to judge the tone of: 6 may wait with 4.
        if k == "6" and verdict.get("6") == "보류" and verdict.get("4") == "보류":
            continue
        if verdict.get(k) not in allowed:
            out.append(f"{k} {verdict.get(k)!r} not one of {'/'.join(allowed)}")
    five = verdict.get("5")
    if not isinstance(five, str) or not (five == "아니오" or five == "보류" or five.startswith("예")):
        out.append(f"5 {five!r} not 예 … / 아니오 / 보류")
    three = verdict.get("3")
    if not (three == "초안 없음" or isinstance(three, dict) and isinstance(three.get("asked"), list)
            and isinstance(three.get("missed"), list)):
        out.append("3 missing asked / missed")
    elif isinstance(three, dict) and not isinstance(three.get("toSeller", []), list):
        # Absent is an answer written before 2026-11-02 (no `left` given); present, it must be a list.
        out.append("3 toSeller not a list")
    elif isinstance(three, dict) and "toSeller" in three:
        # Per question, so a count means questions (case-eval-r13: one question came back as two 「missed」 sub-points).
        asked = set(three.get("asked") or [])
        loose = [m for m in (three.get("missed") or []) + (three.get("toSeller") or []) if m not in asked]
        if loose:
            out.append(f"3 {len(loose)} missed/toSeller not copied from asked")
    if not isinstance(verdict.get("1"), str) or not verdict.get("1").strip():
        out.append("1 missing")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--model", default="claude-sonnet-5-5")
    a = ap.parse_args()
    key = os.environ.pop("EVAL_JUDGE_API_KEY", "")
    if not key:
        sys.exit("EVAL_JUDGE_API_KEY is not set")
    instruction = open(os.path.join(HERE, "judge_prompt.md"), encoding="utf-8").read() + \
        "\n\n입력은 사례 하나다. 지시한 JSON 한 줄만 출력한다."
    os.umask(0o077)
    with open(os.path.join(a.run, "judge-input.jsonl"), encoding="utf-8") as f, \
            open(os.path.join(a.run, "judge-output.jsonl"), "w", encoding="utf-8") as out:
        for line in f:
            case = json.loads(line)
            verdict, attempts = {}, 0
            while attempts < 2:
                attempts += 1
                try:
                    verdict = ask(key, a.model, instruction, case)
                except Exception as e:  # noqa: BLE001 — a failed call is a result, not a crash
                    verdict = {"error": type(e).__name__}
                if not problems(verdict):
                    break
            # Two misses are an evaluation failure, counted as one — never read as an answer.
            verdict.update(id=case["id"], judgeModel=a.model, attempts=attempts, problems=problems(verdict))
            out.write(json.dumps(verdict, ensure_ascii=False) + "\n")
            print(case["id"], "2:", verdict.get("2"), "4:", verdict.get("4"), "5:", str(verdict.get("5"))[:3],
                  "6:", verdict.get("6"), "missed:", len((verdict.get("3") or {}).get("missed") or []) if isinstance(verdict.get("3"), dict) else 0,
                  "toSeller:", len((verdict.get("3") or {}).get("toSeller") or []) if isinstance(verdict.get("3"), dict) else 0,
                  verdict.get("error") or "", "INVALID: " + "; ".join(verdict["problems"]) if verdict["problems"] else "")


if __name__ == "__main__":
    main()
