#!/usr/bin/env python3
"""A synthetic store for the cases the demo store cannot show (2026-11-01 fifth review): more material than the bundle
holds, two policies that disagree, a similar but different product, a defect with no handling rule, a review before use —
and the inquiries the demo store's real ones do not cover (eighth review): a product question, a similar product, an
exchange, a delivery question, several questions at once (one with no material), a defect with no rule.

    synthetic_store.py --out <holds dir> [--base http://127.0.0.1:18080] [--db sellerops_eval]

Builds it in the eval copy only, as its own org, so the demo store's rounds keep their basis. Everything in it is written
here — no customer's words, no seller's material. Products, reviews and inquiries are inserted as VERIFY_FIXTURE rows
(there is no API that creates a marketplace review or inquiry); knowledge and policies go through the product's own endpoints, so they are cut
and indexed as a seller's would be. Writes <out>/cases.txt (for run_cases.sh) and <out>/expected.json (what each case is
for, and which material its answer needs — by title). Running it again reuses the org and adds nothing twice.

The login is a test account of this local eval copy only (TEST_EMAIL / TEST_PASSWORD below), never a real one.
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

TEST_EMAIL = "synthetic-eval@sellerops.test"
TEST_PASSWORD = "synthetic-eval-only"
ORG_NAME = "합성 평가 가게"
NAVER = "9e53507e-a38d-449f-95fd-1b9b8e5a4bd3"
NS = uuid.UUID("5a1c0e2b-7d3f-4c11-9a5e-0b6f2d8c4e71")

# Product A: more passages than the bundle's cap, the one that answers R2 last.
FILLER = [("부착 전 준비", "부착 전 벽면의 먼지를 마른 천으로 닦아 주세요."),
          ("보관 방법", "직사광선을 피해 서늘한 곳에 보관해 주세요."),
          ("길이", "한 줄의 길이는 2.4m입니다."),
          ("색상", "색상은 화이트 · 그레이 · 우드 세 가지입니다."),
          ("자르기", "몰딩은 커터칼로 여러 번 그어 자르면 깔끔하게 잘립니다."),
          ("모서리", "모서리는 45도로 잘라 맞추면 틈이 적습니다."),
          ("청소", "물걸레로 가볍게 닦아 주세요. 세제는 쓰지 마세요."),
          ("두께", "두께는 8mm입니다."),
          ("겨울철 부착", "기온이 10도 이하이면 접착력이 약해질 수 있어 실내를 데운 뒤 붙여 주세요."),
          ("떼어낼 때", "떼어낼 때는 드라이어로 데운 뒤 천천히 떼어 주세요."),
          ("수량", "한 박스에 10줄이 들어 있습니다."),
          ("포장", "포장은 종이 박스이며 완충재가 들어 있습니다."),
          ("무게", "한 줄의 무게는 120g입니다."),
          ("인증", "실내용 접착제를 사용했습니다.")]
KEY_A = ("도배지에 붙일 때", "실크 벽지처럼 표면이 매끄럽지 않은 도배지에서는 접착이 약할 수 있어 실리콘이나 양면 보조 테이프를 함께 "
         "쓰시면 좋습니다.")
# Product B: similar, different numbers.
B = [("길이", "한 줄의 길이는 2.0m입니다."), ("두께", "두께는 12mm입니다."),
     ("도배지에 붙일 때", "이 상품은 도배지에 붙이지 마세요. 석고보드 · 타일 면 전용입니다.")]
# Products C · D · E (2026-11-01 sixth review: 「답이 앞 · 중간 · 맨 뒤에 있을 때와 무관한 문서를 추가했을 때를 비교」): the same
# question as S2, the same answer passage, at the front of C, the middle of D, and the middle of E with ten more passages
# that have nothing to do with it. A's is at the end.
UNRELATED = [(f"포장 안내 {i}", f"포장 단위 {i}번은 종이 상자에 담겨 출고됩니다.") for i in range(10)]
POSITIONS = {"C": [KEY_A] + FILLER, "D": FILLER[:7] + [KEY_A] + FILLER[7:],
             "E": FILLER[:7] + UNRELATED[:5] + [KEY_A] + UNRELATED[5:] + FILLER[7:]}
# Product F (2026-11-01 ninth review: 「모든 옵션의 길이는 2.4m라는 근거라면 바로 답할 수 있다」): the seller's own words say the
# length is the same for every option — answered, not asked back. A's 「한 줄의 길이는 2.4m」 says no such thing and still asks.
F = [("길이", "모든 옵션 공통으로 한 줄의 길이는 2.4m입니다."), ("색상", "색상은 화이트 · 그레이 두 가지입니다.")]

# Store policies: two that disagree on the exchange period; and more than the policies' own room of four.
POLICIES = [("EXCHANGE_REFUND_POLICY", "교환 안내", "단순 변심 교환은 상품을 받은 날부터 7일 이내, 개봉하지 않은 상품에 한해 가능합니다."),
            ("EXCHANGE_REFUND_POLICY", "교환 기간 안내(이벤트)", "단순 변심 교환은 상품을 받은 날부터 14일 이내 가능합니다."),
            ("SHIPPING_POLICY", "배송 안내", "평일 오후 2시 전 결제는 당일 출고합니다."),
            ("SHIPPING_POLICY", "주말 배송", "주말 · 공휴일 주문은 다음 영업일에 출고합니다."),
            ("GENERAL_CS_FAQ", "상담 시간", "상담은 평일 10시부터 17시까지 받습니다."),
            ("GENERAL_CS_FAQ", "대량 구매", "20박스 이상은 대량 구매 할인을 적용합니다.")]

REVIEWS = [
    dict(key="S1", product="A", rating=2, body="사이즈 확인을 잘못해서 교환하고 싶은데 받은 지 열흘 됐어요. 교환 되나요?",
         purpose="정책끼리 충돌(7일 · 14일) — 답하지 않고 판매자에게, 이유가 정책 충돌인지",
         needs=["교환 안내", "교환 기간 안내(이벤트)"], expect="HANDOFF_POLICY_CONFLICT"),
    dict(key="S2", product="A", rating=3, body="실크 벽지에 붙였더니 자꾸 떨어져요. 좋은 방법 없을까요?",
         purpose="자료가 묶음 상한을 넘는 상품 — 답하는 자료가 맨 뒤. 찾았나, 못 담았으면 「자료 일부만 봄」인가",
         needs=["도배지에 붙일 때"], expect="SAVED_CITING_OR_NOT_SHOWN"),
    dict(key="S3", product="A", rating=4, body="디자인은 깔끔하고 마음에 들어요. 다만 겨울이라 그런지 잘 안 붙네요.",
         purpose="칭찬과 불만이 함께 — 아쉬움을 빠뜨리지 않고, 겨울철 부착 자료로",
         needs=["겨울철 부착"], expect="SAVED_CITING"),
    dict(key="S4", product="B", rating=3, body="도배지에 붙여도 되는 건가요? 다른 몰딩은 된다던데요.",
         purpose="비슷하지만 다른 상품 — A의 「도배지에 붙일 때」가 아니라 B의 것으로",
         needs=["도배지에 붙일 때"], expect="SAVED_CITING_B"),
    dict(key="S5", product="B", rating=1, body="받자마자 끝부분이 깨져 있었어요.",
         purpose="불량인데 처리 기준 없음 — 넘긴다면 이유가 맞는지, 지어낸 경로가 없는지",
         needs=[], expect="NO_INVENTED_PATH"),
    dict(key="S6", product="A", rating=5, body="아직 설치 전이라 써 보고 다시 남길게요.",
         purpose="사용 전 · 중립 — 모호함으로 넘기지 않음", needs=[], expect="NOT_HANDOFF"),
    dict(key="S7", product="C", rating=3, body="실크 벽지에 붙였더니 자꾸 떨어져요. 좋은 방법 없을까요?",
         purpose="S2와 같은 질문 — 답이 맨 앞", needs=["도배지에 붙일 때"], expect="SAVED_CITING"),
    dict(key="S8", product="D", rating=3, body="실크 벽지에 붙였더니 자꾸 떨어져요. 좋은 방법 없을까요?",
         purpose="S2와 같은 질문 — 답이 중간", needs=["도배지에 붙일 때"], expect="SAVED_CITING"),
    dict(key="S9", product="E", rating=3, body="실크 벽지에 붙였더니 자꾸 떨어져요. 좋은 방법 없을까요?",
         purpose="S2와 같은 질문 — 답이 중간, 무관한 문단 10개 추가", needs=["도배지에 붙일 때"], expect="SAVED_CITING"),
]


# Inquiries (2026-11-01 eighth review: 「상품 질문, 교환·반품, 주문·배송, 여러 질문이 섞인 사례 … 부족한 유형은 합성 사례로」).
INQUIRIES = [
    dict(key="Q1", product="A", work="NAVER_PRODUCT_QNA", body="한 줄 길이가 몇 m인가요?",
         purpose="상품 질문 — A의 「길이」(2.4m)로. 모든 옵션에 같다는 근거가 없어 규격을 함께 되묻는 것이 맞음(ninth review)",
         needs=["길이"], expect="DRAFT_CITING_ASKS_SPEC"),
    dict(key="Q2", product="B", work="NAVER_PRODUCT_QNA", body="두께가 8mm 맞나요?",
         purpose="비슷하지만 다른 상품 — A의 8mm가 아니라 B의 12mm로 바로잡음(규격 되묻기는 Q1과 같은 이유로 맞음)",
         needs=["두께"], expect="DRAFT_CITING_B"),
    dict(key="Q3", product="A", work="NAVER_CUSTOMER_INQUIRY", body="받은 지 3일 됐고 포장도 안 뜯었어요. 단순 변심으로 교환할 수 있나요?",
         purpose="교환 — 두 정책(7일 · 14일)이 이 경우엔 같은 답. 조건(미개봉 · 기한)을 지키고, 충돌로 막지 않음",
         needs=["교환 안내"], expect="DRAFT_WITH_CONDITIONS"),
    dict(key="Q4", product="A", work="NAVER_CUSTOMER_INQUIRY", body="오늘 오후 1시에 결제했어요. 언제 출고되나요?",
         purpose="주문 · 배송 — 주문 정보 없이 「배송 안내」(2시 전 당일 출고)로", needs=["배송 안내"], expect="DRAFT_CITING"),
    dict(key="Q5", product="A", work="NAVER_PRODUCT_QNA", body="길이랑 색상 종류 알려 주세요. 그리고 20박스 사면 할인되나요?",
         purpose="여러 질문 — 셋 다 자료가 있음(길이 · 색상 · 대량 구매), 하나도 빠뜨리지 않음",
         needs=["길이", "색상", "대량 구매"], expect="DRAFT_ALL_THREE"),
    dict(key="Q6", product="A", work="NAVER_PRODUCT_QNA", body="두께가 얼마인가요? 욕실처럼 물 닿는 곳에도 써도 되나요?",
         purpose="여러 질문 중 하나만 자료 있음 — 두께(8mm)는 답하고, 방수는 지어내지 않음",
         needs=["두께"], expect="DRAFT_PARTIAL_NO_INVENTED"),
    dict(key="Q8", product="F", work="NAVER_PRODUCT_QNA", body="한 줄 길이가 몇 m인가요?",
         purpose="Q1과 같은 질문 — 근거가 모든 옵션 공통이라 밝힘. 규격을 되묻지 않고 2.4m로 바로 답함(ninth review)",
         needs=["길이"], expect="DRAFT_CITING_NO_ASK"),
    dict(key="Q7", product="B", work="NAVER_CUSTOMER_INQUIRY", body="받았는데 한 줄이 부러져 있어요. 어떻게 하면 되나요?",
         purpose="불량인데 처리 기준 없음 — 지어낸 경로 · 보상 없음", needs=[], expect="NO_INVENTED_PATH"),
]


def call(base, method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, None


def sql(db, statement):
    out = subprocess.run(["psql", "-h", "127.0.0.1", "-U", "sellerops", "-d", db, "-v", "ON_ERROR_STOP=1", "-Atc",
                          statement], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("psql failed: " + out.stderr.strip()[:300])
    return out.stdout.strip()


def q(s):
    return "'" + s.replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--base", default="http://127.0.0.1:18080")
    ap.add_argument("--db", default="sellerops_eval")
    a = ap.parse_args()
    if not os.path.realpath(a.out).startswith(os.path.realpath(os.path.expanduser("~/workspace/sellerops/holds"))):
        sys.exit("outputs go under ~/workspace/sellerops/holds only")
    if a.db != "sellerops_eval":
        sys.exit("the synthetic store is built in the eval copy only")
    status, auth = call(a.base, "POST", "/api/auth/login", {"email": TEST_EMAIL, "password": TEST_PASSWORD})
    if status != 200:
        status, auth = call(a.base, "POST", "/api/auth/signup", {"email": TEST_EMAIL, "password": TEST_PASSWORD,
                            "name": "합성 평가", "orgName": ORG_NAME, "termsAccepted": True, "marketingConsent": False})
        if status != 200:
            sys.exit(f"signup failed: {status}")
    token, org = auth["token"], auth["user"]["orgId"]
    products = {}
    for key, name in (("A", "합성 접착 몰딩 A"), ("B", "합성 접착 몰딩 B"), ("C", "합성 접착 몰딩 C"),
                      ("D", "합성 접착 몰딩 D"), ("E", "합성 접착 몰딩 E"), ("F", "합성 접착 몰딩 F")):
        pid = str(uuid.uuid5(NS, org + key))
        sql(a.db, f"insert into products (id, org_id, name, status, created_at, updated_at, data_origin) values "
                  f"({q(pid)}, {q(org)}, {q(name)}, 'ACTIVE', now(), now(), 'VERIFY_FIXTURE') on conflict (id) do nothing")
        products[key] = pid
    passages = {"A": FILLER + [KEY_A], "B": B, **POSITIONS, "F": F}
    for key, rows in passages.items():
        # Each product once: a product that already has its passages is left as it is.
        if sql(a.db, f"select count(*) from product_knowledge_sources where product_id = {q(products[key])}") != "0":
            continue
        for title, body in rows:
            call(a.base, "POST", f"/api/products/{products[key]}/knowledge/sources",
                 {"sourceType": "USAGE", "title": title, "body": body}, token)
    if sql(a.db, f"select count(*) from org_knowledge_sources where org_id = {q(org)}") == "0":
        for kind, title, body in POLICIES:
            call(a.base, "POST", "/api/org-knowledge/sources", {"knowledgeType": kind, "title": title, "body": body}, token)
    cases, expected = [], {}
    for r in REVIEWS:
        rid = str(uuid.uuid5(NS, org + r["key"]))
        sql(a.db, f"insert into reviews (id, org_id, channel_id, product_id, rating, body, received_at, created_at, updated_at, "
                  f"reply_state, data_origin) values ({q(rid)}, {q(org)}, {q(NAVER)}, {q(products[r['product']])}, "
                  f"{r['rating']}, {q(r['body'])}, now(), now(), now(), 'PENDING', 'VERIFY_FIXTURE') "
                  f"on conflict (id) do nothing")
        cases.append(f"review|synthetic|{rid}")
        expected[rid] = {k: r[k] for k in ("key", "product", "purpose", "needs", "expect")}
    # The store's one NAVER account: an inquiry's work item belongs to one.
    account = str(uuid.uuid5(NS, org + "naver-account"))
    sql(a.db, f"insert into seller_accounts (id, org_id, channel_id, alias, connection_status, is_file_upload, created_at, "
              f"updated_at) values ({q(account)}, {q(org)}, {q(NAVER)}, '합성 계정', 'CONNECTED', false, now(), now()) "
              f"on conflict (id) do nothing")
    for r in INQUIRIES:
        iid, wid = str(uuid.uuid5(NS, org + r["key"])), str(uuid.uuid5(NS, org + r["key"] + "-work"))
        sql(a.db, f"insert into inquiries (id, org_id, channel_id, product_id, body, status, received_at, created_at, "
                  f"updated_at, seller_account_id, operational_state, data_origin, source_subtype, product_binding) values "
                  f"({q(iid)}, {q(org)}, {q(NAVER)}, {q(products[r['product']])}, {q(r['body'])}, 'UNANSWERED', now(), now(), "
                  f"now(), {q(account)}, 'ACTIVE', 'VERIFY_FIXTURE', {q(r['work'])}, 'SOURCE_EXACT') on conflict (id) do nothing")
        sql(a.db, f"insert into inquiry_work_item (id, org_id, inquiry_id, seller_account_id, channel_id, phase, created_at, "
                  f"updated_at) values ({q(wid)}, {q(org)}, {q(iid)}, {q(account)}, {q(NAVER)}, 'OPEN', now(), now()) "
                  f"on conflict (id) do nothing")
        # Proposed by run_cases.sh first, as the seller's first look does — unless an earlier round already did.
        phase = sql(a.db, f"select phase from inquiry_work_item where id = {q(wid)}")
        cases.append(f"inquiry|synthetic|{wid}" + ("|propose" if phase == "OPEN" else ""))
        expected[wid] = {k: r[k] for k in ("key", "product", "purpose", "needs", "expect")}
    os.makedirs(a.out, exist_ok=True)
    os.umask(0o077)
    with open(os.path.join(a.out, "cases.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(cases) + "\n")
    with open(os.path.join(a.out, "expected.json"), "w", encoding="utf-8") as f:
        json.dump({"org": org, "products": products, "cases": expected}, f, ensure_ascii=False, indent=1)
    print("org", org, "· products", len(products), "· cases", len(cases),
          "· product passages", sql(a.db, f"select count(*) from product_knowledge_chunks where org_id = {q(org)}"),
          "· policy passages", sql(a.db, f"select count(*) from org_knowledge_chunks where org_id = {q(org)}"))


if __name__ == "__main__":
    main()
