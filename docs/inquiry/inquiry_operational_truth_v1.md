# Inquiry Operational Truth & Sync Lifecycle Hardening — v1

**Date** 2026-08-22 · **Branch** `feat/inquiry-operational-truth` · **Local PG 15.13 `sellerops`**,
org `7146c50f…d8e0` · **No marketplace contact, no credential read, no WRITE.**

**What this is.** The audit that preceded it
(`docs/archive/2026-q3/cafe24_inquiry_source_reconciliation_audit_2026-08-22.md`) and the package that acts on it.
Three separate defects, one package, because they share one corpus:

1. the seller's spam decisions were recorded and then ignored by every current-truth read;
2. a historical backfill permanently redefined where routine collection starts;
3. nothing anywhere recorded "the source still showed us this row", so no reconciliation of any kind
   could be built.

**What it is NOT.** Not a deletion, not an absence-based reconciliation, and not a spam classifier.
Nothing here decides what is spam — the seller already did, through the approved dismissal batches of
2026-07-06 — and nothing here turns a missing row into a deleted one.

---

## 1. The finding, in one paragraph

`inquiry_work_item` has held **3,199 rows at `DISMISSED` / `disposition=SPAM`** since 2026-07-06,
written by seven approved batches with manifest hashes (`cafe24-spam-dismiss-2026-07-06-chunk-01…07`,
`approved_by=demo@sellerops.ai`). The work queue honoured that decision, because the queue read is
phase-filtered. **Nothing else did.** 홈, Today Inbox, 상품 신호, 반복 문의, item analysis and the
Operator all count through `inquiries` directly, and `inquiries` had no idea. The home screen printed
**3,208 미답변** of which **3,199 were work the seller had already decided not to do**, and the repeat
analysis reported `기타 ×1,779 · 품질 ×523 · 사이즈 ×473 · 배송 ×240 · 가격 ×195` — every one of them
a spam board post — as customer patterns.

---

## 2. Schema and model

| Object | Migration | What it holds |
|---|---|---|
| `inquiries.operational_state` | **V51** | `ACTIVE` · `EXCLUDED_SPAM` · `SOURCE_REMOVED`, NOT NULL default `ACTIVE` |
| `inquiries.operational_state_at` | V51 | when it last changed; null while never left `ACTIVE` |
| `inquiries.last_seen_at` | V51 | when the source last showed us the row — **including unchanged runs** |
| `idx_inquiries_org_state_status` / `…_received` | V51 | the two shapes every current read now has |
| `sync_cursors` key `backfill` | **V52** | the historical lane, split from the routine `primary` lane |

`InquiryOperationalState` is a closed enum. `SOURCE_REMOVED` is **spelled and unreachable** — the same
technique the Operator's WRITE action class uses — so that the day a sweep is proven authoritative there
is somewhere honest to put a source deletion, and until then a test can assert nothing puts one there.

### Source of truth

```
inquiry_work_item.phase = DISMISSED          ← the AUTHORITY (seller decision, approved batch, audited)
  + .disposition        = SPAM
            │
            │  InquiryOperationalStateProjector    (the ONLY writer)
            ▼
inquiries.operational_state = EXCLUDED_SPAM  ← a PROJECTION (rebuildable, reversible, never a decision)
            │
            ▼
InquiryRepository.ACTIVE  →  홈 · Today Inbox · 상품 신호 · item analysis · 리포트 · Operator
CustomerMemoryEntryRepository.ACTIVE_SOURCE  →  반복 문의 · 현재 retrieval
ItemAnalysisRepository (list)  →  FAQ / 상세페이지 후보
```

The projection is **symmetric**: `project()` returns the state the ledger implies *right now*, not a
one-way transition — so a reversal at the work item flows back through the same call with no undo
bookkeeping. `InquiryOperationalStateFenceTest` proves there is exactly one writer, because a second
writer is a second authority and the first symptom would be a count that disagrees with the queue with
nobody able to say which is right.

### What deliberately does NOT carry the exclusion

- **Dedup keys** (`existsByOrgIdAndChannelId…`, `findByOrgIdAndChannelIdAndExternalId`). They must see
  every stored row or a re-collected spam post would insert a second copy of itself on every sweep.
- **Id-driven reads** (`findAllById`). The caller has already decided what it is asking about.
- **Historical / audit reads.** Exclusion is a state, never a delete.
- **The customer-memory index itself.** 3,201 entries were kept; only *current retrieval* passes over
  them. The guard is a subquery on the inquiry, not a copy of the flag on the entry — a copy would be a
  second place to be wrong, and the two would drift the first time a dismissal was reversed. It is
  written as "not exists an excluded source" rather than "exists an active source", because the two
  differ only for an entry whose inquiry row is gone, and there the second form would turn a missing
  record into evidence of a dismissal nobody made.

---

## 3. Cursor lifecycle repair (defect 2)

**The invariant: a historical backfill must never redefine the starting cursor or window of routine
collection.**

`sync_cursors` held one row per (org, account, data type) under the key `primary`, and
`SyncRunExecutor.runPages` wrote the operator's backfill seed into it. Nothing ever cleared it. The demo
org's Cafe24 INQUIRY cursor was found parked at **`b6:o2:s2025-03-23:e2025-03-25`** with an **hourly
schedule enabled** — so every scheduled run swept a three-day window from March 2025 starting at offset
2, collected nothing, and did so forever. That is why 3,201 inquiries collected on 2026-07-06 were never
re-observed once (**3,200 still carry `created_at = updated_at`**), and why no reply-status change on any
of them could ever land although the upsert that would have applied it has existed since V34.

Two lanes, one table: `primary` is routine, `backfill` is historical. V52 moves any `primary` value in
the windowed article-cursor shape into the backfill lane and clears the routine one. A cleared routine
cursor is the honest state — "routine collection has recorded no progress on this board" — and it
restores the unbounded offset sweep that was the behaviour before any backfill ran. That sweep is
idempotent: the V34 external-id upsert absorbs a re-observed article as an update or a no-op.

Applied live:

| account | data type | before (`primary`) | after (`primary`) | after (`backfill`) |
|---|---|---|---|---|
| `78da0eb3…` (demo) | INQUIRY | `b6:o2:s2025-03-23:e2025-03-25` | *(null)* | `b6:o2:s2025-03-23:e2025-03-25` |
| `78da0eb3…` | REVIEW | `b4:o0:s2026-06-30:e2026-07-06` | *(null)* | `b4:o0:s2026-06-30:e2026-07-06` |
| `c5f63e75…` | INQUIRY | `b6:o905:s2026-05-06:e2026-05-06` | *(null)* | `b6:o905:s2026-05-06:e2026-05-06` |
| `c5f63e75…` | REVIEW | `b4:o3:s2026-04-09:e2026-06-25` | *(null)* | `b4:o3:s2026-04-09:e2026-06-25` |
| `bdccb7a7…` / `c5f63e75…` | ORDER_SUMMARY | JSON envelope / date | **untouched** | — |

ORDER_SUMMARY cursors self-window and are not backfill seeds, so the pattern cannot match them.

---

## 4. Observation primitive (defect 3)

Both upserts skipped the save entirely when the source matched storage, so an unchanged row and a row
the seller deleted at the platform left **exactly the same trace: none**. `inquiries.last_seen_at` and
`cafe24_community_articles.collected_at` are now written on the no-op branch too.

**Recording it is not acting on it.** Nothing turns a stale `last_seen_at` into a tombstone, and
`InquiryOperationalStateFenceTest` proves there is no such path — by text scan *and* by exhausting every
(state × work-item shape) combination through the projector. Absence-based reconciliation stays disabled
until a Cafe24 sweep is proven to be an authoritative snapshot; §7 lists what that proof requires.

---

## 5. Live result on the real corpus

All numbers below are the demo org through the real HTTP API, backend restarted on this branch.

| Reading | Before | After |
|---|---|---|
| 홈 `GET /api/dashboard/summary` 미답변 | **3,208** | **9** |
| Today Inbox / 리포트 / Operator `GET /api/inbox` 미답변 | **3,208** | **9** |
| 홈 할 일 문구 | `미답변 문의 3208건을 확인하세요.` | `미답변 문의 9건을 확인하세요.` |
| `GET /api/item-analysis` rows | 7,136 | **3,937** (INQUIRY 3,220 → 21) |
| … of which `답변 필요` | 3,208 | **9** — the same number as the inbox |
| 상품 `b718ed98…` 신호 (inquiries / unanswered) | 3,201 / 3,200 | **2 / 1** |
| 반복 문의 TOPIC 축 | `품질 523 · 사이즈 473 · 배송 240 · 가격 195` | **`색상 4 · 설치 3 · 제품정보 3 · 가격 2`** |
| 반복 문의 SIGNATURE 축 | 6 (already clean) | 6 (unchanged) |
| `indexedInquiries` (rubric denominator) | 3,220 | **21** |
| `inquiries` rows | 3,229 | **3,229** — nothing deleted |
| `customer_memory_entries` INQUIRY | 3,220 | **3,220** — nothing deleted |
| `inquiry_work_item` DISMISSED/SPAM | 3,199 | 3,199 — untouched |

Recovery of the existing rows, through `POST /api/inquiries/operational-state/backfill`:

```
run 1  {"examined":3199,"excluded":3199,"restored":0}
run 2  {"examined":3199,"excluded":0,"restored":0}      ← idempotent
```

**Reversal, proven live and round-tripped:** one work item moved `DISMISSED → OPEN`, backfill →
`{"restored":1}` and 미답변 **9 → 10**; restored to `DISMISSED/SPAM`, backfill → `{"excluded":1}` and
미답변 **10 → 9**. The database ends exactly where it started.

Other orgs: **untouched**, all 30 remaining inquiries `ACTIVE`. Reviews: untouched (3,916 analyses).

---

## 6. Repeat-inquiry re-measurement (contaminated vs clean)

`contracts/inquiry-issue/v1/RUBRIC.md` gates, recomputed on the cleaned corpus. **The contaminated
figures are kept beside the clean ones deliberately** — a measurement whose denominator changed is not
the same measurement, and quietly replacing it would hide that the corpus, not the classifier, moved.

| Gate | 2026-08-21 (contaminated) | 2026-08-22 (clean) | Verdict |
|---|---|---|---|
| G1 recall | 19 / **3,220** = 0.006 | 19 / **21** = **0.905** | the denominator was the defect, not the classifier |
| G2 precision | 1.00 on the genuine subset | 1.00 | PASS |
| G3 usefulness (≥5 signatures × ≥3 occurrences / 28d) | **4** | **4** | **미달 — unchanged** |
| G4 no manufactured pattern | 0 violations (0/3,201 spam labelled) | 0 violations | PASS |

**G3 did not move, and that is the honest result.** The spam never produced a signature — the classifier
refused all 3,201 — so removing it from the corpus could not add a repeat. What the cleanup fixed is the
**TOPIC axis**, which was the rule-based extractor's output and *was* counting spam: `품질 523` and
`사이즈 473` were never customer patterns. The remaining constraint on G3 is the size of the genuine
corpus (**21 indexed inquiries, 19 signed**), not contamination. **Repeat-inquiry detection therefore
stays a LIMITATION**, for a different and now clearly stated reason.

---

## 7. What still needs a live Cafe24 reconnect

Every collection run on the demo org's Cafe24 account has failed since **2026-08-18** with
`자격 증명 복호화에 실패했습니다` (41 INQUIRY runs). Nothing in this package assumes otherwise, and none of
it required the marketplace.

Left explicitly at the live approval boundary:

1. **Re-connect the Cafe24 account.** Until then the repaired routine cursor cannot actually sweep, so
   `last_seen_at` stays null on every row (0 of 3,229 — measured, not assumed).
2. **Prove the routine lane sweeps forward.** One unbounded board-6 sweep to exhaustion, then observe
   that new articles land and reply-status changes update rows in place. The mechanism is repaired and
   unit-proven (`SyncCursorLaneTest`); the live half needs the connection.
3. **Prove a sweep is an authoritative snapshot** — the precondition for ever enabling
   absence → `SOURCE_REMOVED`. Conditions carried over from the audit: one job, one board, one closed
   window, offset 0 to exhaustion, `SUCCESS`, no rate-limit, no page error, no `MAX_PAGES` trip, zero
   rows dropped for a missing `article_no`, and an observed-count floor against the stored count. The
   `start_date`/`end_date` filter is doc-asserted and has already been observed returning rows outside
   its own window, so this is the item that genuinely needs live evidence rather than reasoning.
4. **Whether Cafe24 exposes deletion or restriction at all.** The row projection carries nine fields and
   none of them is a visibility flag; a 비노출 게시글 may be invisible to us even when it is still there.

---

## 8. Regression

| Suite | Tests | Failures |
|---|---|---|
| backend | **2,582** (+17) | 0 |
| frontend | **2,215** (+2) | 0 |
| agent-runtime | 232 | 0 |
| collector | 9,150 | 0 |

New: `InquiryOperationalTruthTest` (8) · `InquiryOperationalStateFenceTest` (3) ·
`SyncCursorLaneTest` (2) · `SourceObservationPrimitiveTest` (3) ·
`reportView.test.ts` parity + unavailable (2).

Existing tests changed, and why: `Cafe24ArticleBackfillFlowTest` now reads the **backfill** lane (the
behaviour it asserts moved lanes, it did not change); the two dismissal-service tests take the projector
in their constructor.

---

## 9. Product decisions this leaves open

1. **Does an excluded inquiry belong in historical reports?** Current/live figures reflect the exclusion
   immediately (§5). Nothing rewrites a past report, because nothing stores one — the weekly report is
   derived live from four endpoints. If stored periodic reports ever exist, this is the question that
   has to be answered first.
2. **There is no seller-facing un-dismiss control.** Reversal works and is proven, but today it is
   reached by moving the work item and re-running the projection. Whether a seller should be able to
   restore a dismissed inquiry from the UI is a product decision, not a missing implementation.
3. **The corpus itself.** 3,201 of this org's 3,229 inquiries are spam board posts that the platform may
   or may not still be serving. That is a collection-policy question (should board 6 spam be ingested at
   all?), and it is upstream of everything here.

---

## 10. 진행 중 문의 재확인 · 「확인하지 못함」 — 설계와 단계별 구현

### 10.0 구현 상태 (2026-11-08)

**완료 — 댓글 관측의 결과 구분 기반.** `Cafe24InquiryAnswerObserver.observeChecks`와 `observeExactChecks`는
요청한 글마다 `SHOP_ANSWERED` · `NO_SHOP_COMMENT` · `NOT_CHECKED`를 돌려준다. 판매자 답변 확인에만 답변 시각이 붙는다.
조회 실패 · 429 · 조회 상한 밖 · 작성자 불명 · 판매자 댓글 시각 해석 실패는 `NOT_CHECKED`다. 댓글 보유 목록이 상한에 닿으면
목록에 없는 글을 「댓글 없음」으로 확정하지 않는다. 글과 다른 번호의 댓글도 확인 근거로 받지 않는다.

댓글 작성자 비교 결과에 더해 「작성자를 구별할 정보가 있었는가」만 불리언으로 전달한다. 작성자 ID · 이름 · 댓글 본문은 새 결과에
담지 않는다. 누락된 응답 목록 · 잘못된 목록 행 · 댓글 자원 404를 빈 목록으로 바꿔 「없음」으로 판정하던 길도 차단했다.
(이 단계에서는 기존 수집이 답변이 증명된 글과 시각만 받는 호환 메서드를 썼다 — 아래 V184 단계에서 세 결과로 바뀌었다.) 정확한 대상 집합을 지정한 기존 이력 보정은
발견 조회 실패를 예전처럼 예외로 전달한다(실패한 이력 실행이 성공 0건으로 끝나지 않음).

**완료 — 실제 수집 경로 연결 · 서버 저장 (2026-11-08, V184).** 카페24 문의 정기 수집(`Cafe24ApiConnector` 게시판 6)이
`observeChecks`의 세 결과를 행마다 싣고(`CanonicalInquiry.commentCheck`), 수집 저장(`IngestionService`)이 문의마다 세 칸에 남긴다.

| 칸 | 뜻 | 언제 바뀌나 |
|---|---|---|
| `comment_check` | 마지막 결과 `SHOP_ANSWERED` · `NO_SHOP_COMMENT` · `NOT_CHECKED` | 댓글 확인이 그 글을 본 수집마다 |
| `comment_check_attempted_at` | 그 결과가 나온 때 — 결과와 무관 | 위와 같음 |
| `comment_checked_at` | 확인에 **성공한** 마지막 때(`SHOP_ANSWERED` · `NO_SHOP_COMMENT`) | 성공 때만. `NOT_CHECKED`는 그대로 둔다 |

- 문의 본문·등록 상태가 그대로인 재수집도 세 칸을 갱신한다.
- 댓글 확인이 돌지 않은 수집은 세 칸을 건드리지 않는다: 댓글 확인이 없는 배포, 후보가 아닌 글(이미 답변 상태인 글 · 스레드 답글), 다른 채널.
  스레드 답글은 매퍼와 저장 두 곳에서 막는다.
- `SHOP_ANSWERED`만 기존 경로(상태 `ANSWERED` → `reconcileConnectorAnswered`)로 할 일을 닫는다. 나머지 두 결과는 상태 · 할 일을 바꾸지 않는다.
  닫힌 문의를 다시 열지도, 진행 중(`PROPOSED`) 할 일을 닫지도 않는다.
- 정기 수집은 날짜를 읽을 수 없는 글도 남기는데, 댓글 보유 목록은 날짜 창으로 묻는다. 그래서 그런 글, 또는 창 안에 있다고 증명되지 않는 글의
  `NO_SHOP_COMMENT`는 `NOT_CHECKED`로 낮춘다(`docs/FINDINGS.md` §B118).
- 기존 문의는 세 칸 모두 null(확인 이력 없음). 작성자 식별자 · 댓글 본문 · 댓글 번호는 저장하지 않는다.
- DB 제약: 값은 세 가지뿐이고, 결과와 결과 시각은 함께 있거나 함께 없다. 성공 시각은 결과 없이 있을 수 없다.

(V184 단계 당시) 판매자 화면은 바뀌지 않았다 — 세 칸을 읽는 API · 화면은 아래 「화면 안내」 단계에서 붙었다. 바뀌는 것은 「댓글로 답한
문의가 할 일에서 빠지는 것」(이전과 같음)뿐이었고, 나머지는 서버 기록이었다. 이력 보정(`Cafe24HistoricalCommentReconciler`, `observeExact`)은 이 칸을 쓰지 않는다.

**검증(V184 단계).** 합성 HTTP 응답 → 커넥터 → 매퍼 → 저장까지 `Cafe24CommentCheckIngestTest` 17건(H2)이 통과했다. 다룬 경우: 변경 없는 재수집,
발견 조회 실패 · 429, 댓글 조회 실패, 발견 목록 상한, 댓글 조회 상한, 날짜 없는 글, 확인하지 않은 수집, 답변된 문의 · 진행 중 할 일,
스레드 답글 · 이미 답변 상태인 글, 다른 채널, 다른 org(판매자 계정). 같은 17건과 V184 칸 · 제약 · 기존 행 null 확인 3건을 버리는
PostgreSQL 16.15(포트 55432, 끝나고 삭제)에서 `Cafe24CommentCheckPostgresProofIT`(`SELLEROPS_PG_PROOF=1`)로 다시 돌려 20건이 통과했다.
Flyway 182개 버전 · V184까지 모두 성공했고 남은 데이터는 0행이다. 기본 개발 DB와 QA DB에는 적용하지 않았다.
지킴 장치를 하나씩 깨 보면 해당 테스트가 실패했다:
- 실패가 성공 시각을 움직임 → 5건
- 변경 없는 재수집이 기록을 건너뜀 → 3건
- 확인하지 않은 수집이 기록을 지움 → 1건
- 날짜 창 낮추기 제거 → 1건
- 매퍼의 답글 막기 두 곳 제거 → 1건

매번 소스를 바이트 단위로 되돌렸다. 백엔드 전체 6,225건 중 실패는 1건, `GoalSmokePreflightTest`뿐이다. 평가 저장소가 복원되지 않은 환경에서
안내 문구를 기대하는 테스트이고, 관련 파일은 2026-09-21 커밋 그대로라 이번 변경과 무관하다. 151건은 opt-in이라 건너뛰었다.

**완료 — 문의 상세 · 승인 · 등록 시점 안내 (2026-11-08, 제품 책임자 결정).** 상세 응답(`GET /api/inquiries/{id}`)에
`commentCheck`(`CommentCheckView`: `state` · `attemptedAt` · `lastCheckedAt`)를 더했다. `state`는 저장된 세 결과에 `NO_RECORD`(한 번도
기록되지 않음 — V184 이전 문의, 그 뒤 다시 읽히지 않은 문의)를 더한 네 단어다. 카페24가 아닌 채널 · 스레드 답글 · 커넥터가 쓰지 않은 행(판매 계정
없음)은 `null` — 묻는 일 자체가 없다. 저장된 기록이 있으면 언제나 그대로 보인다. 화면 문장은 `frontend/src/lib/commentCheck.ts`가 정한다.

| 기록 | 문의 상세(사실 한 줄) | 승인 · 등록 바로 앞(dock · 보내기 영역 · 등록 확인 블록) |
|---|---|---|
| `NOT_CHECKED`, 성공 기록 없음 | 경고색 「댓글로 이미 답했는지 확인하지 못했어요.」 + 「확인을 시도한 때 ○ · 확인에 성공한 기록은 아직 없어요」 | 「댓글로 이미 답했는지 확인하지 못했어요. 판매자센터에서 등록 여부를 확인해 주세요.」 |
| `NOT_CHECKED`, 과거 성공 있음 | 경고색 「최근 수집에서 … 확인하지 못했어요.」 + 「마지막으로 확인한 때 ○ · 확인하지 못한 수집 ○」 | 위 문장 + 「마지막으로 확인한 때는 ○이에요.」 |
| `NO_RECORD` | 조용한 글자 「댓글로 이미 답했는지 확인한 기록이 아직 없어요.」 | 「댓글로 이미 답했는지 확인한 기록이 없어요. 판매자센터에서 등록 여부를 확인해 주세요.」 |
| `NO_SHOP_COMMENT` | 조용한 글자 「댓글로 단 판매자 답변은 없었어요. ○ 확인」 | 없음 |
| `SHOP_ANSWERED` | 조용한 글자 「판매자 댓글로 답변한 것을 확인했어요. ○ 확인」 | 없음(이미 답변됨) |

- 답변이 이미 있다고 아는 문의(상태 `ANSWERED` · 우리 등록 확인 · `COMPLETED`)에는 확인 실패 · 기록 없음을 말하지 않는다.
- 실패 시각을 성공 시각으로 쓰지 않는다. 실패를 「답변 없음」이나 판매자의 잘못으로 말하지 않는다.
- **새 차단은 없다.** 서버의 승인 · 등록 경로는 이 칸을 읽지 않고, 화면의 어떤 버튼도 이 값으로 비활성화되지 않는다. 기존 차단 규칙은 그대로다.
- 목록 · 홈에는 보이지 않는다(§10.4).
- 「확인할 일」의 사례 화면(`OperationsCase`)은 이 칸이 없는 다른 읽기라 안내가 없다. 그 화면의 「보내러 가기」가 여는 문의 화면에서 보인다.
- 개발용 가짜 채널 게시판(`FakeChannelBoard`)은 행마다 `commentCheck`를 줄 수 있어, 화면 점검이 실제 수집 경로(`IngestionService`)로 세 칸을 채운다.

**검증(화면 안내 단계).** 백엔드: `CommentCheckViewTest` 6건, 수집 → 저장 → 상세 뷰가 세 경우를 다르게 내는
`Cafe24CommentCheckIngestTest` 1건 추가(18건), 상세 서비스가 카페24에만 싣는 `InquiryProposalServiceTest` 1건, 가짜 게시판 1건 — 관련 묶음
98건 통과(건너뜀 21건은 PostgreSQL opt-in). 프런트: `InquiryResponsePanel.commentCheck.test.tsx` 11건, 전체 319개 파일 4,242건 통과.
지킴 장치를 하나씩 깨 보면 해당 테스트가 실패했다(dock 안내 제거 1 · 등록 확인 블록 안내 제거 1 · 실패 시각을 성공 시각으로 2 · 안내가 등록 버튼을 막음 1 ·
기록 없음을 실패 문장으로 2 · 안내가 복사 버튼을 막음 1). 매번 소스를 바이트 단위로 되돌렸다.
화면(`holds/eval/commentcheck-20261108/`, 버리는 PostgreSQL 16 컨테이너 · 끝나고 삭제, 가짜 채널, 모델 호출 0, `.env` 없이 실행, 합성 4건): 데모
로그인 → API 연결 계정 등록 → 「지금 수집하기」 두 번(두 번째 전에 게시판 파일에서 한 건을 `NO_SHOP_COMMENT` → `NOT_CHECKED`로 바꿈). 저장된
칸은 SQL로 넣지 않았다. 규칙 기반 제안(모델 없음) 뒤 판매자 초안을 저장해 dock을 세웠다. 네 화면이 각각 다른 문장을 보였고, 과거 성공 뒤 실패는
「마지막으로 확인한 때 17:58 · 확인하지 못한 수집 17:59」였다. 「초안 복사」는 넷 다 눌렸다. 등록 실행 스위치는 가짜 채널과 함께 켤 수 없어
등록 확인 블록은 컴포넌트 테스트로만 확인했다.

**정정(2026-11-08).** 「오래 열린 문의는 `NO_RECORD`로 남는다」는 틀렸다. 정기 수집은 최근 14일을 매번 다시 읽으므로, 14일 안에 있을 때 한 번이라도
댓글 확인을 받은 문의는 **그때의 기록을 들고** 창 밖으로 나간다 — 「과거 기록은 있는데 지금 상태는 모른다」. 화면은 그 기록의 시각을 그대로 보이므로
오래된 「댓글 없음 · ○일 확인」도 그 날짜로 읽힌다. `NO_RECORD`는 V184 전에 저장됐고 그 뒤 다시 읽히지 않은 문의다.

**완료 — 카페24 재확인 실행부 (2026-11-08, 스케줄 없음).** `Cafe24AnswerRecheckRunner` — 한 판매 계정, 한 번 실행, 요청 상한과 시계는 호출하는
쪽이 준다. 이것을 부르는 빈 · 스케줄은 없다. → 같은 날 정기 수집 뒤에 연결했다(아래 「정기 수집 뒤 재확인 연결」, 기본 꺼짐).

- **대상**: 그 계정의 카페24 게시판 6 문의 중 `UNANSWERED` · `ACTIVE`(스팸 등 제외 아님) · 실제 데이터 · 스레드 답글 아님 · 받은 날(KST)이 정기 수집
  창(14일)보다 이전 · 할 일 `OPEN`/`PROPOSED` · 우리 등록이 진행 중이거나 확인 대기(`ACTION_PENDING` · `DISPATCHING` · `EXECUTED` ·
  `DELIVERY_UNKNOWN`)가 아님. 번호로 하나씩 이름을 대고 묻는다 — 날짜 창 · offset · 커서가 없고, 신규 수집 커서를 건드리지 않는다.
- **순서**: 마지막 확인 성공(`comment_checked_at`)이 오래된 것부터(성공한 적 없는 것이 먼저), 같으면 수집한 순. 한 실행에서 한 문의는 한 번.
- **방법**: 최대 25건씩 — 번호로 글 다시 읽기 1요청, 아직 미답변인 글만 댓글 보유 확인 1요청 + 댓글 있는 글마다 1요청
  (`fetchByArticleNumbers` · `observeExactChecks`). 돌아온 글은 정기 수집과 같은 저장(`IngestionService.ingestInquiries`)을 지난다.
- **결과**:

  | 무엇이 보였나 | 기록 | 할 일 |
  |---|---|---|
  | 판매자 댓글(작성자 증명) 또는 답변 상태 | `ANSWERED` · 답변 시각은 채널의 시각 · 댓글이면 `SHOP_ANSWERED` | 기존 규칙으로 닫힘 |
  | 댓글 없음 | `NO_SHOP_COMMENT` · 확인 성공 시각 | 그대로 열림 |
  | 글 다시 읽기 실패 · 429 · 댓글 보유 확인 실패 · 작성자 불명 · 번호로 물었는데 글이 오지 않음 | `NOT_CHECKED` · 성공 시각은 그대로 | 그대로 열림 |
  | 요청 상한 때문에 묻지 않음 | **아무것도 쓰지 않음** — 실패가 아니다 | 그대로 |

  글이 오지 않은 경우는 상태 · 할 일 · `last_seen_at`을 건드리지 않는다(삭제도 답변도 아니다, `IngestionService.recordFailedCommentLook`).
- **실패 원인 구분 (같은 날 보완).** 저장 기록과 화면은 「확인 못 함」 하나다. 실행 결과(`Outcome.looks`)는 문의마다 **누구의 실패인지**를 함께
  돌려준다 — 저장된 기록만 보고는 나중에 복원할 수 없기 때문이다(댓글 확인이 예외를 `NOT_CHECKED`로 바꾸므로). 원인은 관측기가 결과마다 붙이고
  (`Cafe24InquiryAnswerObserver.Reason`), 실행부가 둘로 나눈다(`Cause`).

  | 원인 | 무엇 | 실행 |
  |---|---|---|
  | 문의(`ARTICLE`) | 그 글의 댓글 읽기가 404 · 500 · 해석 불가 응답 · 댓글로 답변을 증명하지 못함(작성자 불명 · 시각 해석 불가) · 번호로 물었는데 그 글만 오지 않음 | 계속한다 |
  | 계정 · 채널(`ACCOUNT`) | 429 · 401/403(글 다시 읽기 · 댓글 보유 확인 · 댓글 읽기 어디서든) · 댓글 읽기의 502/503/504 · 그 밖의 상태 · 연결 끊김 · 종류를 모르는 예외 · 묶음 다시 읽기 실패 · 댓글 보유 확인 실패 · 보유 목록이 불완전 · 상한 | 다음 댓글 요청을 보내지 않고, 그 묶음까지 기록하고 멈춘다(`AUTH_REJECTED` · `RATE_LIMITED` · `CHANNEL_FAILED`) |
  | 시도 안 함(`NOT_ATTEMPTED`) | 요청 한도가 그 요청을 거절함 — 보내지 않음 | 댓글 기록을 쓰지 않는다(매퍼가 거절) |
  | 토큰 없음 | 인증 실패 | 아무것도 묻지 않고 아무것도 쓰지 않는다(`AUTH_FAILED`) |

  그 문의에 대한 증거가 없는 실패, 처음 보는 원인은 모두 계정 쪽이다 — 문의 탓은 그 문의에 대한 증거가 있을 때만. 재시도 간격의 연속 실패
  횟수는 문의 원인만 센다(다음 묶음에서 칸과 함께).

  **정정(같은 날).** 처음 구분은 댓글 읽기의 **모든** 예외를 「그 글의 읽기 실패」로 받아 다음 글을 계속 읽었다 — 401·403도, 연결 끊김도. 계정
  인증이 막혔는데 문의마다 실패가 쌓일 수 있는 길이었고, 위 문장(처음 보는 원인은 계정 쪽)도 이 경로에서는 거짓이었다. 이제 게시판 읽기
  클라이언트가 실패를 상태 값과 종류로 던지고(`Cafe24ReadFailedException`, `IllegalStateException`을 이어받아 문구와 기존 호출부는 그대로),
  관측기는 종류가 「그 글의 것」(404 · 500 · 해석 불가)일 때만 건너뛰고 계속한다. 401·403은 `AUTH_REJECTED`, 502/503/504는
  `CHANNEL_UNAVAILABLE`, 그 밖의 상태 · 종류 없는 예외는 `UNEXPECTED`로 — 셋 다 그 자리에서 댓글 요청을 멈추고, 이미 읽은 결과는 남긴다.
  정기 수집도 같은 관측기를 쓰므로, 정기 수집 중 댓글 읽기가 401·503을 받아도 그 뒤 댓글은 읽지 않고 「확인 못 함」이 된다(정기 수집의 다른
  결과는 그대로).
- **요청 상한 = 실제 HTTP 호출 수** (`Cafe24RequestBudget`): 토큰 갱신, 글 다시 읽기, 댓글 보유 확인, 댓글 읽기를 모두 센다. 보내기 전에 세므로 실패한
  호출도 센다. 토큰 갱신도 세기 위해 `Cafe24Authorizer`에 같은 계정 잠금을 쓰면서 토큰 요청만 넘겨받은 클라이언트로 보내는 오버로드를 더했다.
  묶음은 최악 비용(2 + 묶음 크기)이 남은 한도에 들어갈 때만 시작한다 — 한도가 묶음을 반으로 자르지 않는다. 잘렸다면(설계상 없어야 함) 그 묶음의
  댓글 결과는 쓰지 않는다. 직전 결과가 `NOT_CHECKED`인 문의에는 상한의 절반까지만 먼저 쓰고, 나머지는 다른 문의가 먼저 쓴 뒤 남는 것을 쓴다.
- ~~아직 없음: 재시도 간격, 하루 요청 예산~~ — 아래 「재시도 기록 · 하루 예산 · 실행 하나」(V185)에서 완료. 남은 것은 실행 시각 · 스케줄이다. → 스케줄도 연결했다(아래 「정기 수집 뒤 재확인 연결」, V186).
  (당시 문장: 지금 한도는 **실행 한 번**의 한도다 — 하루에 두 번 돌리면 각자 새 한도를 받고, 같은 계정의 실행이 겹치면 같은 글을 두 번 묻는다.)
  「하루 한 번 · 20요청」은 여전히 임시안이다.

**검증(재확인 실행부).** `Cafe24AnswerRecheckTest` 13건(H2) — 가짜 카페24 쇼핑몰(`FakeCafe24Board`: 글 · 답변 상태 · 댓글을 들고 URL대로 답하는
테스트용 가짜, 네트워크 없음)을 정기 수집이 먼저 읽어 문의를 만들고, 5주 뒤 판매자가 판매자센터에서 단 댓글 · 답변 상태를 재확인이 읽는다. 문의
상태는 SQL이나 저장소로 대신 넣지 않았다(승인된 할 일 · 등록 진행 중 · 스팸 표시 같은 제외 조건만 직접 만들었다). 다룬 것: 늦은 댓글 답변 → 할 일
닫힘(정기 수집으로는 안 닫힘을 먼저 확인) · 늦은 답변 상태 · 여전히 답 없음 · 글 다시 읽기 500 · 429 · 댓글 보유 확인 500 · 작성자 불명 · 글이 오지
않음 · 한도 소진(묻지 않은 문의는 기록 그대로) · 한도가 한 번도 못 물을 만큼 작음 · 실패가 계속되는 문의가 다른 문의 몫을 못 씀 · 대상 선정(최근 ·
승인 뒤 · 등록 진행 중 · 스팸 · 스레드 답글 · 이미 답변 · 다른 채널 · 다른 org) · 다른 판매 계정은 자기 쇼핑몰에 자기 문의만 묻는다. 지킴 장치를
하나씩 깨 보면 해당 테스트가 실패했다: 실패 몫 상한 제거 1 · 글 없음 기록 제거 1 · 창 조건 제거 1 · 할 일 단계 조건 제거 1 · 등록 진행 중 조건
제거 1 · 다시 읽기 실패 무기록 2 · 토큰 요청 미계수 3 · 실패 뒤 계속 진행 2 · 최악 비용 예약 제거 3. 매번 소스를 바이트 단위로 되돌렸다.
원인 구분을 더한 뒤 18건(추가: 댓글 보유 확인 실패는 계정 원인이고 실행을 멈춤 · 한 글의 댓글 읽기 500은 그 문의 원인이고 실행은 계속 · 댓글 읽기
사이의 429는 끊긴 문의 모두 계정 원인 · 토큰 실패는 아무것도 쓰지 않음 · 한도가 거절한 댓글 요청은 시도 안 함이고 저장되지 않음). 원인 쪽 지킴
장치 여덟 가지(모두 문의 탓 · 모두 계정 탓 · 묶음 실패를 문의 탓으로 · 글 없음을 계정 탓으로 · 계정 실패에도 계속 · 읽기 사이 429를 읽기 실패로 ·
거절을 읽기 실패로 · 매퍼가 시도 안 함을 저장)를 하나씩 깨면 해당 테스트가 실패했고, 최악 비용 예약 제거도 여전히 3건이 실패했다. 버리는
PostgreSQL 16(포트 55432, Flyway 182개 버전 · V184, 끝나고 삭제)에서 `Cafe24AnswerRecheckPostgresProofIT` 18건과 `Cafe24CommentCheckPostgresProofIT`
21건이 통과했고 남은 행은 0이다(원인 구분 전 13건도 먼저 통과). 기본 개발 DB와 QA DB에는 연결하지 않았다. 관련 묶음(커넥터 · 수집 · 문의 전체)
2,136건 통과, 실패 1건은 무관한 `GoalSmokePreflightTest`.

인증·권한 · 종류 모를 오류 정정 뒤(H2): `Cafe24AnswerRecheckTest` 29건 · `Cafe24CommentCheckTest` 40건 통과. 추가한 것: 댓글 읽기 401/403은
`AUTH_REJECTED`로 그 자리에서 멈추고 다음 댓글 요청 · 다음 묶음이 없으며 먼저 읽은 결과는 남음 · 502/503/504 · 400/418 · 연결 끊김도 계정 원인으로
멈춤 · 글 다시 읽기와 댓글 보유 확인의 401/403도 `AUTH_REJECTED` · 404/500/해석 불가 응답은 여전히 그 문의 원인이고 계속 읽음. 지킴 장치 아홉
가지(모든 실패를 문의 탓으로 · 인증을 문의 탓으로 · 종류 없는 예외를 문의 탓으로 · 계정 실패 뒤에도 계속 읽음 · 클라이언트가 상태를 버림 · 장애를 500으로
· 보유 확인 인증 미구분 · 다시 읽기 인증 미구분 · 댓글 인증 멈춤을 채널 실패로)를 하나씩 깨면 매번 2~16건이 실패했고, 소스는 바이트 단위로 되돌렸다.
관련 묶음(커넥터 · 수집 · 문의 · 주문 사실 울타리) 2,202건 중 실패 1건은 같은 무관한 `GoalSmokePreflightTest`. **PostgreSQL 재검증은 하지 못했다** —
Docker 서버가 10분 넘게 응답하지 않았고 재시작 뒤에도 같았다(Docker는 다시 껐다). 이 정정은 스키마 · 저장 경로를 바꾸지 않지만, 일회용
PostgreSQL에서 `Cafe24AnswerRecheckPostgresProofIT`(이제 29건)를 다시 돌리는 일이 남아 있다. → 같은 날 V185 단계와 함께 일회용
PostgreSQL에서 돌려 통과했다(아래 「검증(PostgreSQL)」).

**완료 — 재시도 기록 · 하루 예산 · 실행 하나 (2026-11-08, V185, 자동 실행은 여전히 없음).** 재확인 실행부가 실행과 실행 사이, 서버 재시작
사이에 기억해야 할 세 가지를 DB에 둔다(`InquiryRecheckLedger`, V185). 숫자는 모두 설정값이다(`Cafe24RecheckPolicy`,
`sellerops.inquiry.recheck.daily-request-limit` · `retry-after-days` · `run-lease-minutes`). 기본값 20요청 · 1·2·4·7일 · 10분은
`PROVISIONAL` — **파일럿 출발점이고 운영값이 아니다**(§10.3 · §10.5).

| 무엇 | 어디에 | 규칙 |
|---|---|---|
| 문의별 연속 실패 | `inquiries.answer_recheck_failures` · `answer_recheck_failed_at` | 그 문의 원인(`ARTICLE` — 404 · 500 · 해석 불가 · 작성자 불명 · 번호로 물었는데 안 옴) 실패만 +1. 확인 성공(답변 확인 · 댓글 없음 확인 · 답변 상태)이면 0. 계정 원인(429 · 401/403 · 장애 · 종류 모를 오류 · 묶음 다시 읽기 실패) · 한도가 막아 보내지 않은 것 · 닿지 못한 것은 **올리지도 되돌리지도 않는다** |
| 다음 시도 | 같은 두 칸에서 계산 | n번 연속 실패한 문의는 마지막 실패의 한국 날짜 + `retry-after-days[n-1]`일(목록 끝을 넘으면 마지막 값)부터 다시 묻는다. 그동안은 대상에서 빠지고(`Outcome.waiting`) 다른 문의가 그 몫을 쓴다. 날짜로 세므로 어제보다 조금 이른 시각에 돌아도 하루를 건너뛰지 않는다 |
| 계정별 하루 요청 수 | `inquiry_recheck_daily_usage` (계정 · 한국 날짜마다 한 행) | 요청을 **보내기 전에** 「`requests < 상한`이면 +1」 한 문장으로 확보한다. 확보하지 못하면 보내지 않는다. 그래서 실패한 요청도 세고, 둘이 동시에 마지막 하나를 가져갈 수 없다. 토큰 갱신 · 글 다시 읽기 · 댓글 보유 확인 · 댓글 읽기가 모두 거친다. 날짜는 요청하는 순간의 한국 날짜 — 자정을 넘긴 요청은 새날에서 쓴다 |
| 계정별 실행 하나 | `inquiry_recheck_run_lock` (계정마다 한 행) | 실행은 시작할 때 잡는다. 이미 잡혀 있으면 아무것도 보내지 않고 `BUSY`로 끝난다. 요청마다 유지 시간을 늘리고, 끝나면 자기 것만 지운다. 비정상 종료로 남은 행은 유지 시간이 지나면 다음 실행이 넘겨받는다. 유지 시간이 지나 넘겨받힌 실행은 다음 요청부터 보내지 않고 `BUSY`로 멈춘다. 다른 계정은 막지 않는다 |

- 실패 시각의 기준을 §10.1 표의 「마지막 시도 시각」이 아니라 별도 칸(`answer_recheck_failed_at`)으로 둔 이유: `comment_check_attempted_at`은
  계정 원인 실패에도 움직인다. 그 칸을 기준으로 쓰면 429가 난 날마다 기다림이 늘어난다. §10.1의 네 칸은 이렇게 채워졌다 — 마지막 성공 =
  `comment_checked_at`, 마지막 시도 = `comment_check_attempted_at`, 마지막 결과 = `comment_check`, 연속 실패 = `answer_recheck_failures`(+ 그 시각).
- 실패 횟수 두 칸은 엔티티 저장이 쓰지 못한다(읽기 전용 매핑). 한 문장짜리 갱신 쿼리 둘만 쓴다 — 오래된 엔티티를 저장해도 횟수를 덮지 않는다.
- 실행 결과에 `DAILY_LIMIT`(오늘 예산이 남은 대상을 남김)와 `BUSY`가 더해졌다. 같은 날 다시 부르면 남은 예산만 쓴다. 순서는 그대로
  「마지막 성공이 오래된 것부터」라, 앞 실행이 못 닿은 문의가 먼저 온다.
- ~~같은 날 두 번째 실행은 첫 실행이 이미 확인한 문의도 순서 뒤쪽에서 다시 물을 수 있다(예산 안에서). 「하루 한 문의 한 번」은 넣지 않았다 —
  필요하면 스케줄 묶음에서 정한다(§10.5).~~ → 스케줄 묶음에서 넣었다: 같은 한국 날짜에 확인에 성공한 문의는 다시 묻지 않는다(아래).
- 화면 · API · 기존 V184 칸의 뜻은 바꾸지 않았다. 기존 문의는 실패 0 · 시각 null로 시작한다(지어내지 않음).

**검증(V185 단계, H2).** `Cafe24AnswerRecheckTest` 42건(기존 29 + 13) · `Cafe24CommentCheckTest` 40건 통과, 세 번 연달아 돌려 같았다. 가짜 시계로 여러 날을
진행했다. 다룬 것:
- 그 문의 탓으로 계속 실패하는 문의는 0 · 1 · 3 · 7 · 14 · 21일째에만 묻고, 다른 두 문의는 23일 동안 매일 확인됨. 매일 한 시간씩 이르게 돌려도 같음.
  21일째에 읽히자 실패 0
- 간격 설정 `[3]`이면 0 · 3 · 6일째. 마지막 값이 반복됨
- 429 · 묶음 다시 읽기 503 · 댓글 401 · 연결 끊김 · 한도가 작아 닿지 못함은 실패 횟수와 시각을 바꾸지 않고, 429로 끊긴 문의는 기다리지 않음
- 번호로 물었는데 안 온 글도 그 문의 탓으로 셈
- 실패 뒤 늦은 답변을 찾으면 할 일이 닫히고 횟수가 0이 됨
- 하루 8요청: 첫 실행 8요청 후 `DAILY_LIMIT`. 새 실행기 · 새 원장(재시작)과 더 큰 한도를 준 호출 모두 0요청. 실패한 다시 읽기 · 실패한 토큰 갱신도 셈
- 한국 23:50에 다 쓴 뒤 00:10(UTC로는 같은 날)에 새 예산 8요청. 어제 못 닿은 문의부터
- 한 계정의 예산 소진이 다른 계정을 막지 않음
- 잡힌 계정은 0요청 `BUSY`, 다른 계정은 그동안 진행. 죽은 실행의 잠금은 유지 시간 1초 전까지 `BUSY`, 지나면 넘겨받음
- 유지 시간이 지나 넘겨받힌 실행은 진행 중인 읽기만 끝내고 더 보내지 않으며 새 잠금을 지우지 않음
- 실제 스레드 동시 실행: 첫 실행이 다시 읽기 안에 붙잡힌 동안 두 번째 실행은 `BUSY` · 0요청, 다른 계정은 끝남. 토큰 1 · 다시 읽기 1 · 댓글 읽기 3
  — 중복 조회 없음. 16스레드 × 10회가 20요청짜리 하루를 동시에 가져가면 정확히 20. 8스레드가 빈 계정을 동시에 잡으면 정확히 하나

이 테스트는 커밋된 행을 직접 지우고, `org_id` · `seller_account_id` 칸이 있는 모든 표를 다시 세어 남은 행이 0임을 확인한다.

지킴 장치 15가지를 하나씩 깨 보면 매번 1~6건이 실패했다. 소스는 바이트 단위로 되돌렸다.
- 계정 원인도 셈 · 계정 원인이 횟수를 지움 · 성공이 지우지 않음 · 글 없음을 안 셈
- 기다림 없음 · 시간 단위로 기다림 · 간격 설정 무시
- 하루 예산 미계수 · 하루를 UTC로 셈 · 상한 조건을 갱신 문장에서 뺌
- 잠금 없음 · 죽은 잠금 복구 없음 · 유지 실패 무시 · 남의 잠금 해제 · 끝나도 안 풂

`Inquiry` 엔티티에 칸이 더해졌으므로 백엔드 전체를 다시 돌렸다. 6,326건 중 실패 1건은 같은 무관한 `GoalSmokePreflightTest`이고, 188건은 opt-in이라
건너뛰었다(PostgreSQL 증명 IT 포함).

**검증(PostgreSQL, 2026-11-08).** 일회용 컨테이너(`postgres:16-alpine`, PostgreSQL 16.15, `127.0.0.1:55432`, 볼륨 없음)에
빈 스키마부터 Flyway V1…V185(183개 적용, 모두 성공)를 올리고 다음을 돌렸다. 기본 개발 DB · QA DB에는 연결하지 않았다.
- `Cafe24AnswerRecheckPostgresProofIT` 43건: H2의 42건 전부와 V185 칸 · 제약 · 외래 키 확인 1건. 위 목록의 여러 날 · 재시작 · 자정 · 잠금 경우와,
  실제 스레드 동시 실행(PostgreSQL 행 잠금으로 겹친 실행 `BUSY` · 중복 조회 없음 · 16스레드가 하루 20을 넘지 못함 · 8스레드 중 하나만 잡음)이 포함된다.
  V185 확인: 새 칸을 모르는 쓰기는 실패 0 · 시각 null로 시작, 횟수만 · 시각만 · 음수 횟수 · 음수 하루 합계는 거부, 없는 계정의 하루 행은 외래 키가 거부,
  계정을 지우면 하루 행 · 잠금 행이 함께 지워짐.
- `Cafe24CommentCheckPostgresProofIT` 21건(앞 단계에서 미룬 것).

세 번 연달아 돌려 세 번 모두 64건 통과(실패 · 오류 · 건너뜀 0). 끝난 뒤 하루 사용량 · 잠금 · 문의 · 계정 · 조직 표는 모두 0행이었다.
같은 날 앞 단계(오류 구분 정정)에서 미룬 PostgreSQL 재검증도 이것으로 끝났다.

외부 API 호출 · 모델 호출은 이 단계에 없다.

**완료 — 정기 수집 뒤 재확인 연결 (2026-11-08, V186, 자동 실행 기본 꺼짐).** 재확인을 정기 수집 끝에 붙였다. 숫자는 모두 임시값 그대로다
(하루 20요청 · 1·2·4·7일 · 잠금 10분 · 계정 대기 2·4·8시간, `Cafe24RecheckPolicy.PROVISIONAL`).

| 무엇 | 어떻게 |
|---|---|
| 언제 도나 | 정기 수집(`SyncScheduleRunner`)이 한 건을 끝낼 때마다 결과를 `ScheduledCollectionFollowUp`에 넘긴다. 재확인 후속(`Cafe24AnswerRecheckAfterCollection`)은 카페24 API · `INQUIRY` · 트리거 `SCHEDULED` · 상태 `SUCCESS` · 호출 제한 없음 · **그 수집의 댓글 확인이 계정 · 채널 쪽에서 끊기지 않았을 때만**(2026-11-09 보완, 아래) 같은 계정을 한 번 재확인한다. 수동 · 재시도 · 백필 실행 뒤, `PARTIAL` · `FAILED` · 429를 받은 수집 뒤, 아직 도는 실행을 돌려받은 경우에는 돌지 않는다 — 계정이 지금 문제를 겪는 중이면 요청을 더하지 않는다. 그래서 재확인은 늘 그 주기의 신규 수집이 끝난 뒤에 온다 |
| 켜는 법 | `sellerops.inquiry.recheck.auto-enabled=true`와 카페24 커넥터(`sellerops.connector.cafe24.enabled`)가 **둘 다** 켜져야 실행부 · 후속 빈이 생긴다(`Cafe24AnswerRecheckConfiguration`). 정기 수집 스케줄러도 켜져 있어야 한다. 기본은 모두 꺼짐 |
| 같은 날 다시 묻지 않음 | 같은 한국 날짜에 확인에 성공한 문의(`comment_checked_at`이 오늘)는 대상에서 빠진다(`Outcome.checkedToday`). 이 칸은 성공(답변 확인 · 댓글 없음 확인)에만 움직인다. 계정 장애 · 인증 거부 · 한도로 못 본 문의는 그대로라 같은 날에도 다시 대상이다. 그 문의 원인 실패는 V185 재시도 간격을 따른다 |
| 계정 대기 | V186 `inquiry_recheck_account_wait`. 재확인이 계정 원인(`RATE_LIMITED` · `AUTH_REJECTED` · `AUTH_FAILED` · `CHANNEL_FAILED`)으로 멈추면 그 계정은 정해진 시각까지 요청을 하나도 보내지 않는다(`ACCOUNT_WAIT`). 연달아 멈추면 2 → 4 → 8시간(마지막 값 반복). 실제 요청을 보내고 계정 원인 없이 끝난 실행이 지운다. 아무 요청도 보내지 않은 실행은 회복의 증거가 아니라 지우지 않는다. DB에 있으므로 재시작 뒤에도 남는다. 기록 · 연장 · 삭제는 **지금 잠금을 쥔 실행만** 한다 — 잠금 확인과 변경이 한 문장이다(2026-11-09 보완, 아래) |
| 예산 · 잠금 | V185 그대로. 오늘 예산을 다 쓴 것(`DAILY_LIMIT`)과 겹친 실행(`BUSY`)은 계정 문제가 아니므로 계정 대기를 만들지 않는다 |

- 주기: 정기 수집 주기(기본 60분)마다 불린다. 그러나 오늘 확인된 문의는 빠지고 하루 예산이 상한이라, 실제 요청은 계정당 하루 20을 넘지 않는다.
  §10.3의 「하루 한 번」을 「수집마다 부르되 오늘 성공한 것은 다시 묻지 않음」으로 구현했고, 하루 한 번만 부르는 문턱은 두지 않았다 — 계정 장애로
  못 본 문의를 대기 뒤 같은 날 다시 볼 수 있게 하기 위해서다.
- 재확인은 스케줄러 스레드에서 그 수집 바로 뒤에 동기로 돈다(계정당 하루 최대 20요청). 후속이 예외를 던져도 같은 묶음의 다른 수집은 계속된다.
- 붙지 않는 경로: 담당 업무(Responsibility Runtime)가 소유한 원천은 그 런타임이 수집하므로 이 후속이 불리지 않는다(§10.5).
- 화면 · API는 바꾸지 않았다.

**검증(스케줄 연결, 2026-11-08).** 가짜 카페24 쇼핑몰과 가짜 시계만 썼다. 실제 채널 · 모델 호출 없음, 기본 개발 DB · QA DB 연결 없음.
- H2 106건, 세 번 연달아 같은 결과: `Cafe24AnswerRecheckTest` 50(새 8) · `Cafe24AnswerRecheckConfigurationTest` 2 · `Cafe24ScheduledRecheckFlowTest` 1 ·
  `SyncScheduleRunnerTest` 13(새 2) · `Cafe24CommentCheckTest` 40.
- 다룬 것:
  - 진짜 스케줄러 → 진짜 수집 실행기 · 카페24 커넥터 → 재확인. 꺼져 있으면 정기 수집만으로는 늦은 답변이 닫히지 않음. 켜면 같은 주기의 신규 수집 요청이 모두 끝난 뒤
    재확인이 닫음. 같은 날 다음 수집 뒤에는 0요청. 실패한 수집 뒤에는 재확인 없음, 다음 깨끗한 수집에서 다시
  - 깨끗하지 않은 수집 8가지(`PARTIAL` · `FAILED` · 아직 도는 실행 · 429 · 수동 · 재시도 · 리뷰 · 다른 커넥터) 뒤에는 요청 0
  - 같은 한국 날짜: 11 · 15 · 23시 수집 뒤 0요청, 다음 날 다시. 한국 08:30(UTC로는 전날)과 10:00은 같은 날, 한국 23:50과 00:10(UTC로는 같은 날)은 다른 날
  - 401로 끊긴 두 문의는 성공 시각이 그대로라 대기 뒤 다시 물음, 그날 성공한 하나는 묻지 않음
  - 503 · 503 · 429가 이어지면 대기 2 · 4 · 8시간, 끝나기 1분 전(재시작 뒤)에는 요청 0, 한국 자정에 회복하면 대기가 지워지고 다음 장애는 다시 2시간
  - 요청 없이 끝난 실행은 대기를 지우지 않음. 예산을 다 쓴 날은 대기 없이 다음 날 못 닿은 문의부터
  - 실제 스레드: 같은 계정 수집 둘이 동시에 끝나면 한쪽은 `BUSY` · 0요청, 토큰 1 · 다시 읽기 1 · 댓글 3. 겹친 것은 계정 대기를 만들지 않음
  - 설정: 두 깃발이 다 켜져야만 빈이 생김(다섯 조합 확인). 배포 설정은 꺼짐이고 숫자는 `PROVISIONAL`과 같음
- 지킴 장치 18가지를 하나씩 깨면 매번 1~6건이 실패했다. 첫 판에서 셋이 통과해(UTC 날짜 · 요청 없이 대기 지움 · 효과 없는 변이) 테스트 2건을 더하고
  변이 하나를 고쳐 다시 돌렸다. 소스는 매번 바이트 단위로 되돌렸다.
- 백엔드 전체 6,348건: 실패 1건은 같은 무관한 `GoalSmokePreflightTest`, 197건은 opt-in이라 건너뜀.
- PostgreSQL: 새 일회용 컨테이너(PostgreSQL 16.15, `127.0.0.1:55432`, 볼륨 없음)에 Flyway V1…V186(모두 성공)을 올리고,
  `Cafe24AnswerRecheckPostgresProofIT` 52건(H2 50건 전부 + V185 · V186 칸 · 제약 · 외래 키 확인)과 `Cafe24CommentCheckPostgresProofIT` 21건을
  세 번 연달아 돌려 모두 통과했다. 끝난 뒤 계정 대기 · 하루 사용량 · 잠금 · 문의 · 계정 · 조직 표는 0행. 컨테이너는 지웠다.

**보완 — 운영 활성화 전에 막은 빈틈 둘 (2026-11-09, 자동 실행은 계속 꺼짐).** 코드 리뷰가 찾았다. 새 마이그레이션 없음, 화면 · API 그대로.

| 빈틈 | 무엇이 틀렸나 | 이제 |
|---|---|---|
| 정기 수집의 댓글 확인 오류 | 정기 수집 중 댓글 확인(발견 요청 · 댓글 읽기)이 401 · 403 · 429 · 503을 받아도 관측기는 그 문의를 「확인 못 함」으로 두고, 커넥터는 글 페이지를 보통대로 돌려준다. 글 수집이 성공하면 작업은 `SUCCESS` · 호출 제한 없음이라, 계정에 문제가 있는데도 재확인이 이어질 수 있었다 | 커넥터가 그 페이지에 계정 · 채널 쪽 중단을 적는다(`FetchPage.accountSideStop`, 관측기 사유 이름만). 실행기가 그것을 끝난 작업에 넘기고(`SyncJob.accountSideStop`, 저장 안 함), 재확인 후속은 이 값이 있으면 돌지 않는다. 수집한 글과 이미 얻은 확인 결과는 그대로 저장된다. **모르면 깨끗하지 않다**: 방금 끝난 실행이 돌려준 객체가 아니면(`accountSideKnown=false`, 예: DB에서 다시 읽은 작업) 재확인하지 않는다. 계정 쪽으로 세는 사유: 인증 거부 · 429 · 채널 응답 없음(502~504) · 발견 요청 실패 · 종류 모를 실패. 세지 않는 것: 그 문의 하나의 읽기 실패(404 · 500 · 읽을 수 없는 본문) · 작성자를 증명 못 함 · 읽기 상한 · 발견 목록 상한 · 보내지 않은 요청 |
| 잠금을 넘겨받은 뒤의 계정 대기 | 실행 끝 정리가 계정 대기를 기록 · 삭제할 때 지금 잠금 주인을 확인하지 않았다. 이전 실행이 멈춘 사이 잠금이 만료되어 새 실행이 넘겨받고 대기를 기록하면, 늦게 끝난 이전 실행이 그 대기를 지우거나 한 번 더 늘릴 수 있었다 | 대기를 여는 문장 · 늘리는 문장 · 지우는 문장이 모두 「이 잠금 주인이 아직 만료 전 잠금을 쥐고 있음」을 같은 문장 안에서 조건으로 건다(`InquiryRecheckAccountWaitRepository`). 잠금을 잃은 실행은 0행을 바꾸고 아무것도 쓰지 않으며, 로그에 「잠금이 다른 실행으로 넘어감」을 남긴다 |

**검증(보완, 2026-11-09).** 가짜 카페24 쇼핑몰과 가짜 시계만 썼다. 실제 채널 · 모델 호출 없음, 기본 개발 DB · QA DB 연결 없음.
- 새 테스트 6건(H2):
  - 진짜 스케줄러 → 진짜 실행기 · 커넥터 → 후속: 정기 수집의 댓글 읽기 401 · 429 · 503, 발견 요청 401 · 429 · 503 — 여섯 번 모두 작업은 `SUCCESS`,
    새 문의는 저장되고 「확인 못 함」, 재확인 요청 0, 재확인 하루 사용량 0. 이어서 한 문의의 댓글 읽기만 500인 수집 뒤에는 재확인이 돌아 늦은 답변을 닫음
  - 커넥터 페이지: 같은 여섯 경우에 페이지가 사유를 싣고 글은 그대로, 한 문의만의 500과 정상 읽기에서는 싣지 않음
  - 사유 분류: 계정 쪽 6종 · 아닌 것 5종, 여럿이면 가장 무거운 것
  - 잠금 인계: 이전 실행이 마지막 요청에서 멈춤 → 잠금 만료 → 새 실행이 넘겨받아 429로 대기 기록 → 이전 실행이 끝남. 정상으로 끝나도 대기를 지우지 못하고,
    401로 끝나도 한 번 더 늘리지 못한다(연속 1 · 새 실행의 대기 시각 · 마지막 사유 `RATE_LIMITED` 그대로)
  - 장부 문장: 잡은 적 없는 잠금 · 만료된 잠금 · 다른 실행이 쥔 잠금 · 다른 계정의 잠금으로는 열기 · 늘리기 · 지우기 모두 0, 지금 주인만 됨
  - 깨끗한 수집 판정 목록에 4가지를 더함(계정 쪽 사유 3가지 · 「모름」)
- H2 관련 18개 클래스 277건 통과.
- 지킴 장치 10가지를 하나씩 깨면 매번 1~3건이 실패했다(커넥터 · 실행기 · 후속 · 사유 분류 · 늘리기 · 지우기 · 열기 · 만료 조건). 소스는 매번 바이트 단위로 되돌렸다.
- PostgreSQL: 새 일회용 컨테이너(PostgreSQL 16.15, `127.0.0.1:55432`, 볼륨 없음)에 Flyway V1…V186을 올리고 `Cafe24AnswerRecheckPostgresProofIT` 57건
  (H2 55건 전부 + V185 · V186) · `Cafe24CommentCheckPostgresProofIT` 21건을 세 번 연달아 돌려 모두 통과. 잠금 조건 변이 4가지도 PostgreSQL에서 각각 1~2건
  실패(첫 판의 세 변이는 PostgreSQL이 문구 자체를 거부한 것이라 다시 짰다 — `docs/FINDINGS.md` §B125). 끝난 뒤 관련 표 0행, 컨테이너 지움.
- 백엔드 전체 6,359건: 실패 1건은 이전과 같은 무관한 `GoalSmokePreflightTest`(평가 저장소 파일 없음), 202건은 opt-in이라 건너뜀.
- 남은 한계: 잠금 조건은 각 서버의 시계로 잰다(잠금 자체와 같은 기준). 정기 수집 중 계정 쪽 중단은 재확인을 건너뛰게만 하고 재확인의 계정 대기를 만들지는 않는다 —
  다음 깨끗한 수집이 회복의 증거다. 담당 업무(Responsibility Runtime) 경로는 여전히 붙지 않는다(§10.5).

**검증.** 새 결과 구분 테스트 27건을 포함해 댓글 관측 · 클라이언트 · 수집 · 문의 저장 연계 · 리뷰 댓글 확인의 관련 테스트
130건 통과(실패·건너뜀 0, Java 17, 오프라인 Gradle). 전체 백엔드·프런트 테스트나 PostgreSQL 검증을 했다는 뜻은 아니다.
조회 상한 판정, 작성자 불명 처리, 미조회 기본 결과를 각각 잘못 바꾸면 해당 테스트가 실패했다(1 · 3 · 1건).
매번 소스를 바이트 단위로 복구했고, 복구한 코드에서 위 130건을 다시 통과했다. 문서 링크 끊김 0건.

**왜.** 늦게 답한 문의가 계속 미답변으로 남으면 판매자의 할 일 목록이 틀린다. 합성 테스트로 확인한 사실(`docs/FINDINGS.md` §B109,
`NaverLateAnswerReReadTest`): 네이버 정기 수집은 지난번 끝에서 이어 갈 뿐 지난 문의를 다시 묻지 않는다. 고객 문의는 등록한 날이 지나면
다시 읽히지 않는다. 상품 문의는 어느 날짜로 거르는지가 문서에 없다. 카페24는 최근 14일을 매번 다시 읽지만 그보다 오래 열린 문의는
마찬가지로 다시 묻지 않는다. 카페24 댓글 관측이 못 돈 경우는 「판매자 댓글 없음」과 같은 빈 결과가 된다. **최근 14일을 다시 읽는 것만으로는
오래 열린 문의가 풀리지 않는다** — 그래서 대상은 날짜 창이 아니라 **열린 문의**다.

### 10.1 재확인은 신규 수집과 다른 일이다

| | 신규 수집(지금) | 진행 중 재확인(이 설계) |
|---|---|---|
| 묻는 것 | 새로 들어온 문의 | 이미 가진 열린 문의가 채널에서 답변됐는가 |
| 대상 | 날짜 창 | 판매자가 아직 할 일로 가진 문의 — 나이와 무관 |
| 결과 | 행 추가 · 갱신 | 답변됨이면 기존 수집 경로로 반영 · 아니면 「확인함」 시각만 |
| 커서 | 이어 가기 | 쓰지 않음(신규 수집 커서를 움직이지 않는다) |

**대상.** 판매자 연결(seller account)이 있는 문의 중 상태가 `UNANSWERED`이고 할 일이 `OPEN` · `PROPOSED`인 것. 빼는 것: 제외(스팸 등)된
문의, 스레드 답글, 우리 등록이 확인 대기 중인 문의(등록 확인이 이미 다시 읽는다), 판매자가 승인한 뒤의 단계(`APPROVED` 이후 — 이미 있는 규칙대로
관측이 닫지 않는다).

**기록 — 문의마다 네 칸** (2026-11-07 보완):

| 칸 | 뜻 | 언제 바뀌나 |
|---|---|---|
| `answer_rechecked_at` | 마지막으로 **확인에 성공한** 때(`ANSWERED` · `UNANSWERED`가 나온 때) | 확인 성공 때만 |
| `answer_recheck_attempted_at` | 마지막으로 **시도한** 때 — 결과와 무관 | 그 문의를 대상으로 한 요청이 나갈 때마다 |
| `answer_recheck_result` | 마지막 결과 `ANSWERED` · `UNANSWERED` · `UNVERIFIABLE` | 시도할 때마다 |
| `answer_recheck_failures` | 연달아 `UNVERIFIABLE`이 나온 횟수 | 실패면 +1, 성공이면 0 |

`UNVERIFIABLE`(호출 제한 · 실패 · 조회 범위나 페이지 확인이 불완전 · 창 안에서 못 찾음)은 「확인함」으로 치지 않는다 —
`answer_rechecked_at`을 찍지 않는다. 그러나 시도 시각과 실패 횟수는 찍는다. 그래야 다음 규칙이 그 문의를 뒤로 보낼 수 있다.

**재시도 간격.** `UNVERIFIABLE`이 나온 문의는 다음 시도까지 기다린다: 연속 실패 1회 → 1일, 2회 → 2일, 3회 → 4일, 그 뒤로는
7일(상한). 다음 시도 가능 시각 = `answer_recheck_attempted_at` + 간격. 성공하면 간격이 없어진다(실패 횟수 0).
(카페24 구현, V185: 기준은 마지막 **그 문의 원인** 실패의 한국 날짜이고 간격은 설정값이다 — §10.0 「재시도 기록 · 하루 예산 · 실행 하나」.)
**계정 전체가 막힌 실패는 문의 탓이 아니다.** 호출 제한(429) · 인증 만료 · 채널 장애로 주기가 멈춘 경우에는 그 주기에 시도하지
못한 문의의 시도 시각 · 실패 횟수를 바꾸지 않는다. 문의 하나의 문제(창 안에서 못 찾음, 그 문의가 든 페이지를 끝까지 못 읽음)만 센다.

**다음 대상 선정 — 한 주기 안에서.**
1. 다음 시도 가능 시각이 지금보다 뒤인 문의는 뺀다.
2. 남은 문의를 `answer_rechecked_at`이 오래된 순(한 번도 확인에 성공하지 못한 것이 먼저, 같으면 수집한 순)으로 줄 세운다.
3. 한 주기에서 한 문의는 한 번만 시도한다. 실패해도 그 주기에 다시 넣지 않는다.
4. 요청 상한(§10.3)의 **절반까지만** 「직전 결과가 `UNVERIFIABLE`인 문의」에 쓴다. 나머지 절반은 직전에 성공했거나 처음인 문의 몫이다.
   한쪽이 남기면 다른 쪽이 쓴다.

이 넷이 함께 있어야 확인이 계속 실패하는 문의가 매번 맨 앞에서 예산을 다 쓰고 다른 문의의 확인을 막는 일이 없다. 상한에 걸려 못 한 것은
다음 주기에 이어서 — 나이로 버리지 않는다.

답변됨이면
지금의 수집 경로(`IngestionService` → `reconcileConnectorAnswered`)로 반영해 할 일을 닫는다. 닫는 규칙은 지금과 같다: 다시 열지 않고,
판매자가 이미 진행한 단계는 닫지 않으며, 부재로 닫지 않는다.

### 10.2 채널별 방법 (READ만)

- **네이버 고객 문의** — id로 읽는 API가 없으므로 열린 문의들의 등록일을 덮는 창을 `answered=true`로 조회한다(문서에 있는 매개변수, 지금 클라이언트는
  쓰지 않음). 돌아온 행만 답변된 것이므로 열린 문의가 많아도 요청이 적다. 창 길이 상한은 문서에 없다 — 첫 실제 읽기에서 확인할 항목.
- **네이버 상품 문의** — 같은 방법(`answered` 매개변수 있음). 다만 창 기준이 질문 날짜인지가 먼저 정해져야 창을 짤 수 있다 — 실제 읽기로 좁힐
  질문 1번.
- **네이버 — `answered=true` 결과에 없다는 것만으로는 미답변이 아니다.** 결과에 있으면 `ANSWERED`다. 없을 때 `UNANSWERED`로
  적으려면 아래가 **모두** 참이어야 한다. 하나라도 아니면 `UNVERIFIABLE`이다.
  1. 그 문의의 창 기준 날짜를 안다 — 고객 문의는 등록일(문서). 상품 문의는 기준이 정해지기 전까지 이 조건이 성립하지 않으므로,
     부재는 언제나 `UNVERIFIABLE`이다.
  2. 조회한 창이 그 날짜를 KST 하루 경계까지 포함해 덮는다.
  3. 그 창의 페이지를 마지막까지 읽었다 — 응답의 전체 건수(또는 마지막 페이지 표시)와 실제로 받은 행 수가 맞고, 중간 페이지에 실패 · 재시도
     소진 · 상한 중단이 없다.
  4. 응답이 그 계정의 것이다(연결 계정이 바뀌지 않았다).
  부재로 `UNANSWERED`가 되더라도 바뀌는 것은 확인 시각뿐이다. 할 일은 그대로 열려 있다. 그래도 조건을 지키는 이유는 「확인함」이 그 문의를
  다음 순서에서 뒤로 보내기 때문이다.
- **카페24** — 번호로 다시 읽기가 이미 있다: 글 상태는 `Cafe24BoardArticlesClient.fetchByArticleNumbers`, 댓글 답변은
  `Cafe24InquiryAnswerObserver.observeExact`(한 번에 25건). 14일 창 밖의 열린 문의만 대상(창 안은 정기 수집이 이미 다시 읽는다).

### 10.3 주기와 계정별 호출 상한 — **파일럿 임시안, 확정 운영값이 아니다**

아래 두 숫자(하루 한 번 · 계정당 20요청)는 파일럿에서 관찰을 시작하기 위한 출발점이다. 운영값은 파일럿 관찰(아래)을 보고 판매자 계정 운영
결정으로 정한다.

- 주기: 계정마다 하루 한 번, 정기 수집이 끝난 뒤. 판매자가 문의를 열어 답을 쓰기 시작할 때는 이미 있는 전송 직전 재확인(`PreSendCheck`)이 지킨다.
  구현(2026-11-08, §10.0): 깨끗하게 끝난 정기 수집마다 부르되, 그날 확인에 성공한 문의는 다시 묻지 않고 하루 예산이 상한이다. 따로 「하루 한 번만
  부른다」는 문턱은 두지 않았다 — 계정 장애로 못 본 문의를 같은 날 대기 뒤에 다시 볼 수 있게 하기 위해서다.
- 계정 대기(임시): 재확인이 계정 원인(429 · 401/403 · 토큰 없음 · 장애)으로 멈추면 그 계정은 **2시간**, 연달아 멈추면 **4시간**, 그 뒤로는
  **8시간** 동안 재확인 요청을 보내지 않는다(`account-wait-minutes`). 정기 수집이 끝날 때마다 같은 실패에 요청을 쓰지 않기 위해서다.
- 상한: 재확인은 계정당 하루 **20요청**(임시). **요청 수는 채널 API로 실제로 나간 HTTP 호출 수다** — 다음 페이지 요청, 재시도, 카페24 댓글
  확인 호출을 모두 센다. 문의 건수나 「조회 한 번」 같은 논리 단위로 세지 않는다. 네이버는 `answered=true` 창 조회 한 번이 여러 문의를 덮으므로 대부분 몇 요청으로 끝난다.
  카페24는 25건 묶음마다 글 다시 읽기 1요청 + 댓글 보유 확인 1요청 + 댓글이 있는 글마다 1요청이다(`observeExact`). 상한에 닿으면 남은 건수를 로그에 숫자로 남기고 다음 날 이어 간다.
- 신규 수집의 호출 제한과 겹치지 않게 재확인은 신규 수집이 끝난 뒤에만, 호출 제한(429)을 받으면 그 주기는 거기서 멈춘다.

**파일럿에서 관찰할 것 — 계정마다, 주기마다 한 줄.** 쓴 요청 수(HTTP 호출), 대상이었으나 상한 때문에 못 한 문의 수(미처리 건수),
결과별 건수(`ANSWERED` · `UNANSWERED` · `UNVERIFIABLE`), **가장 오래 확인하지 못한 기간**(열린 문의 중 마지막 확인 성공 —
없으면 수집한 때 — 부터 지금까지 가장 긴 것). 미처리 건수가 며칠 계속 0보다 크거나, 가장 오래 확인하지 못한 기간이 계속 늘면 숫자가 모자란
것이다. 반대로 매번 몇 요청만 쓰면 상한을 내릴 수 있다. 이 관찰 없이 숫자를 확정하지 않는다.

### 10.4 카페24 「판매자 답변 없음」과 「확인하지 못함」을 기록에서 나눈다

`Cafe24InquiryAnswerObserver`가 지금은 「답변 확인됨 → 시각」만 돌려준다. 이것을 글마다 세 가지로 넓힌다:

| 결과 | 뜻 | 언제 |
|---|---|---|
| `SHOP_ANSWERED` | 판매자 댓글이 있고 작성자가 증명됨 | 지금과 같음 |
| `NO_SHOP_COMMENT` | 확인했고 판매자 댓글이 없다 | 댓글 없음이 확인됨(댓글 보유 목록 조회가 상한에 안 걸렸고 그 글이 없음), 또는 댓글을 읽었는데 고객 댓글뿐 |
| `NOT_CHECKED` | 확인하지 못했다 | 호출 제한 · 읽기 실패 · 읽기 상한 초과 · 댓글 보유 목록이 상한(100)에 닿아 그 글이 없다는 것이 증명이 아님 · 작성자 불명 · 시각 해석 불가 |

기록: 문의마다 마지막 결과와 그 시각, 그리고 성공한 마지막 시각(`comment_check`, `comment_check_attempted_at`,
`comment_checked_at` — V184, §10.0). `NOT_CHECKED`는 다음 정기 수집 · 재확인에서 다시 시도할 대상이 된다.

**판매자 안내는 업무 판단에 영향을 줄 때만.** 목록 · 홈에는 보이지 않는다. 그 문의의 상세와, 답을 **승인하거나 등록하려는 순간**에 말한다 —
중복 답변을 막는 것이 목적이다. `NOT_CHECKED`와 「기록 없음」은 다른 문장이고, 과거 성공 시각이 있으면 함께 보인다. 막지는 않는다. 문구 ·
위치는 §10.0 「화면 안내」(2026-11-08, 제품 책임자 결정).

### 10.5 정해야 할 것

| 항목 | 분류 |
|---|---|
| 주기(하루 한 번)와 계정당 상한(20요청, HTTP 호출 기준) — 파일럿 임시안. 파일럿 관찰(미처리 건수 · 가장 오래 확인하지 못한 기간) 뒤 확정 | 제품 · 운영 결정 |
| 재시도 간격(1 · 2 · 4 · 7일)과 실패 몫(상한의 절반) — 임시안 | 제품 · 운영 결정(파일럿 관찰 뒤) |
| 네이버 상품 문의의 창 기준(질문 날짜 / 마지막 활동) · 고객 문의 창 길이 상한 · 수정하면 답변 시각이 바뀌는가 | 실제 읽기 필요(파일럿 채널 하나, 새 승인) |
| ~~`NOT_CHECKED` 안내를 승인 · 등록 순간에 둘지~~ — 결정됨(2026-11-08): 문의 상세와 승인 · 등록 바로 앞에 두고, 막지 않는다(§10.0) | 제품 결정 — 완료 |
| ~~새 칸 두 묶음은 새 마이그레이션~~ — `comment_check` 세 칸은 V184, 연속 실패 두 칸 · 하루 사용량 · 실행 잠금은 V185로 완료(§10.0) | 저장소에서 확인 가능 — 완료 |
| ~~같은 날 두 번째 실행이 첫 실행이 이미 확인한 문의를 다시 묻지 않게 할지(「하루 한 문의 한 번」)~~ — 결정됨(2026-11-08): 같은 한국 날짜에 확인에 성공한 문의는 다시 묻지 않는다. 계정 장애 · 인증 거부 · 한도로 못 본 문의는 성공이 아니다(§10.0) | 제품 결정 — 완료 |
| 계정 대기 길이(2 · 4 · 8시간, 마지막 값 반복) — 임시안 | 제품 · 운영 결정(파일럿 관찰 뒤) |
| 담당 업무(Responsibility Runtime)가 소유한 카페24 문의 원천 — 그 런타임이 수집하므로 정기 수집 뒤 재확인이 붙지 않는다. 그 경로에도 붙일지 | 제품 · 운영 결정 |
| 자동 실행을 켤지(`sellerops.inquiry.recheck.auto-enabled`) — 기본 꺼짐. 켜는 일은 운영 활성화다 | 제품 · 운영 결정 |
| 네이버 응답의 끝 표시(§10.2 부재 조건 3) — 고객 문의는 이미 `last` · `totalElements` · `totalPages`를 읽는다(`NaverInquiryPage`, `NaverCustomerInquiriesClient`). 상품 문의 응답은 구현 때 확인 | 저장소에서 확인 가능 — 구현 때 |
