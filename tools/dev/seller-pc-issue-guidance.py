#!/usr/bin/env python3
"""File proposed answers to repeated review problems into a seller-PC demo's 확인 필요 (2026-10-27).

The proposals are written by hand from the store's real reviews and live in tools/dev/.run/issue-guidance.json, which is
gitignored like everything else read from the store. Each one names the products it is about (by channel product
number), a pattern that finds the reviews reporting the problem, and the proposed sentence, with 【확인: …】 blanks where
only the owner knows the fact. This script counts the reviews per product, and files one proposal per product that has
at least MIN_REVIEWS of them — or one for the whole company when it names no product — through the same endpoint a
future model-written producer will use. Nothing becomes knowledge here: the owner accepts, edits or declines each one,
and a proposal with a blank left in it cannot be accepted.

Run against the demo or the judging copy:  SELLER_PC_DEMO=judge tools/dev/seller-pc-issue-guidance.py
Prints counts only.
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PSQL = "/opt/homebrew/opt/postgresql@15/bin/psql"
TARGETS = {"demo": (18190, "sellerpc_demo"), "judge": (18690, "sellerpc_judge")}
MIN_REVIEWS = 2


def seed_password():
    src = (ROOT / "backend/src/main/java/com/sellerops/config/MockDataSeeder.java").read_text()
    m = re.search(r'setEmail\("demo@sellerops\.ai"\);\s*user\.setPasswordHash\(passwordEncoder\.encode\("([^"]+)"\)\)', src)
    if not m:
        raise SystemExit("could not find the seed account in MockDataSeeder")
    return m.group(1)


def call(port, method, path, body=None, token=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read()
        return json.loads(raw) if raw else None


def sql_rows(db, query):
    out = subprocess.run([PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "sellerops", "-tA", "-F", "\x1f", "-R", "\x1e",
                          "-d", db, "-c", query], check=True, capture_output=True, text=True).stdout
    rows = [r.split("\x1f") for r in out.split("\x1e") if r.strip()]
    return [[*r[:-1], r[-1].rstrip("\n")] for r in rows]


def reports(entry, body):
    """The review reports this problem: it names it, says something is wrong, and is not only praise for it."""
    if not re.search(entry["match"], body) or not re.search(entry["complaint"], body):
        return False
    return not (entry.get("praise_only") and re.search(entry["praise_only"], body)
                and not re.search(r"(아쉽|아쉬|약해|약하|별로|불편|ㅠ)", body))


def main():
    name = os.environ.get("SELLER_PC_DEMO", "demo")
    if name not in TARGETS:
        raise SystemExit("SELLER_PC_DEMO is demo or judge")
    port, db = TARGETS[name]
    guidance = json.loads((ROOT / "tools/dev/.run/issue-guidance.json").read_text())["guidance"]
    token = call(port, "POST", "/api/auth/login", {"email": "demo@sellerops.ai", "password": seed_password()})["token"]
    linked = call(port, "POST", "/api/products/review-link/reconcile", {}, token=token)
    print(f"{name}: reviews linked {linked['linked']} · products made {linked['derived']} · still unlinked {linked['unlinked']}")

    reviews = sql_rows(db, "select coalesce(cast(r.product_id as varchar), ''), coalesce(r.source_product_ref, ''), r.body "
                           "from reviews r where length(trim(r.body)) > 0")
    suggestions = []
    for entry in guidance:
        counts = {}
        for product_id, ref, body in reviews:
            if entry["refs"] and ref not in entry["refs"]:
                continue
            if reports(entry, body):
                key = product_id if entry["refs"] else ""
                counts[key] = counts.get(key, 0) + 1
        for product_id, n in sorted(counts.items(), key=lambda kv: -kv[1]):
            if n < MIN_REVIEWS or (entry["refs"] and not product_id):
                continue
            suggestions.append({"productId": product_id or None, "subject": entry["subject"],
                                "content": entry["content"], "evidenceCount": n})
        print(f"  {entry['subject']}: products {sum(1 for k, n in counts.items() if n >= MIN_REVIEWS and (k or not entry['refs']))}"
              f" · reviews {sum(counts.values())}")
    open_now = call(port, "POST", "/api/knowledge/candidates/guidance", suggestions, token=token)
    print(f"filed {len(suggestions)} · open in 확인 필요 {sum(1 for c in open_now if c['origin'] == 'ISSUE_GUIDANCE')}")


if __name__ == "__main__":
    sys.exit(main())
