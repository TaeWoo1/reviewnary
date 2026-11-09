#!/usr/bin/env python3
"""Build the blind A/B sheet, the key, the judge inputs and the code fact checks for one reply-agent evaluation.

Reads the EVAL copy of the database (read-only) and writes only to --out, which must be outside the repository: the
outputs carry customer text. See README.md and docs/standing/reply_agent_v1.md §4-1.

    build_sheet.py --runs <run dir>/runs.jsonl --out ~/workspace/sellerops/holds/eval/run1 [--db sellerops_eval] [--seed 28]

Every reply is THIS evaluation's: the old side is the version run_eval.sh's generate call returned, the new side is the
version the agent run it started saved — never a review's latest draft, so a past success cannot stand in for a run
that wrote nothing (review 2026-10-28). Each side carries the passages stored with its version, so a reader can check a
promise against the policy it cites rather than judge fluency alone.
"""
import argparse
import html
import json
import os
import random
import re
import subprocess
import sys

PSQL = "/opt/homebrew/opt/postgresql@15/bin/psql"
ORG = "7146c50f-ff6d-4c83-ae96-18c930e6d8e0"

CHECKS = {
    "N2 연락처·링크·계좌": re.compile(r"01[016789][-. ]?\d{3,4}[-. ]?\d{4}|https?://|www\.|[\w.+-]+@[\w-]+\.[a-z]{2,}|카카오톡|카톡|오픈채팅|계좌"),
    "N3 리뷰 대가·수정 요청": re.compile(r"(리뷰|후기|평가|별점)[^.!?\n]{0,15}(수정|삭제|지워|지우|내려|바꿔|올려)|(적립금|포인트|쿠폰|사은품)[^.!?\n]{0,20}(리뷰|후기)"),
    "N7 삭제·신고·비난": re.compile(r"(삭제|신고|법적\s*조치|고소)\s*(하겠|할\s*예정|조치하겠|처리하겠|드리겠)|허위\s*(리뷰|후기)|블랙\s*컨슈머"),
    "N8 반품법보다 불리": re.compile(r"개봉[^.!?\n]{0,15}(반품|교환|환불)[^.!?\n]{0,10}(불가|안\s*되|어렵|못)|(?<!\d)[1-6]\s*일\s*(이내|안에|까지)[^.!?\n]{0,15}(반품|교환|환불)"),
    "미루는 문장": re.compile(r"확인\s*후\s*(안내|연락)|추후\s*안내|연락\s*드리겠"),
    "범용 감사만": re.compile(r"^\s*(안녕하세요[^.!?]*[.!?]\s*)?(소중한\s*)?(리뷰|후기)\s*(남겨\s*주셔서\s*)?감사합니다[.!]?\s*$"),
}


def rows(db, sql):
    env = dict(os.environ, PGOPTIONS="-c default_transaction_read_only=on")
    out = subprocess.run([PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "sellerops", "-d", db, "-tA", "-c", sql],
                         capture_output=True, text=True, env=env, check=True).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def uid(value):
    v = str(value or "")
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", v):
        raise SystemExit(f"not an id: {v!r}")
    return v


def review_row(db, rid):
    found = rows(db, f"""select row_to_json(t) from (select r.id, r.rating, r.body, p.name as product
        from reviews r left join products p on p.id = r.product_id where r.org_id = '{ORG}' and r.id = '{rid}') t""")
    return found[0] if found else None


def version_row(db, rid, version):
    """One stored draft version and the passages stored with it — the evidence that version cites."""
    if not isinstance(version, int):
        return None
    draft = rows(db, f"""select row_to_json(t) from (select version, body, model_version, author_kind, created_by
        from review_reply_draft where review_id = '{rid}' and version = {version}) t""")
    if not draft:
        return None
    evidence = rows(db, f"""select row_to_json(t) from (select e.kind, e.title,
        coalesce(pk.content, ok.content, am.answer_body) as text
        from review_draft_evidence e
        left join product_knowledge_chunks pk on e.kind = 'PRODUCT_KNOWLEDGE' and pk.id = e.chunk_id
        left join org_knowledge_chunks ok on e.kind = 'ORG_POLICY' and ok.id = e.chunk_id
        left join answer_memory am on e.kind = 'ANSWER_MEMORY' and am.id = e.source_id
        where e.org_id = '{ORG}' and e.review_id = '{rid}' and e.draft_version = {version} order by e.ordinal) t""")
    return dict(draft[0], evidence=evidence)


def run_row(db, run_id):
    if not run_id:
        return None
    found = rows(db, f"""select row_to_json(t) from (select id, category, outcome, handoff_reason, first_failed_rules,
        failed_rules, checks_failed, revisions, draft_version from reply_agent_run
        where org_id = '{ORG}' and id = '{uid(run_id)}') t""")
    return found[0] if found else None


def knowledge_fingerprint(db):
    """One hash over every passage text a citation can be read back from — taken before and after a run."""
    found = rows(db, f"""select row_to_json(t) from (select md5(coalesce((select string_agg(id::text || md5(content), ','
        order by id) from product_knowledge_chunks where org_id = '{ORG}'), '') || '|' || coalesce((select string_agg(
        id::text || md5(content), ',' order by id) from org_knowledge_chunks where org_id = '{ORG}'), '') || '|' ||
        coalesce((select string_agg(id::text || md5(answer_body), ',' order by id) from answer_memory
        where org_id = '{ORG}'), '')) as fp) t""")
    return found[0]["fp"]


def no_draft(side, run):
    """Why a side wrote nothing — a person's by design, or a failure. Key only: the reason would unblind the sheet."""
    if side == "old":
        return "생성 안 함: " + (run["old"].get("note") or run["old"].get("why") or "알 수 없음")
    outcome, reason = run["new"].get("outcome"), run["new"].get("reason") or ""
    label = {"HANDOFF": "사람에게 넘김", "SKIPPED": "저장 안 함", "FAILED": "생성 실패"}.get(outcome, "알 수 없음")
    return f"{label}: {reason}".rstrip(": ")


HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
JAVA = "/opt/homebrew/opt/openjdk@17/bin/java"


def redact_all(texts):
    """The backend's pre-send redaction over every text, in one call (Redact.java). Fails closed: no redactor, no file."""
    import base64
    classes = os.path.join(REPO, "backend", "build", "classes", "java", "main")
    if not os.path.isdir(classes):
        sys.exit("backend classes are not built (./gradlew compileJava) — the judge files need the backend's redaction")
    payload = "".join(base64.b64encode((t or "").encode("utf-8")).decode() + "\n" for t in texts)
    done = subprocess.run([JAVA, "-cp", classes, os.path.join(HERE, "Redact.java")], input=payload,
                          capture_output=True, text=True, check=True).stdout.splitlines()
    if len(done) != len(texts):
        sys.exit("redaction returned a different number of texts — refusing to write the judge files")
    return [base64.b64decode(line).decode("utf-8") if t is not None else None for t, line in zip(texts, done)]


def redacted_judge(judge):
    """The judge items with the review, both replies and every passage redacted as a model payload is."""
    slots = []
    for item in judge:
        slots += [item["review"], item["A"], item["B"]]
        for side in ("evidenceA", "evidenceB"):
            for e in item[side]:
                slots += [e.get("title"), e.get("text")]
    done = iter(redact_all(slots))
    out = []
    for item in judge:
        r = dict(item, review=next(done), A=next(done), B=next(done))
        for side in ("evidenceA", "evidenceB"):
            r[side] = [dict(e, title=next(done), text=next(done)) for e in item[side]]
        out.append(r)
    for r in out:
        flat = json.dumps(r, ensure_ascii=False)
        if re.search(r"01[016789][-. ]?\d{3,4}[-. ]?\d{4}|[\w.+-]+@[\w-]+\.[a-z]{2,}", flat):
            sys.exit(f"{r['id']}: a phone number or an email survived redaction — refusing to write the judge files")
    return out


def facts(text, max_len=200):
    found = [name for name, pattern in CHECKS.items() if text and pattern.search(text)]
    if text and len(text.strip()) > max_len:
        found.append(f"F1 {max_len}자 초과")
    return found


def main():
    if len(sys.argv) in (3, 4) and sys.argv[1] == "fingerprint":
        # An optional org: the synthetic store is its own (case-eval/synthetic_store.py).
        global ORG
        if len(sys.argv) == 4:
            ORG = uid(sys.argv[3])
        print(knowledge_fingerprint(sys.argv[2]))
        return
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", required=True, help="runs.jsonl written by run_eval.sh")
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", default="sellerops_eval")
    ap.add_argument("--seed", type=int, default=28)
    a = ap.parse_args()
    out = os.path.abspath(os.path.expanduser(a.out))
    if out == REPO or out.startswith(REPO + os.sep):
        sys.exit("--out must be outside the repository: the outputs carry customer text")
    os.makedirs(out, exist_ok=True)
    rnd = random.Random(a.seed)

    # The passages a version cites are read back from the knowledge tables as they are NOW, and an edit re-creates the
    # chunks (second review, 2026-10-28). So the knowledge must be what it was during the run: run_eval.sh fingerprints
    # it before and after, and this compares both with the present. Any difference withholds every fact check.
    run_dir = os.path.dirname(os.path.abspath(os.path.expanduser(a.runs)))
    prints = []
    for name in ("knowledge-before.txt", "knowledge-after.txt"):
        path = os.path.join(run_dir, name)
        prints.append(open(path).read().strip() if os.path.exists(path) else None)
    frozen = None not in prints and prints[0] == prints[1] == knowledge_fingerprint(a.db)
    if not frozen:
        print("knowledge changed during or after the run (or was not fingerprinted) — fact checks are withheld")

    cases, key, judge, fact_rows = [], {}, [], {}
    for line in open(os.path.expanduser(a.runs), encoding="utf-8"):
        if not line.strip():
            continue
        run = json.loads(line)
        rid = uid(run["reviewId"])
        review = review_row(a.db, rid)
        if not review:
            continue
        old = version_row(a.db, rid, run["old"].get("version"))
        agent_run = run_row(a.db, run["new"].get("runId"))
        new_version = agent_run.get("draft_version") if agent_run else None
        new = version_row(a.db, rid, new_version)
        if old and (old.get("model_version") or "").startswith("reply-agent/"):
            sys.exit(f"{rid}: old version {old['version']} is the agent's — the generate call did not take the old path")
        if new and not (new.get("model_version") or "").startswith("reply-agent/"):
            sys.exit(f"{rid}: version {new_version} is not the agent's — refusing to show it as the agent's")
        drafts = {"old": old, "new": new}
        sides = ["old", "new"]
        rnd.shuffle(sides)
        cid = f"C{len(cases) + 1:02d}"
        body = {s: (drafts[s] or {}).get("body") for s in sides}
        evidence = {s: [{"kind": e["kind"], "title": e.get("title"), "text": e.get("text")}
                        for e in (drafts[s] or {}).get("evidence", [])] for s in sides}
        # A side's facts can be checked only when every passage it cites was read back, from knowledge that held still.
        checkable = {side: frozen and bool(body[side]) and all(e.get("text") for e in evidence[side]) for side in sides}
        key[cid] = {"A": sides[0], "B": sides[1], "set": run["set"], "reviewId": rid,
                    "factsCheckable": {"A": checkable[sides[0]], "B": checkable[sides[1]]},
                    "versions": {s: (drafts[s] or {}).get("version") for s in sides},
                    "provenance": {s: {k: (drafts[s] or {}).get(k) for k in ("model_version", "author_kind", "created_by")}
                                   for s in sides},
                    "noDraft": {s: no_draft(s, run) for s in sides if not body[s]},
                    "run": agent_run}
        fact_rows[cid] = {"A": facts(body[sides[0]]), "B": facts(body[sides[1]])}
        cases.append({"id": cid, "rating": review["rating"], "product": review.get("product"),
                      "review": review.get("body") or "", "A": body[sides[0]], "B": body[sides[1]],
                      "evA": evidence[sides[0]], "evB": evidence[sides[1]]})
        judge.append({"id": cid, "review": review.get("body") or "",
                      "A": body[sides[0]], "B": body[sides[1]], "evidenceA": evidence[sides[0]],
                      "evidenceB": evidence[sides[1]], "factsCheckableA": checkable[sides[0]],
                      "factsCheckableB": checkable[sides[1]]})

    with open(os.path.join(out, "key.json"), "w", encoding="utf-8") as f:
        json.dump(key, f, ensure_ascii=False, indent=1)
    with open(os.path.join(out, "facts.json"), "w", encoding="utf-8") as f:
        json.dump(fact_rows, f, ensure_ascii=False, indent=1)
    # The judge is another model: what it reads is redacted exactly as every model payload is (second review, 2026-10-28).
    # The owner's blind sheet stays whole — the seller reading their own customers' words on this machine.
    judge = redacted_judge(judge)
    # The judge reads every case twice, A/B swapped, so a position preference shows as disagreement (Zheng et al. 2023).
    for name, swap in (("judge-input.jsonl", False), ("judge-input-swapped.jsonl", True)):
        with open(os.path.join(out, name), "w", encoding="utf-8") as f:
            for item in judge:
                if swap:
                    item = dict(item, A=item["B"], B=item["A"], evidenceA=item["evidenceB"], evidenceB=item["evidenceA"],
                                factsCheckableA=item["factsCheckableB"], factsCheckableB=item["factsCheckableA"])
                f.write(json.dumps(item, ensure_ascii=False) + "\n")
    with open(os.path.join(out, "blind.html"), "w", encoding="utf-8") as f:
        f.write(sheet(cases))
    print(f"{len(cases)} cases → {out}")


def sheet(cases):
    blocks = []
    for c in cases:
        def reply(side):
            text = c[side]
            body = html.escape(text) if text else "<em>(이 방식은 초안을 만들지 않았어요 — 이유는 채점 뒤 key.json에)</em>"
            ev = c["ev" + side]
            cited = ("".join(f'<li><b>{html.escape(e.get("title") or e["kind"])}</b> {html.escape(e.get("text") or "(원문 없음)")}</li>'
                             for e in ev) if ev else "<li>근거 없음</li>")
            name = f"{c['id']}-{side}"
            marks = "".join(
                f'<label><input type="checkbox" name="{name}-flag" value="{v}"> {v}</label>'
                for v in ["고객 상황을 놓침", "질문·문제 일부를 빠뜨림", "이미 해 본 방법을 권함", "근거와 다른 사실·약속",
                          "근거 없는 약속", "범용 문구", "연락처·개인정보", "말투 어색"])
            grade = "".join(
                f'<label><input type="radio" name="{name}-grade" value="{v}"> {v}</label>' for v in ["좋음", "보통", "나쁨"])
            return (f'<div class="reply"><h4>답글 {side}</h4><p class="body">{body}</p>'
                    f'<details><summary>이 답글이 본 근거 {len(ev)}건</summary><ul class="ev">{cited}</ul></details>'
                    f'<div class="row">{grade}</div><div class="row flags">{marks}</div></div>')
        better = "".join(
            f'<label><input type="radio" name="{c["id"]}-better" value="{v}"> {v}</label>' for v in ["A", "B", "비슷함"])
        blocks.append(
            f'<section data-id="{c["id"]}"><h3>{c["id"]} · ★{c["rating"]} · {html.escape(c.get("product") or "상품 미상")}</h3>'
            f'<blockquote>{html.escape(c["review"])}</blockquote><div class="pair">{reply("A")}{reply("B")}</div>'
            f'<div class="row">더 나은 답글: {better}</div>'
            f'<textarea name="{c["id"]}-memo" placeholder="메모(선택)"></textarea></section>')
    return """<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>답글 블라인드 비교</title><style>
body{font:15px/1.6 -apple-system,'Pretendard',sans-serif;max-width:980px;margin:24px auto;padding:0 16px;color:#1d1d1f;background:#faf9f7}
section{background:#fff;border:1px solid #e5e2dc;border-radius:14px;padding:16px;margin:16px 0}
blockquote{margin:8px 0;padding:10px 12px;background:#f3f1ec;border-radius:10px;white-space:pre-wrap}
.pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:700px){.pair{grid-template-columns:1fr}}
.reply{border:1px solid #e5e2dc;border-radius:10px;padding:10px}.body{white-space:pre-wrap}
details{margin:6px 0;font-size:13px;color:#555}.ev{margin:4px 0;padding-left:18px}
.row{margin:6px 0;display:flex;flex-wrap:wrap;gap:10px}.flags label{font-size:13px;color:#555}
textarea{width:100%;min-height:44px;margin-top:6px}button{font:inherit;padding:8px 14px;border-radius:10px;border:1px solid #1d1d1f;background:#1d1d1f;color:#fff}
</style><h1>답글 블라인드 비교</h1><p>A와 B 중 어느 쪽이 새 방식인지 가려 두었어요. 답글마다 평가하고, 더 나은 쪽을 골라 주세요.
약속이나 사실은 「이 답글이 본 근거」를 펼쳐 실제 정책·상품 정보와 맞는지 확인해 주세요.
고른 내용은 이 브라우저에 저장되고, 끝나면 아래 버튼으로 결과 파일을 저장해 주세요.</p>
""" + "".join(blocks) + """<p><button id="export">결과 파일 저장</button></p><script>
const KEY='reply-blind-v1';
const saved=(()=>{try{return JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){return {}}})();
for(const el of document.querySelectorAll('input,textarea')){const k=el.name+'|'+(el.value||'');
 if(el.type==='radio'||el.type==='checkbox'){if(saved[k])el.checked=true}else if(saved[el.name]!=null){el.value=saved[el.name]}}
document.addEventListener('change',e=>{const el=e.target;if(el.type==='radio'){for(const o of document.querySelectorAll(`input[name="${el.name}"]`))delete saved[o.name+'|'+o.value]}
 if(el.type==='radio'||el.type==='checkbox'){saved[el.name+'|'+el.value]=el.checked}else{saved[el.name]=el.value}
 try{localStorage.setItem(KEY,JSON.stringify(saved))}catch(e){}});
document.getElementById('export').onclick=()=>{const out={};for(const s of document.querySelectorAll('section')){const id=s.dataset.id;const r={};
 for(const side of ['A','B']){r[side]={grade:(s.querySelector(`input[name="${id}-${side}-grade"]:checked`)||{}).value||null,
  flags:[...s.querySelectorAll(`input[name="${id}-${side}-flag"]:checked`)].map(x=>x.value)}}
 r.better=(s.querySelector(`input[name="${id}-better"]:checked`)||{}).value||null;r.memo=(s.querySelector('textarea')||{}).value||'';out[id]=r}
 const blob=new Blob([JSON.stringify(out,null,1)],{type:'application/json'});const a=document.createElement('a');
 a.href=URL.createObjectURL(blob);a.download='owner-scores.json';a.click()};
</script></html>"""


if __name__ == "__main__":
    main()
