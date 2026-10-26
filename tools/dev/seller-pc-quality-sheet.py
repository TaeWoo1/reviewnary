#!/usr/bin/env python3
"""The owner's judging sheet: real reviews, what the PC said of each, and the reply it drafted.

Runs against the judging copy only (`SELLER_PC_DEMO=judge tools/dev/seller-pc-demo.sh up` — a copy of the kept demo's
PC database, no vendor server, no pull), never against the kept demo: making a draft marks the review 「대응 필요」 and
writes a draft version, which the kept demo must not grow from a judging session. It refuses a PC whose vendor link
is anything but off.

    python3 -I tools/dev/seller-pc-quality-sheet.py [rows]     # 50 by default

Draws a fixed sample (the same reviews on every run) spread over the three groups and the safety marks, reads each the
way the review screen does, asks the draft model for a reply to the unanswered ones — one model call each, as
「AI 초안 준비」 does — and writes tools/dev/.run/seller-pc-judge/판정시트.xlsx (gitignored) for the owner to mark
맞다 / 고칠 것 / 틀림. Prints counts only: the review text and the drafts are in the sheet and nowhere else.
"""
import json
import random
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/dev/.run/seller-pc-judge"
PC = "http://127.0.0.1:18690"
SCREEN = "http://127.0.0.1:6091"
DB = "sellerpc_judge"
PSQL = "/opt/homebrew/opt/postgresql@15/bin/psql"
SEED = 20261027

TIER = {"NEEDS_ATTENTION": "확인 필요", "WATCH": "지켜보기", "FYI": "참고"}
SAFETY_KIND = {"INJURY": "다침", "BODY_REACTION": "몸 반응", "PRODUCT_HAZARD": "제품 위험", "LEGAL": "법적 문제"}
SAFETY_READING = {"OCCURRENCE": "발생", "WORRY": "우려"}
BASIS = {"GROUNDED": "우리 정보 근거", "PAST_ANSWER": "지난 답변 참고", "THANKS_ONLY": "감사 인사만",
         "NO_ANSWER_BASIS": "근거 없음"}
VERDICTS = '"맞다,고칠 것,틀림"'


def say(line):
    print(line, flush=True)


def seed_password():
    src = (ROOT / "backend/src/main/java/com/sellerops/config/MockDataSeeder.java").read_text()
    m = re.search(r'setEmail\("demo@sellerops\.ai"\);\s*user\.setPasswordHash\(passwordEncoder\.encode\("([^"]+)"\)\)', src)
    if not m:
        raise SystemExit("could not find the seed account in MockDataSeeder")
    return m.group(1)


def call(method, path, body=None, token=None, timeout=90):
    req = urllib.request.Request(PC + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as res:
        raw = res.read()
        return json.loads(raw) if raw else None


def sql_rows(query):
    out = subprocess.run([PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "sellerops", "-tA", "-F", "\x1f", "-R", "\x1e",
                          "-d", DB, "-c", query], check=True, capture_output=True, text=True).stdout
    rows = [r.split("\x1f") for r in out.split("\x1e") if r.strip()]
    # psql ends its output with a newline, which lands in the last row's last field.
    return [[*r[:-1], r[-1].rstrip("\n")] for r in rows]


def listed(token, **params):
    """Every row of the record under these filters, as the review screen pages through it."""
    rows, page = [], 0
    while True:
        q = "&".join(f"{k}={v}" for k, v in {**params, "page": page, "size": 100}.items())
        view = call("GET", f"/api/reviews/record?{q}", token=token)
        rows += view["items"]
        if (page + 1) * view["size"] >= view["total"] or not view["items"]:
            return rows
        page += 1


def main():
    want = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    token = call("POST", "/api/auth/login", {"email": "demo@sellerops.ai", "password": seed_password()})["token"]
    link = call("GET", "/api/local/vendor-link", token=token)
    if link.get("state") != "OFF":
        raise SystemExit(f"refusing: this PC's vendor link is {link.get('state')}, not OFF — run it on the judging copy")

    groups = {tier: [r for r in listed(token, sort="attention", tier=tier) if (r["review"].get("preview") or "").strip()]
              for tier in TIER}
    safety = [r for r in listed(token, sort="attention", safety="true") if r.get("safety")]
    say("held: " + " · ".join(f"{TIER[t]} {len(rows)}" for t, rows in groups.items()) + f" · 안전 표시 {len(safety)}")

    # Every 확인 필요 and every safety mark (they are few), then 지켜보기, then 참고. The rule never files a 1–3★ review
    # under 참고, so what to look for there is a 4–5★ review whose words complain.
    rng = random.Random(SEED)
    picked, seen = [], set()

    def take(rows, n):
        pool = sorted((r for r in rows if r["review"]["id"] not in seen), key=lambda r: r["review"]["id"])
        for r in rng.sample(pool, max(0, min(n, len(pool)))):
            seen.add(r["review"]["id"])
            picked.append(r)

    take(safety, len(safety))
    take(groups["NEEDS_ATTENTION"], round(want * 0.3))
    take(groups["WATCH"], round(want * 0.3))
    take(groups["FYI"], want - len(picked))
    say(f"sample: {len(picked)}")
    picked.sort(key=lambda r: (list(TIER).index(r["review"]["triage"]["tier"]), r["review"]["id"]))

    ids = ",".join(f"'{r['review']['id']}'" for r in picked)
    facts = {row[0]: row[1:] for row in sql_rows(
        "select r.id, r.body, coalesce(r.source_product_name, ''), r.reply_state, coalesce(r.seller_reply_body, ''), "
        f"(select s.id from seller_accounts s where s.org_id = r.org_id and s.channel_id = r.channel_id limit 1) "
        f"from reviews r where r.id in ({ids})")}

    # A review that already has a model's draft keeps it: re-running costs no model call for it and the owner's sheet
    # does not change under them. Only the rest are asked for.
    saved = {row[0]: row[1:] for row in sql_rows(
        "select distinct on (review_id) review_id, body, coalesce(answer_basis, '') from review_reply_draft "
        f"where author_kind = 'MODEL' and review_id in ({ids}) order by review_id, version desc")}

    made, kept, failed, answered = 0, 0, 0, 0
    authors, reasons = {}, {}
    sheet = []
    for n, row in enumerate(picked, 1):
        review = row["review"]
        body, product, reply_state, seller_reply, account = facts[review["id"]]
        draft, basis, note = "", "", ""
        if reply_state == "ANSWERED":
            answered += 1
            note = "이미 답한 리뷰 — 오른쪽은 실제로 단 답변"
            draft = seller_reply
        elif review["id"] in saved:
            draft, saved_basis = saved[review["id"]]
            basis = BASIS.get(saved_basis, saved_basis)
            kept += 1
        else:
            try:
                call("POST", f"/api/reviews/{review['id']}/decision",
                     {"commandId": str(uuid.uuid4()), "disposition": "RESPONSE_NEEDED"}, token=token)
                t0 = time.time()
                view = call("POST", f"/api/seller-accounts/{account}/attention/items/review:{review['id']}"
                            "/reply/draft/generate", {}, token=token)
                draft = view["draft"]["body"]
                authors[view.get("authorKind")] = authors.get(view.get("authorKind"), 0) + 1
                basis = BASIS.get(view.get("answerBasis"), view.get("answerBasis") or "")
                if view.get("authorKind") and view["authorKind"] != "MODEL":
                    basis += f" · {view['authorKind']}"
                note = f"{time.time() - t0:.1f}초"
                if view.get("unavailableMessage"):
                    # Why the model's reply was not kept and the template was saved instead — closed words, no text.
                    note += f" · {view['unavailableMessage']}"
                    basis = "AI 초안 아님 — 저장된 문구"
                    reasons[view["unavailableMessage"]] = reasons.get(view["unavailableMessage"], 0) + 1
                made += 1
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
                failed += 1
                note = f"초안을 만들지 못함 ({getattr(e, 'code', type(e).__name__)})"
        mark = row.get("safety")
        sheet.append({
            "n": n, "link": f"{SCREEN}/reviews/reply/{review['id']}", "rating": review.get("rating"),
            "product": product or review.get("productName") or "", "body": body,
            "tier": TIER.get(review["triage"]["tier"], review["triage"]["tier"]),
            "reason": review["triage"].get("reason") or "", "tags": ", ".join(review["triage"].get("tags") or []),
            "safety": f"{SAFETY_KIND.get(mark['kind'], mark['kind'])} {SAFETY_READING.get(mark['reading'], '')}"
                      + (f" ({mark['term']})" if mark.get("term") else "") if mark else "",
            "draft": draft, "basis": basis, "note": note,
        })
        say(f"  {n}/{len(picked)}")

    write(sheet)
    say(f"rows {len(sheet)} · drafts made {made} · kept {kept} · failed {failed} · already answered {answered} · written by {authors} · template because {reasons}")
    say(f"sheet: {RUN / '판정시트.xlsx'}")


def write(rows):
    wb = Workbook()
    ws = wb.active
    ws.title = "판정"
    head = ["번호", "화면에서 보기", "별점", "상품", "리뷰", "시스템 묶음", "묶은 이유", "태그", "안전 표시",
            "분류 판정", "분류 — 고칠 점", "AI 초안", "초안 근거", "초안 판정", "초안 — 고칠 점", "참고"]
    ws.append(head)
    for r in rows:
        ws.append([r["n"], "열기", r["rating"], r["product"], r["body"], r["tier"], r["reason"], r["tags"], r["safety"],
                   "", "", r["draft"], r["basis"], "", "", r["note"]])
        ws.cell(row=ws.max_row, column=2).hyperlink = r["link"]
        ws.cell(row=ws.max_row, column=2).font = Font(color="1F4FBF", underline="single")
    widths = [6, 9, 6, 18, 60, 11, 24, 14, 14, 11, 28, 60, 14, 11, 28, 18]
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[ws.cell(row=1, column=i).column_letter].width = w
    for cell in ws[1]:
        cell.font = Font(bold=True)
        cell.fill = PatternFill("solid", fgColor="EEF1F5")
    for row in ws.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
    for col in ("J", "N"):
        dv = DataValidation(type="list", formula1=VERDICTS, allow_blank=True)
        ws.add_data_validation(dv)
        dv.add(f"{col}2:{col}{ws.max_row}")
        for row in range(2, ws.max_row + 1):
            ws[f"{col}{row}"].fill = PatternFill("solid", fgColor="FFF8E1")
    ws.freeze_panes = "F2"

    guide = wb.create_sheet("읽는 법")
    for line in [
        "한 줄 = 리뷰 하나. 노란 칸 두 개만 고르시면 됩니다: 맞다 / 고칠 것 / 틀림.",
        "",
        "분류 판정 — 「시스템 묶음 · 묶은 이유 · 안전 표시」가 이 리뷰에 맞는가.",
        "  맞다: 그대로 좋다.   고칠 것: 묶음은 맞는데 이유나 표시가 어색하다.   틀림: 다른 묶음이어야 한다.",
        "  틀림이면 「분류 — 고칠 점」에 어느 묶음이어야 하는지 한마디만 (예: 확인 필요여야 함).",
        "",
        "초안 판정 — 이 답변을 고객에게 그대로 보내도 되는가.",
        "  맞다: 그대로 보내도 된다.   고칠 것: 조금 손보면 된다.   틀림: 다시 써야 한다.",
        "  고칠 것 · 틀림이면 「초안 — 고칠 점」에 무엇이 문제인지 한마디만 (예: 교환 방법을 안 알려 줌).",
        "",
        "이미 답한 리뷰는 「AI 초안」 칸에 실제로 다신 답변이 있습니다 — 초안 판정은 비워 두셔도 됩니다.",
        "「초안 근거」가 「AI 초안 아님」인 줄은 모델 서비스가 그때 요청을 받지 않아 저장된 문구가 들어간 것입니다 —"
        " 초안 판정은 비워 두세요. 다시 돌리면 그 줄만 AI 초안으로 채워집니다(나머지는 그대로).",
        "「화면에서 보기」는 판정용 복사본 PC 화면을 엽니다(평소 PC와 같은 계정). 원래 데모에는 아무것도 바뀌지 않습니다.",
        "",
        "이 파일에는 실제 리뷰 원문이 들어 있습니다. 이 PC 밖으로 보내지 마세요.",
    ]:
        guide.append([line])
    guide.column_dimensions["A"].width = 110

    RUN.mkdir(parents=True, exist_ok=True)
    path = RUN / "판정시트.xlsx"
    wb.save(path)
    path.chmod(0o600)


if __name__ == "__main__":
    main()
