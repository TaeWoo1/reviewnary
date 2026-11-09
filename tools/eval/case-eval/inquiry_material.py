#!/usr/bin/env python3
"""Every passage the store had for each inquiry of a run, and which of them the saved draft cites — the inquiry side of
ReviewRetrievalTraceIT's material.jsonl (2026-11-01 eighth review: 「자료가 없었는지, 있었는데 못 찾았는지, 찾고도 답을
잘못 썼는지」).

    inquiry_material.py --run <dir> [--db sellerops_eval] [--org <id>]

Reads <run>/runs.jsonl and the copy database read-only; writes <run>/material.jsonl, one row per inquiry case, in the
shape material_need.py reads (`--lane inquiry`): the redacted question, every active policy passage of the org and every
active knowledge passage of the inquiry's product (ids O-… · P-…), and `reachedModel` — the passages the saved version
cites, or, with no saved version, every passage of the documents the needs judge named as covering a need (labels are all
it returns). Prints ids and counts only.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "reply-agent"))
import build_sheet  # noqa: E402
from build_sheet import redact_all, rows, uid  # noqa: E402


def one(db, sql):
    found = rows(db, f"select row_to_json(t) from ({sql}) t")
    return found[0] if found else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--db", default="sellerops_eval")
    ap.add_argument("--org", default=build_sheet.ORG)
    a = ap.parse_args()
    run_dir = os.path.abspath(a.run)
    if not run_dir.startswith(os.path.expanduser("~/workspace/sellerops/holds/")):
        sys.exit("run dir must be under ~/workspace/sellerops/holds/")
    org = uid(a.org)
    runs = [json.loads(l) for l in open(os.path.join(run_dir, "runs.jsonl"), encoding="utf-8") if l.strip()]
    policies = rows(a.db, f"""select row_to_json(t) from (select 'O-' || left(c.id::text, 8) as id, 'ORG_POLICY' as kind,
        s.title, c.content as text from org_knowledge_chunks c join org_knowledge_sources s on s.id = c.source_id
        where c.org_id = '{org}' and s.active order by s.title, c.ordinal) t""")
    built = []
    for run in runs:
        if run.get("kind") != "inquiry":
            continue
        wid = uid(run["id"])
        inq = one(a.db, f"""select i.title, i.body, i.product_id from inquiry_work_item w join inquiries i on i.id = w.inquiry_id
            where w.org_id = '{org}' and w.id = '{wid}'""")
        product = []
        if inq["product_id"]:
            product = rows(a.db, f"""select row_to_json(t) from (select 'P-' || left(c.id::text, 8) as id,
                'PRODUCT_KNOWLEDGE' as kind, s.title, c.content as text from product_knowledge_chunks c
                join product_knowledge_sources s on s.id = c.source_id
                where c.org_id = '{org}' and c.product_id = '{uid(inq['product_id'])}' and s.active
                order by s.title, c.ordinal) t""")
        version = run.get("version")
        cited = []
        if isinstance(version, int):
            cited = rows(a.db, f"""select to_json(case kind when 'ORG_POLICY' then 'O-' when 'PRODUCT_KNOWLEDGE' then 'P-'
                else 'M-' end || left(chunk_id::text, 8)) from inquiry_draft_evidence where org_id = '{org}'
                and work_item_id = '{wid}' and draft_version = {version} and chunk_id is not null order by ordinal""")
        if not cited:
            # No saved version to read citations from: what the needs judge said covers each need — document labels
            # (「운영 정책 · 교환·반품 기준」), so every passage of a named document counts as reached.
            raw = json.load(open(os.path.join(run_dir, "raw", run["id"] + ".json"), encoding="utf-8")).get("body") or {}
            named = {e.split(" · ", 1)[-1] for n in ((raw.get("knowledgeGap") or {}).get("needs") or [])
                     for e in (n.get("evidence") or []) if isinstance(e, str)}
            cited = [i["id"] for i in policies + product if i["title"] in named]
        built.append({"run": run, "question": "\n".join(x for x in (inq["title"], inq["body"]) if x),
                      "items": policies + product, "cited": cited})
    slots = []
    for b in built:
        slots.append(b["question"])
        for i in b["items"]:
            slots += [i["title"], i["text"]]
    done = iter(redact_all(slots))
    os.umask(0o077)
    with open(os.path.join(run_dir, "material.jsonl"), "w", encoding="utf-8") as f:
        for b in built:
            body = next(done)
            items = [dict(i, title=next(done), text=next(done)) for i in b["items"]]
            run = b["run"]
            f.write(json.dumps({"review": run["id"][:8], "rating": None, "body": body, "items": items,
                                "reachedModel": b["cited"], "cited": b["cited"], "drafted": isinstance(run.get("version"), int),
                                "knowledgeState": run.get("knowledgeState")}, ensure_ascii=False) + "\n")
            print(run["id"][:8], "items", len(items), "cited", len(b["cited"]), "drafted", isinstance(run.get("version"), int))


if __name__ == "__main__":
    main()
