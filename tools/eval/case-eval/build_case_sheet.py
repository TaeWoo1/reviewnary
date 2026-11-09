#!/usr/bin/env python3
"""The per-case sheet (RUBRIC.md v1) from one run of run_cases.sh — this run's versions only.

    build_case_sheet.py --run <out dir> [--db sellerops_eval]

Reads <run>/runs.jsonl and <run>/raw/, then the copy database read-only, and writes into <run>:
  sheet.html          the owner's sheet — original, what the product did, the draft, the passages it cited, what the
                      store's material held, and an answer form for every RUBRIC item (「JSON 저장」 downloads answers)
  auto.json           what code can say per case: outcome, judgement, needs, cited evidence, material available,
                      pattern checks, and the model calls the usage ledger recorded during the run
  judge-input.jsonl   the same cases for a separate judge model, redacted by the backend's own pre-send redaction
Nothing here prints customer text; the files carry it, so <run> must be under ~/workspace/sellerops/holds/.
"""
import argparse
import html
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "reply-agent"))
import build_sheet  # noqa: E402
from build_sheet import ORG, facts, knowledge_fingerprint, redact_all, rows, run_row, uid, version_row  # noqa: E402

ITEMS = [
    ("1", "분류", ["맞음", "부분", "틀림"]),
    ("2", "대응 필요성", ["맞음", "틀림", "위험한 틀림"]),
    ("3", "여러 문제의 누락", ["빠짐 없음", "일부 빠짐", "해당 없음"]),
    ("4", "근거 적합성", ["맞음", "일부 엉뚱함", "틀림", "근거 없이 씀", "해당 없음"]),
    ("4-1", "자료가 실제로 있었나", ["있었고 씀", "있었는데 안 씀", "없었음"]),
    ("5", "잘못된 약속", ["없음", "있음"]),
    ("6", "말투", ["좋음", "고치면 됨", "못 씀", "해당 없음"]),
    ("7", "처리 상태", ["맞음", "틀림", "이유를 말하지 않음"]),
]


def one(db, sql):
    found = rows(db, f"select row_to_json(t) from ({sql}) t")
    return found[0] if found else None


def material(db, product_id):
    """What the store's knowledge held for this case at run time — RUBRIC 4-1."""
    pid = f"'{uid(product_id)}'" if product_id else "null"
    return one(db, f"""select
        (select count(*) from product_knowledge_sources where org_id = '{ORG}' and product_id = {pid}) as product_sources,
        (select count(*) from product_knowledge_chunks where org_id = '{ORG}' and product_id = {pid}) as product_chunks,
        (select count(*) from product_facts where org_id = '{ORG}' and product_id = {pid}) as product_facts,
        (select count(*) from org_knowledge_sources where org_id = '{ORG}' and active) as org_policies,
        (select count(*) from answer_memory where org_id = '{ORG}') as past_answers,
        (select count(*) from answer_memory where org_id = '{ORG}' and product_id = {pid}) as past_answers_for_product""")


DAYS = ["월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"]


def moment(received):
    """When the inquiry arrived, word for word as the product writes it (InquiryMoment.of) — the drafter is told it, so a
    draft that says 「말씀하신 날(일요일)」 is checked against it, not against nothing (2026-11-02, case-eval-r13)."""
    if not received:
        return None
    import datetime
    t = received if isinstance(received, datetime.datetime) else datetime.datetime.fromisoformat(str(received))
    t = t.astimezone(datetime.timezone(datetime.timedelta(hours=9)))
    clock = ("오전 " if t.hour < 12 else "오후 ") + str(t.hour % 12 or 12) + "시" + ("" if t.minute == 0 else f" {t.minute}분")
    weekend = t.weekday() >= 5
    return (f"{t.year}년 {t.month}월 {t.day}일 {DAYS[t.weekday()]} {clock}(한국 시간) · "
            + ("주말" if weekend else "평일(공휴일인지는 알 수 없음)"))


def left_questions(raw):
    """The questions the seller is shown to answer before sending (tenth review) — what a reply leaves on purpose, so the
    judge can tell a question left to the seller from one the reply dropped (2026-11-02: the summary used to subtract
    their COUNT from the judge's missed list by position)."""
    out = []
    for q in (raw.get("body") or {}).get("openQuestions") or []:
        text = q.get("question") or q.get("text") if isinstance(q, dict) else q
        if isinstance(text, str) and text.strip():
            out.append(text.strip())
    return out


def review_case(db, run):
    rid = uid(run["id"])
    review = one(db, f"""select r.id, r.rating, r.body, r.product_id, p.name as product, r.media_count, c.code as channel
        from reviews r left join products p on p.id = r.product_id left join channels c on c.id = r.channel_id
        where r.org_id = '{ORG}' and r.id = '{rid}'""")
    judgement = one(db, f"""select category, also_categories, needs_response, answered, safety_signal, handoff_reason,
        unsure_reason, source, model_version from review_judgement where org_id = '{ORG}' and review_id = '{rid}'
        order by judged_at desc limit 1""")
    agent = run_row(db, run.get("runId")) if run.get("runId") else None
    draft = version_row(db, rid, run.get("version"))
    return {
        "customer": {"title": None, "body": review["body"], "rating": review["rating"], "product": review["product"],
                     "photos": review["media_count"], "channel": review["channel"]},
        "work": "REVIEW_REPLY",
        "did": {"outcome": run.get("outcome"), "reason": run.get("reason"), "category": run.get("category"),
                "openQuestions": run.get("openQuestions") or 0, "judgement": judgement, "run": agent},
        "draft": draft and draft["body"], "model": draft and draft["model_version"],
        "evidence": (draft or {}).get("evidence") or [],
        "needs": [],
        "left": [],
        "material": material(db, review["product_id"]),
    }


def inquiry_case(db, run, raw):
    wid = uid(run["id"])
    inq = one(db, f"""select i.title, i.body, i.product_id, p.name as product, c.code as channel, i.source_subtype,
        i.received_at
        from inquiry_work_item w join inquiries i on i.id = w.inquiry_id join channels c on c.id = w.channel_id
        left join products p on p.id = i.product_id where w.org_id = '{ORG}' and w.id = '{wid}'""")
    proposal = one(db, f"""select summary_category, action_kind, provider_kind, provider_version from inquiry_proposal
        where org_id = '{ORG}' and work_item_id = '{wid}' order by created_at desc limit 1""")
    version = run.get("version")
    draft = None
    evidence = []
    if isinstance(version, int):
        draft = one(db, f"""select comments as body, model_version, knowledge_state, answer_basis from inquiry_reply_draft
            where org_id = '{ORG}' and work_item_id = '{wid}' and version = {version}""")
        evidence = rows(db, f"""select row_to_json(t) from (select e.kind, e.title,
            coalesce(pk.content, ok.content, am.answer_body) as text
            from inquiry_draft_evidence e
            left join product_knowledge_chunks pk on e.kind = 'PRODUCT_KNOWLEDGE' and pk.id = e.chunk_id
            left join org_knowledge_chunks ok on e.kind = 'ORG_POLICY' and ok.id = e.chunk_id
            left join answer_memory am on e.kind = 'ANSWER_MEMORY' and am.id = e.source_id
            where e.org_id = '{ORG}' and e.work_item_id = '{wid}' and e.draft_version = {version} order by e.ordinal) t""")
    body = raw.get("body") or {}
    gap = body.get("knowledgeGap") or {}
    return {
        "customer": {"title": inq["title"], "body": inq["body"], "rating": None, "product": inq["product"],
                     "channel": inq["channel"], "received": moment(inq.get("received_at"))},
        "work": inq["source_subtype"] or f"{inq['channel']}_INQUIRY",
        "did": {"outcome": inquiry_outcome(run, draft), "proposal": proposal, "authorKind": run.get("authorKind"),
                "unavailableKind": run.get("unavailableKind"), "proposed": run.get("proposed"),
                "knowledgeState": body.get("knowledgeState"), "knowledgeNote": body.get("knowledgeNote"),
                "answerBasis": body.get("answerBasis"), "answerBasisNote": body.get("answerBasisNote"),
                "answerBasisAction": body.get("answerBasisAction"), "unavailable": body.get("unavailableMessage"),
                "http": run.get("http")},
        "draft": draft and draft["body"], "model": draft and draft["model_version"],
        "evidence": evidence,
        "needs": [{k: n.get(k) for k in ("ask", "type", "status", "statusKo", "evidence", "missing", "askCustomer")}
                  for n in (gap.get("needs") or [])],
        "left": left_questions(raw),
        "material": material(db, inq["product_id"]),
    }


def inquiry_outcome(run, draft):
    """An inquiry's result as a closed word (2026-11-01 eighth review: a draft, a hold, a failure and 「no basis — the seller
    writes」 are four different results, and none of them is a review's SAVED)."""
    if run.get("http") != 200:
        return "FAILED"
    if draft:
        if run.get("authorKind") == "SELLER_APPROVED_FALLBACK":
            return "DRAFT_FALLBACK"
        if run.get("answerBasis") == "PARTIAL_ANSWER" or (run.get("openQuestions") or 0) > 0:
            return "DRAFT_PARTIAL"  # tenth review: part answered, the rest left to the seller and the send held
        return "DRAFT_ASKS_CUSTOMER" if run.get("answerBasis") == "NEEDS_CLARIFICATION" else "DRAFT"
    kind = run.get("unavailableKind")
    if kind == "HELD_BY_CHECK":
        return "HELD_BY_CHECK"
    if kind == "WAITING_FOR_CHECK":
        return "WAITING_FOR_CHECK"
    if kind == "NOT_RUN" or run.get("unavailable"):
        return "FAILED"
    return "NO_BASIS"


def usage(db, started, finished):
    return rows(db, f"""select row_to_json(t) from (select kind, step, actor, trigger_kind, count(*) as calls,
        sum(input_tokens) as input_tokens, sum(output_tokens) as output_tokens from agent_llm_usage
        where org_id = '{ORG}' and created_at between '{started}' and '{finished}'
        group by 1, 2, 3, 4 order by 1, 2) t""")


def esc(v):
    return html.escape("" if v is None else str(v))


def card(i, c):
    cust = c["customer"]
    head = f"C{i:02d} · {'리뷰' if c['kind'] == 'review' else '문의'} · {c['set']}"
    meta = " · ".join(x for x in [f"★{cust['rating']}" if cust.get("rating") else None, cust.get("channel"),
                                   cust.get("product") or "상품 없음",
                                   f"사진 {cust['photos']}" if cust.get("photos") else None] if x)
    ev = "".join(f"<li><b>{esc(e.get('kind'))}</b> {esc(e.get('title'))}<div class=q>{esc(e.get('text'))}</div></li>"
                 for e in c["evidence"]) or "<li class=none>인용한 근거 없음</li>"
    needs = "".join(f"<li>{esc(n.get('ask'))} — <b>{esc(n.get('statusKo') or n.get('status'))}</b>"
                    f"{' · 없는 것: ' + esc(n.get('missing')) if n.get('missing') else ''}</li>" for n in c["needs"])
    m = c["material"] or {}
    did = c["did"]
    if c["kind"] == "review":
        j = did.get("judgement") or {}
        state = (f"{esc(did.get('outcome'))} {esc(did.get('reason') or '')} · 범주 {esc(j.get('category'))}"
                 f" (+{esc(j.get('also_categories'))}) · 답해야 함 {esc(j.get('needs_response'))}"
                 f" · 안전 {esc(j.get('safety_signal'))} · 넘긴 이유 {esc(j.get('handoff_reason'))}")
    else:
        p = did.get("proposal") or {}
        state = (f"{esc(did.get('outcome'))} · 제안 범주 {esc(p.get('summary_category'))} · 지식 {esc(did.get('knowledgeState'))}"
                 f" · 답의 근거 {esc(did.get('answerBasis'))} {esc(did.get('answerBasisNote') or '')}"
                 f"{' · 생성 불가: ' + esc(did.get('unavailable')) if did.get('unavailable') else ''}")
    rec = c.get("recommend") or {}

    def item(k, name, opts):
        verdict, why = (rec.get(k) or [None, None])[:2]
        radios = "".join(f"<label class=o><input type=radio name='{k}.verdict' value='{esc(o)}'"
                         f"{' checked' if o == verdict else ''}>{esc(o)}</label>" for o in opts)
        reason = f"<p class=rec>추천: <b>{esc(verdict)}</b> — {esc(why)}</p>" if verdict else ""
        return (f"<fieldset><legend>{k}. {name}</legend>{reason}{radios}"
                f"<label>다르면 사장님 판단 <input name='{k}.answer'></label></fieldset>")

    confirm = "".join(f"<li>{esc(q)}</li>" for q in rec.get("confirm") or [])
    form = (f"<div class=confirm><b>사장님 확인</b><ul>{confirm}</ul></div>" if confirm else "") + "".join(
        item(k, name, opts) for k, name, opts in ITEMS)
    return f"""<section class=case data-id="{esc(c['id'])}" data-key="C{i:02d}"><h2>{esc(head)}</h2><p class=meta>{esc(meta)}</p>
<div class=grid><div><h3>고객 원문</h3>{'<p><b>' + esc(cust.get('title')) + '</b></p>' if cust.get('title') else ''}
<div class=q>{esc(cust.get('body'))}</div></div>
<div><h3>제품이 한 것</h3><p>{state}</p>{'<h4>필요 목록</h4><ul>' + needs + '</ul>' if needs else ''}
<h3>초안</h3><div class="q draft">{esc(c['draft']) if c['draft'] else '<span class=none>초안 없음</span>'}</div>
<p class=meta>모델 {esc(c.get('model'))} · 코드 검사 {esc(', '.join(c['checks']) or '없음')}</p></div></div>
<h3>이 초안이 인용한 근거</h3><ul>{ev}</ul>
<p class=meta>가게 자료(실행 시점): 상품 지식 {m.get('product_sources')}건/{m.get('product_chunks')}조각 · 상품 사실 {m.get('product_facts')}
 · 조직 정책 {m.get('org_policies')} · 과거 답변 {m.get('past_answers')}(이 상품 {m.get('past_answers_for_product')})</p>
<form>{form}<label>판매자가 고친 곳(3단계) <input name='edited'></label></form></section>"""


PAGE = """<!doctype html><html lang=ko><meta charset=utf-8><title>사례별 평가표 v1</title>
<style>body{font:14px/1.6 -apple-system,'Pretendard',sans-serif;margin:24px auto;max-width:1100px;padding:0 16px;color:#222;background:#fbfaf7}
.case{border:1px solid #ddd;border-radius:10px;padding:16px;margin:20px 0;background:#fff}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.q{white-space:pre-wrap;background:#f5f3ee;padding:8px;border-radius:6px}.draft{background:#eef4ee}.meta{color:#666;font-size:12px}
.none{color:#a33}.rec{margin:2px 0;color:#333}.confirm{background:#fff4e5;padding:8px;border-radius:6px}
.top{background:#fff4e5;border:1px solid #f0c48a;border-radius:10px;padding:12px}fieldset{border:1px solid #eee;margin:6px 0}label{margin-right:10px}.o{font-size:13px}input[type=text],input:not([type]){width:240px}
@media(max-width:700px){.grid{grid-template-columns:1fr}}</style>
<h1>사례별 평가표 v1</h1><p>기준: tools/eval/case-eval/RUBRIC.md. 판정마다 추천과 이유를 먼저 채워 두었다(선택된 값).
추천이 틀린 곳만 바꾸고, 아래 「확인이 필요한 것」에 답한 뒤 「JSON 저장」.</p>
__CONFIRM__<p class=meta>__META__</p>__CARDS__
<button onclick="save()">JSON 저장</button>
<script>function save(){const out=[];document.querySelectorAll('.case').forEach(s=>{const a={id:s.dataset.id,key:s.dataset.key};
s.querySelectorAll('input').forEach(i=>{if(i.type==='radio'){if(i.checked)a[i.name]=i.value}else if(i.value)a[i.name]=i.value});out.push(a)});
const b=new Blob([JSON.stringify(out,null,1)],{type:'application/json'});const l=document.createElement('a');
l.href=URL.createObjectURL(b);l.download='owner-answers.json';l.click()}</script></html>"""


def main():
    global ORG
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--db", default="sellerops_eval")
    # The synthetic store is its own org (synthetic_store.py): every query here and in build_sheet reads this one.
    ap.add_argument("--org", default=build_sheet.ORG)
    a = ap.parse_args()
    ORG = build_sheet.ORG = uid(a.org)
    run_dir = os.path.abspath(a.run)
    if not run_dir.startswith(os.path.expanduser("~/workspace/sellerops/holds/")):
        sys.exit("run dir must be under ~/workspace/sellerops/holds/")
    os.umask(0o077)
    with open(os.path.join(run_dir, "runs.jsonl"), encoding="utf-8") as f:
        runs = [json.loads(line) for line in f if line.strip()]
    read = lambda name: open(os.path.join(run_dir, name), encoding="utf-8").read().strip()  # noqa: E731
    before, after, now = read("knowledge-before.txt"), read("knowledge-after.txt"), knowledge_fingerprint(a.db)
    held_still = before == after == now
    cases = []
    for run in runs:
        with open(os.path.join(run_dir, "raw", run["id"] + ".json"), encoding="utf-8") as f:
            raw = json.load(f)
        c = review_case(a.db, run) if run["kind"] == "review" else inquiry_case(a.db, run, raw)
        if run["kind"] == "review":
            c["left"] = left_questions(raw)
        c.update(id=run["id"], kind=run["kind"], set=run["set"], seconds=run.get("seconds"),
                 checks=facts(c["draft"]) if c["draft"] else [], factsCheckable=held_still)
        cases.append(c)
    recommend = {}
    if os.path.exists(os.path.join(run_dir, "recommend.json")):
        with open(os.path.join(run_dir, "recommend.json"), encoding="utf-8") as f:
            recommend = json.load(f).get("cases", {})
    for i, c in enumerate(cases):
        c["recommend"] = recommend.get(f"C{i + 1:02d}")
    asks = [f"C{i + 1:02d} · {q}" for i, c in enumerate(cases) for q in (c.get("recommend") or {}).get("confirm") or []]
    confirm_block = ("<div class=top><b>사장님 확인이 필요한 것</b><ul>" + "".join(f"<li>{esc(q)}</li>" for q in asks)
                     + "</ul></div>") if asks else ""
    calls = usage(a.db, read("started-at.txt"), read("finished-at.txt"))
    meta = (f"사례 {len(cases)} (리뷰 {sum(c['kind'] == 'review' for c in cases)} · 문의 {sum(c['kind'] == 'inquiry' for c in cases)})"
            f" · 지식 고정 {'예' if held_still else '아니오 — 4 · 4-1 · 5 보류'} · 설정: "
            + (read("config.txt").replace("\n", " / ") if os.path.exists(os.path.join(run_dir, "config.txt")) else "기록 없음"))
    with open(os.path.join(run_dir, "sheet.html"), "w", encoding="utf-8") as f:
        f.write(PAGE.replace("__CONFIRM__", confirm_block).replace("__META__", esc(meta))
                .replace("__CARDS__", "".join(card(i + 1, c) for i, c in enumerate(cases))))
    auto = [{k: c[k] for k in ("id", "kind", "set", "seconds", "checks", "factsCheckable", "work")}
            | {"channel": c["customer"].get("channel"), "did": c["did"], "needs": [{k: n[k] for k in ("type", "status")} for n in c["needs"]],
               "cited": [e.get("kind") for e in c["evidence"]], "material": c["material"], "hasDraft": bool(c["draft"])}
            for c in cases]
    with open(os.path.join(run_dir, "auto.json"), "w", encoding="utf-8") as f:
        json.dump({"knowledgeHeldStill": held_still, "usage": calls, "cases": auto}, f, ensure_ascii=False, indent=1,
                  default=str)
    slots = []
    for c in cases:
        slots += [c["customer"].get("title"), c["customer"]["body"], c["draft"]]
        for e in c["evidence"]:
            slots += [e.get("title"), e.get("text")]
        slots += c["left"]
    done = iter(redact_all(slots))
    with open(os.path.join(run_dir, "judge-input.jsonl"), "w", encoding="utf-8") as f:
        for i, c in enumerate(cases):
            item = {"id": f"C{i + 1:02d}", "kind": c["kind"], "rating": c["customer"].get("rating"),
                    "title": next(done), "customer": next(done), "reply": next(done),
                    "evidence": [{"kind": e.get("kind"), "title": next(done), "text": next(done)} for e in c["evidence"]],
                    "left": [next(done) for _ in c["left"]],
                    "received": c["customer"].get("received"),
                    "factsCheckable": held_still}
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    for c in cases:
        print(c["kind"], c["set"], c["id"][:8], c["did"].get("outcome"), "draft" if c["draft"] else "no-draft",
              "cited", len(c["evidence"]), "checks", len(c["checks"]))
    print("usage rows", len(calls), "· knowledge held still", held_still)


if __name__ == "__main__":
    main()
