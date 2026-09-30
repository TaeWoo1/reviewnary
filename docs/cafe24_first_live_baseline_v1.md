# Cafe24 First Live Baseline v1 — 2026-09-30

**Verdict: `PASS` · CLOSED.**

**What this is.** The first run in which a **real seller's Cafe24 store** was connected to the pilot
host and read end to end: OAuth consent → credential in the vault → routine schedules → collection →
canonical rows. It is the baseline every later Cafe24 number is compared against.

**What it is not.** Not a capability promotion. `docs/multi-channel-connector-roadmap.md` §4.1 remains
the single capability declaration; this file records only what this run showed.

**Sanitization.** Org and account identifiers appear as their first 8 characters. No customer content,
author, credential, token, refresh token, mall id, or email address is recorded here — by design, not
by omission: the numbers below were read from row counts, job accounting and connector log lines that
are themselves sanitized.

---

## 1. Environment

| | |
|---|---|
| Date | 2026-09-30 (UTC) |
| Checkout | `shadow-naver-cafe24-v1-rc12` — detached, exact-match, clean tree |
| Canonical host | a free DuckDNS name, migrated this day from the IP-derived `sslip.io` name |
| Edge | one Caddy site answering **both** names (`PILOT_EXTRA_HOSTS`), separate certificate per name |
| Org | `c412c3d5…` |
| Seller account | `4ad8527a…` · channel **CAFE24** |

**Posture during the run.** NAVER, Coupang, mock, mock-fallback, seed, demo entry, inquiry-publish
execution, review-publish execution, responsibility scheduler, responsibility investigation and
`REVIEW_IMPORT_UNATTENDED` were all `false`. `SELLEROPS_CONNECTOR_CAFE24_ANSWER_EXECUTION_SCOPES` was
blank, so the write re-consent entry point **did not exist** — marketplace WRITE was unreachable by
absence of a code path, not by a flag that could be flipped.

**Posture changed by this run, deliberately.** `SELLEROPS_SELF_PILOT_ENABLED` and
`SELLEROPS_COLLECT_SCHEDULER_ENABLED` were turned on together (both had been `false`). This was a
product-owner decision taken during the run, because Cafe24 has **no per-data-type manual collection
control in the UI** — the first-connection tutorial triggers one `ORDER_SUMMARY` run and nothing else,
and `CollectionSettingsSection` (the per-type 「지금 수집」) renders only in the Coupang view. Routine
collection is therefore the only path to a first INQUIRY or REVIEW read, and it is two halves that are
useless alone: self-pilot CREATES the schedules, the collect poller EXECUTES them. `preflight.sh`
checks the pair for exactly this reason.

## 2. Connection

| | |
|---|---|
| Connection status | **CONNECTED** |
| Credential rows | 1 · `connector_class=API` · `auth_type=OAUTH2` |
| Granted scopes | `mall.read_community`, `mall.read_order`, `mall.read_product` — **three, all READ** |
| Connected at | 03:45:31Z |
| Connection health | `state=CONNECTED` · `consecutive_failures=0` · `last_error` empty |

The granted scope set is the whole authorization this run holds. There is no `mall.write_community`,
so no seller-facing write was possible even had something asked for one.

## 3. Collection

Self-pilot created three schedules at 03:48:01Z — `REVIEW`, `INQUIRY`, `ORDER_SUMMARY`
(`SelfPilotReconciler.ROUTINE_TYPES`) — all enabled, 60-minute interval, `next_run_at` immediate. The
poller executed all three at 03:48:48Z.

| # | Data type | Run | Rows | Outcome |
|---|---|---|---|---|
| 1 | ORDER_SUMMARY | 03:45:45Z tutorial, manual | success 2 | SUCCESS |
| 2 | REVIEW | 03:48:48Z routine | 0 | SUCCESS — board 4, 14-day window, nothing received |
| 3 | INQUIRY | 03:48:48Z routine | success 2 | SUCCESS — first store |
| 4 | ORDER_SUMMARY | 03:48:48Z routine | success 2 | SUCCESS |
| 5 | REVIEW | 04:12:24Z **backfill, 90 days** | success 2 | SUCCESS — first store |
| 6 | REVIEW | 04:48:49Z routine | 0 | SUCCESS — 14-day window again, nothing received |
| 7 | INQUIRY | 04:48:49Z routine | success 0 · **skipped 2** | SUCCESS — re-read, nothing inserted |
| 8 | ORDER_SUMMARY | 04:48:50Z routine | success 2 | SUCCESS |
| 9 | REVIEW | 04:58:49Z **backfill, 90 days (repeat)** | success 0 · **skipped 2** | SUCCESS — re-read, nothing inserted |

**9 runs · 9 SUCCESS · 0 failures · `failed_rows` total 0.**

### Canonical rows

| Table | Rows |
|---|---|
| `inquiries` | **2** — `thread_role` **ROOT 1 + REPLY 1** · `data_origin=REAL` · both `ANSWERED` |
| `reviews` | **2** — `data_origin=REAL` · both rating 5 · received **2026-08-16** and **2026-09-07** |
| `order_daily_summaries` | **2** — one order on each of two dates |
| `products` / `channel_products` | 0 — `PRODUCT` is not a routine type, so nothing scheduled it |

Derived rows that appeared as a consequence: `customer_memory_entries` 2, `home_open_day` 2,
`sync_cursors` 3.

**On the inquiry count.** Two rows, but one of them is a reply to the other. Under
`docs/inquiry_thread_semantics_v1.md` (「답글은 문의가 아니다」) the honest reading is **one inquiry and
one reply**, which is also what the connector's own accounting line reports (`스레드답글=1`). Both are
`ANSWERED`, which is why `inquiry_work_item` is 0 — there is nothing outstanding to work.

## 4. Why the routine window found no review, and where the reviews were

The routine run succeeded and collected nothing, and those are two different facts. The connector's
own accounting line names the query it made:

```
board=4 창=[2026-09-16 ~ 2026-09-30] offset=0 수신=0 (해당 구간에 새 글 없음)
board=6 수신=2 저장=2 비밀글제외=0 창밖제외=0 식별번호없음제외=0 스레드답글=1
```

A bounded 90-day backfill (`2026-07-02 … 2026-09-30`) then found **two reviews**, and their dates
settle it:

```
cafe24:b4:a3671   rating 5   received 2026-08-16   media_count 1
cafe24:b4:a3675   rating 5   received 2026-09-07   media_count 0
board=4 수신=2 저장=2 비밀글제외=0 창밖제외=0 식별번호없음제외=0
```

**Both are older than 2026-09-16, so both sit outside the routine window by construction.** The
14-day run was not failing to see them; it was not looking where they are.

This is the fact to carry forward, and it is a property of the design, not of this store:

> **`Cafe24ApiConnector.ROUTINE_WINDOW_DAYS = 14` is a compile-time constant.** Routine collection
> deliberately re-reads a fixed recent window. A store whose reviews are older than two weeks shows
> **zero reviews** after connecting, on a run that reports SUCCESS, until someone runs a windowed
> backfill. Nothing in the product tells the seller this.

What the run also established along the way, none of which needed the backfill to know:

- **Board 4 (구매후기) was queried successfully and returned zero articles** in the routine window. A
  board that did not exist, or a scope that did not cover it, would have failed the run.
- **Board discovery itself succeeded.** The first-connection capability probe reads the mall's board
  list and logs a sanitized line on *every* failure kind (rate-limited, insufficient scope,
  auth-failed). No such line exists for this run, so the probe reached `OK`.
- **The same credential, in the same window, read board 6 and found two articles.** Authorization,
  transport, pagination and parsing all worked before any review was ever seen.

## 5. Duplicate check and idempotency

Every collection path in this run was executed **at least twice** and none of them inserted a row the
second time. This is measured, not inferred from the schema:

| Path | Repeat | Second-run accounting | Canonical count |
|---|---|---|---|
| INQUIRY, board 6, 14-day routine | 03:48:48Z → 04:48:49Z | `success 0 · skipped 2` | 2 → **2** |
| REVIEW, board 4, 90-day backfill | 04:12:24Z → 04:58:49Z | `success 0 · skipped 2` | 2 → **2** |
| ORDER_SUMMARY | three runs | `success 2` each time | 2 → **2** |

**`skipped_rows` is the number that matters.** On both re-reads the connector received the articles
again and the job recorded them as skipped, not stored — the rows were recognised as already present.
Duplicate groups by external / natural key: **inquiries 0 · reviews 0 · order summaries 0**;
2 inquiry rows with 2 distinct `external_id`, 2 review rows with 2 distinct `external_id`.

**Two log layers report different numbers for the same event, and only one of them means "written".**
The connector's line for the INQUIRY re-read says `수신=2 저장=2` while the job says
`success_rows=0 skipped_rows=2`. The connector's 「저장」 counts rows handed to the store; the job's
accounting is what distinguishes an insert from a skip. Reading the connector line alone would suggest
two rows were written on a run that wrote none.

Row timestamps agree with all of it: the two inquiries have `created_at ≠ updated_at` (re-read and
upserted, not inserted), and the two reviews have `created_at == updated_at` after the *routine* tick
(never re-read there — they are outside its window) and were likewise only skipped by the repeated
backfill.

The guarantees underneath are structural:

| Index | Covers |
|---|---|
| `uq_inquiries_external` | `(org_id, channel_id, external_id)` where `external_id IS NOT NULL` |
| `uq_inquiries_hash` | `(org_id, channel_id, content_hash)` where `content_hash IS NOT NULL` |
| `uq_reviews_external` | `(org_id, channel_id, external_id)` where `external_id IS NOT NULL` |
| `uq_reviews_hash` | `(org_id, channel_id, content_hash)` where `content_hash IS NOT NULL` |
| `uq_order_summary_natural` | `(org_id, channel_id, summary_date)` |

**One result needs stating precisely, because read carelessly it looks like a defect.** A grouped
count over `(org_id, channel_id, content_hash)` reports one group of size 2, on both `inquiries` and
`reviews`. Neither is a duplicate: **every row in both tables has `content_hash = NULL`** (non-null
count 0), SQL `GROUP BY` treats NULLs as one group, and the unique index excludes NULLs by its own
`WHERE` clause. Restricting the same query to non-null hashes returns 0 groups on both tables.

**Backlog fact — carried forward, not fixed.** Cafe24 inquiries **and** reviews land with no
`content_hash`, so the hash-based second line of defence is **inert for this channel on both tables**
(`reviews.dedup_key_version` is 1 while the hash it would key is absent). Dedup rests entirely on
`external_id`. That held here across four re-reads, and it holds as long as the provider keeps issuing
stable article numbers — but it is one mechanism where the schema provides two. This is a gap in
defence depth, not an observed failure.

## 6. Marketplace WRITE

**Zero.** Every execution, approval, draft and submission table was counted and is empty:

`inquiry_execution` · `inquiry_approval` · `inquiry_action_intent` · `inquiry_proposal` ·
`inquiry_reply_draft` · `review_reply_execution` · `review_reply_submission_ref` ·
`review_reply_approval` · `review_reply_draft` · `review_reply_outcome` · `review_import_launch` ·
`review_import_plan` · `review_import_segment` · `inquiry_import_batch` — **all 0.**

Three independent reasons this could not have been otherwise: both publish-execution flags were
`false`, the write re-consent scope was blank so the entry point bean did not exist, and the granted
OAuth scope set contains no write scope at all.

## 7. Host verification

| | |
|---|---|
| `smoke.sh` | **37 ok, 0 failed** — includes both hostnames serving HTTPS 200 with valid certificates |
| Guard suite | 12/12 suites, 245 assertions, 0 failing |
| Containers | backend · edge · agent-runtime · postgres healthy (frontend defines no healthcheck) |
| `backup.sh` | **OK** 03:50:41Z · 353,660 bytes · off-host S3 upload confirmed by ETag · instance-role credential |
| Seller-facing UI | **confirmed by the operator in the browser** — both reviews visible; the inquiry list carries both rows |

**On the empty 「미답변 문의」 view.** It is empty and that is correct: both inquiries are `ANSWERED`,
so `inquiry_work_item` is 0 and there is nothing outstanding to work. The rows are in the inquiry
list, not in the queue of things to answer.

## 8. Verdict, and what is carried forward

**`PASS` · the Cafe24 first live baseline is CLOSED.** Every completion criterion was met on a real
seller's store: account connected, credential stored and reused, first INQUIRY read, first REVIEW
read, canonical rows with no duplicates, data visible in the product, **zero marketplace writes**,
zero connector errors, one successful off-host backup.

Nothing here promotes a capability. `docs/multi-channel-connector-roadmap.md` §4.1 remains the single
capability declaration.

### Backlog — recorded, not fixed

None of these blocked the baseline, and each is a thing the next person needs to know.

1. **A store with no review newer than 14 days shows zero reviews after connecting**, on a run that
   reports SUCCESS, until a windowed backfill is run by hand.
   `Cafe24ApiConnector.ROUTINE_WINDOW_DAYS = 14` is a compile-time constant and the product says
   nothing about it. This is exactly what happened here (§4).
2. **There is no per-data-type manual collection control in the Cafe24 UI.** The first-connection
   tutorial fires one `ORDER_SUMMARY` run; `CollectionSettingsSection` (the per-type 「지금 수집」)
   renders only in the Coupang view. Routine collection is the only path to a first INQUIRY or REVIEW
   read, and the 90-day review backfill in this run could only be issued against the API directly —
   a seller has no way to ask for it (§1, §4).
3. **`content_hash` is NULL on every Cafe24 inquiry and review row**, so the hash-based second line
   of dedup defence is inert for this channel on both tables. `external_id` alone carries it (§5).
4. **The same person signing in with Google and with NAVER produced two separate organizations** with
   the same display name. This baseline lives in the NAVER-identity org; the Google-identity org has
   no connected channel. Anyone verifying this data must sign in with the NAVER identity. Account
   linking is a product-owner decision and was not touched.
5. **Two log layers report different numbers for one event.** The connector prints 「저장」 for rows
   handed to the store; the job records `success_rows` / `skipped_rows`. Only the latter separates an
   insert from a skip (§5).
