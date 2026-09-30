# Cafe24 First Live Baseline v1 — 2026-09-30

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

| Data type | Run | Rows | Outcome |
|---|---|---|---|
| ORDER_SUMMARY | 03:45:45Z (tutorial, manual) | 2 | SUCCESS |
| REVIEW | 03:48:48Z (scheduled) | **0** | SUCCESS |
| INQUIRY | 03:48:48Z (scheduled) | 2 | SUCCESS |
| ORDER_SUMMARY | 03:48:48Z (scheduled) | 2 | SUCCESS |

**4 runs · 4 SUCCESS · 0 failures · `failed_rows` total 0.**

### Canonical rows

| Table | Rows |
|---|---|
| `inquiries` | **2** — `thread_role` **ROOT 1 + REPLY 1** · `data_origin=REAL` · both `ANSWERED` |
| `reviews` | **0** |
| `order_daily_summaries` | **2** — one order on each of two dates |
| `products` / `channel_products` | 0 — `PRODUCT` is not a routine type, so nothing scheduled it |

Derived rows that appeared as a consequence: `customer_memory_entries` 2, `home_open_day` 2,
`sync_cursors` 3.

**On the inquiry count.** Two rows, but one of them is a reply to the other. Under
`docs/inquiry_thread_semantics_v1.md` (「답글은 문의가 아니다」) the honest reading is **one inquiry and
one reply**, which is also what the connector's own accounting line reports (`스레드답글=1`). Both are
`ANSWERED`, which is why `inquiry_work_item` is 0 — there is nothing outstanding to work.

## 4. Why REVIEW is 0

The run succeeded and collected nothing, and those are two different facts. The connector's own
accounting line names the query it made:

```
board=4 창=[2026-09-16 ~ 2026-09-30] offset=0 수신=0 (해당 구간에 새 글 없음)
board=6 수신=2 저장=2 비밀글제외=0 창밖제외=0 식별번호없음제외=0 스레드답글=1
```

What this establishes:

- **Board 4 (구매후기) was queried successfully and returned zero articles.** A board that did not
  exist, or a scope that did not cover it, would have failed the run; the run is SUCCESS.
- **Board discovery itself succeeded.** The first-connection capability probe reads the mall's board
  list, and it logs a sanitized line on *every* failure kind (rate-limited, insufficient scope,
  auth-failed). No such line exists for this run, so the probe reached `OK` — the board list was read.
- **The same credential, in the same window, read board 6 and found two articles.** Authorization,
  transport, pagination and parsing all work. Nothing about the review path is untested except the
  presence of data.
- **The window is 14 days and it is not configurable.** `Cafe24ApiConnector.ROUTINE_WINDOW_DAYS = 14`
  is a compile-time constant, deliberately a fixed recent window for routine re-reads. Looking further
  back is the job of the windowed backfill path (`POST /api/seller-accounts/{id}/backfill`), not of a
  routine run.

**Therefore:** the store has no 구매후기 board-4 article in 2026-09-16 … 2026-09-30. Whether it has
any *older* review is a question a bounded backfill answers, and that question is OPEN as of this
document — see §8.

## 5. Duplicate check

Re-running the same collection must not duplicate a row, and the strongest available evidence is that
it already happened: **`ORDER_SUMMARY` ran twice and `order_daily_summaries` still holds 2 rows**,
with `created_at == updated_at` on both — the second run changed nothing. `success_rows` counts rows
*processed*, not rows written, which is why the second run also reported 2.

The guarantees are structural, not incidental:

| Index | Covers |
|---|---|
| `uq_inquiries_external` | `(org_id, channel_id, external_id)` where `external_id IS NOT NULL` |
| `uq_inquiries_hash` | `(org_id, channel_id, content_hash)` where `content_hash IS NOT NULL` |
| `uq_reviews_external` | `(org_id, channel_id, external_id)` where `external_id IS NOT NULL` |
| `uq_reviews_hash` | `(org_id, channel_id, content_hash)` where `content_hash IS NOT NULL` |
| `uq_order_summary_natural` | `(org_id, channel_id, summary_date)` |

Measured: 2 inquiry rows, 2 distinct `external_id`, **0 real duplicate groups**.

**One result needs stating precisely, because read carelessly it looks like a defect.** A grouped
count over `(org_id, channel_id, content_hash)` reports one group of size 2. That is not a duplicate:
**both rows have `content_hash = NULL`**, SQL `GROUP BY` treats NULLs as one group, and the unique
index excludes NULLs by its own `WHERE` clause. Restricting the same query to non-null hashes returns
0 groups. Dedup for these rows is carried entirely by `external_id`, which is present and distinct on
both.

**Recorded, not fixed:** Cafe24 inquiries land with no `content_hash`, so the hash-based second line of
defence is inert for this channel. `external_id` alone is sufficient while the provider keeps issuing
stable article numbers, and it did here. This is a gap in defence depth, not an observed failure.

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

## 8. Open, and why it is open

**A bounded review backfill has not been run.** It is the one action that separates "this store has no
recent reviews" from "this store has no reviews". It requires the authenticated backfill endpoint;
widening the routine window instead would need a code change and a deploy, which was out of scope for
this run. Until it runs, §4's conclusion is scoped to its 14-day window and no further.

**Recorded, not fixed** (none of these blocked the baseline):

- Cafe24 has no per-data-type manual collection control in the UI; routine collection is the only
  path to a first INQUIRY/REVIEW read. See §1.
- Cafe24 inquiries carry no `content_hash`. See §5.
- The same person signing in with Google and with NAVER produced **two separate organizations** with
  the same display name. This baseline lives in the NAVER-identity org; the Google-identity org has no
  connected channel. Account linking is a product-owner decision and was not touched.
