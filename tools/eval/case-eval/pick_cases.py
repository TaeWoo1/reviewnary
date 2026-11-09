#!/usr/bin/env python3
"""Pick the small evaluation's cases by structure only — rating, length, product link, phase — never by reading text.

    pick_cases.py --out <dir> [--db sellerops_eval] [--exclude <file> ...]

Writes <dir>/cases.txt (`kind|set|id`, kind = review | inquiry, id = review id or inquiry work item id) and
<dir>/selection.json (why each case was picked, in structural terms only). Ids named in any --exclude file (earlier
rounds' case lists and run logs) are never picked again, so this round's cases are ones no rule was tuned on.
The choice is stable: the same database and exclusions pick the same cases.
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "reply-agent"))
from build_sheet import ORG, rows  # noqa: E402  (the read-only psql helper and the demo org)

SALT = "case-eval-v1"

# Reviews: one per shape. `want` is what the shape is meant to exercise; the owner's answer decides what was right.
REVIEW_SHAPES = [
    ("R1", "★1", "r.rating = 1"),
    ("R2", "★2", "r.rating = 2"),
    ("R3", "★3 · 긴 글", "r.rating = 3 and length(r.body) >= 100"),
    ("R4", "★4 · 보통 길이", "r.rating = 4 and length(r.body) between 40 and 160"),
    ("R5", "★5 · 짧은 글", "r.rating = 5 and length(r.body) < 40"),
]


def excluded_ids(paths):
    found = set()
    for p in paths:
        try:
            with open(p, encoding="utf-8") as f:
                found |= set(re.findall(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", f.read()))
        except OSError:
            sys.exit(f"cannot read exclude file {p}")
    return found


def pick_review(db, where, skip, with_knowledge, reuse=False):
    knowledge = ("exists" if with_knowledge else "not exists") + \
        " (select 1 from product_knowledge_sources s where s.product_id = r.product_id)"
    found = rows(db, f"""select row_to_json(t) from (select r.id, r.rating, length(r.body) as len, r.media_count,
        r.product_id is not null as has_product
        from reviews r join channels c on c.id = r.channel_id
        where r.org_id = '{ORG}' and c.code = 'NAVER' and r.data_origin = 'REAL' and r.reply_state in ('PENDING', 'UNKNOWN')
          and r.seller_reply_body is null
          and not exists (select 1 from review_reply_approval a where a.review_id = r.id)
          and r.product_id is not null and {where} and {knowledge}
        order by md5(r.id::text || '{SALT}') limit 40) t""")
    for r in found:
        if reuse or r["id"] not in skip:
            r["productHasKnowledge"] = with_knowledge
            r["seenBefore"] = r["id"] in skip
            return r
    return None


def pick_inquiries(db, skip, bound, n):
    cond = "i.product_id is not null" if bound else "i.product_id is null"
    found = rows(db, f"""select row_to_json(t) from (select w.id, c.code as channel, w.phase, length(i.body) as len,
        i.product_id is not null as has_product,
        exists (select 1 from product_knowledge_sources s where s.product_id = i.product_id) as product_has_knowledge
        from inquiry_work_item w join inquiries i on i.id = w.inquiry_id join channels c on c.id = w.channel_id
        where w.org_id = '{ORG}' and w.phase = 'PROPOSED' and i.data_origin = 'REAL' and {cond}
        order by md5(w.id::text || '{SALT}')) t""")
    return [r for r in found if r["id"] not in skip][:n]


def pick_inquiry_round(db, skip, n):
    """An inquiry round (2026-11-01 eighth review): every unused real inquiry a draft can be asked for — proposed, or not
    yet opened (the run proposes first) — spread over the product's own proposal categories, by structure only."""
    found = rows(db, f"""select row_to_json(t) from (select w.id, c.code as channel, w.phase, i.source_subtype as work,
        length(i.body) as len, i.product_id is not null as has_product,
        exists (select 1 from product_knowledge_sources s where s.product_id = i.product_id) as product_has_knowledge,
        (select p.summary_category from inquiry_proposal p where p.work_item_id = w.id order by p.created_at desc limit 1)
            as category
        from inquiry_work_item w join inquiries i on i.id = w.inquiry_id join channels c on c.id = w.channel_id
        where w.org_id = '{ORG}' and w.phase in ('PROPOSED', 'OPEN') and i.data_origin = 'REAL'
        order by md5(w.id::text || '{SALT}')) t""")
    fresh = [r for r in found if r["id"] not in skip]
    # Round-robin over categories so one kind of question cannot fill the round.
    by = {}
    for r in fresh:
        by.setdefault(r["category"] or "-", []).append(r)
    picked = []
    while len(picked) < n and any(by.values()):
        for k in sorted(by):
            if by[k] and len(picked) < n:
                picked.append(by[k].pop(0))
    return picked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", default="sellerops_eval")
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("--inquiries", type=int, default=0, help="an inquiry round of this many cases instead")
    a = ap.parse_args()
    skip = excluded_ids(a.exclude)
    picked = []
    if a.inquiries:
        for r in pick_inquiry_round(a.db, skip, a.inquiries):
            picked.append(dict(kind="inquiry", set="s1", shape="Q", label=r["category"] or "제안 전", **r))
        write(a, skip, picked)
        return
    for i, (key, label, where) in enumerate(REVIEW_SHAPES):
        # Every other shape from a product that has product knowledge, so 4-1 sees both sides.
        r = pick_review(a.db, where, skip, with_knowledge=(i % 2 == 0)) or pick_review(a.db, where, skip, i % 2 != 0)
        # Few low-rated reviews exist and earlier rounds tuned rules on most of them: reuse one rather than drop the
        # shape, and mark it `dev` so its score is never reported with the cases nothing was tuned on (RUBRIC rule 5).
        r = r or pick_review(a.db, where, skip, i % 2 == 0, reuse=True) or pick_review(a.db, where, skip, i % 2 != 0, True)
        if r is None:
            print(f"{key}: no review of shape {label!r}", file=sys.stderr)
            continue
        picked.append(dict(kind="review", set="dev" if r["seenBefore"] else "s1", shape=key, label=label, **r))
    for bound, n in ((True, 3), (False, 2)):
        for r in pick_inquiries(a.db, skip, bound, n):
            picked.append(dict(kind="inquiry", set="s1", shape="Q", label="상품 연결됨" if bound else "상품 없음", **r))
    write(a, skip, picked)


def write(a, skip, picked):
    os.makedirs(a.out, exist_ok=True)
    os.umask(0o077)
    with open(os.path.join(a.out, "cases.txt"), "w", encoding="utf-8") as f:
        for p in picked:
            # An inquiry nobody opened yet is proposed first by run_cases.sh, as the seller's first look does.
            extra = "|propose" if p.get("phase") == "OPEN" else ""
            f.write(f"{p['kind']}|{p['set']}|{p['id']}{extra}\n")
    with open(os.path.join(a.out, "selection.json"), "w", encoding="utf-8") as f:
        json.dump({"salt": SALT, "db": a.db, "excludedIds": len(skip), "cases": picked}, f, ensure_ascii=False, indent=1)
    for p in picked:
        print(p["kind"], p["set"], p["shape"], p["label"], p["id"], f"len={p['len']}",
              "knowledge" if p.get("productHasKnowledge") or p.get("product_has_knowledge") else "-")


if __name__ == "__main__":
    main()
