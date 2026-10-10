# Review Delivery Truth Spine v1 — 리뷰 답변이 어디까지 갔는지, 한 곳에서 말한다

2026-10-10 · `0cd78a74` 기준 · 마켓플레이스 호출 **0** · WRITE 동작 변경 **0** · 모델 호출 **0** ·
마이그레이션 **1** (`V128`, nullable 컬럼 1개 + 부분 인덱스 1개) · 승인 **0** ⇒ evidence 행 없음.

이 문서는 새 기능이 아니다. **이미 쓰이고 있던 두 테이블을 읽는 쪽이 없어서 끊겨 있던 연결만** 닫는다.
Inquiry 레인의 `AnswerDeliveryTruthReader` 패턴을 그대로 따른다.

---

## §1 무엇이 끊겨 있었나

리뷰 답변의 「무슨 일이 일어났는가」는 **서로를 모르는 두 append-only 테이블**에 나뉘어 있었다.

| 테이블 | 무엇을 기록하나 | verification 상한 |
|---|---|---|
| `review_reply_outcome` (V20) | **판매자가** 가이드 제출 지점에서 「내가 올렸다」고 보고한 것 | `UNVERIFIED` 영구 고정 |
| `review_reply_execution` (V84) | **reviewnary가** 한 것 — Cafe24 POST와 해시 read-back, composer 채우기, collector가 본 제출 | API 레인에서 `VERIFIED` 도달 가능 |

그런데 하위 소비자는 **전부 V20만** 읽었다.

- `ReviewDecisionWorkspaceService.log` — 결정 로그 종류 6개에 execution이 없음
- `ReviewReplyAdapter.reportedVersions` — 「등록됨」 판정을 판매자 자기보고로만
- `OperationsCaseReconciler.deriveReview` — `replyState`(수집만 쓰는 마켓플레이스 관측값)와 triage 결정만
- `answer_memory` — `origin_review_id`가 없어서 리뷰 답변은 `strength`를 가질 수 없었음

결과: **reviewnary가 직접 올리고 해시로 확인까지 한 Cafe24 답변이, 제품의 어느 화면에도 「보냈다」로
나타나지 않았다.** 판매자는 다음 수집이 돌 때까지 「확인 필요」 카드를 계속 봤다.

## §2 정본 — `ReviewDeliveryTruthReader`

`review/publish/ReviewDeliveryTruthReader`가 두 테이블을 **읽는 시점에** 합친다. 각 테이블의 최신 행이
그 절반의 현재 상태이고(둘 다 append-only), 필드 단위로 세대를 섞지 않는다 — 그러면 두 행 중 어느 것도
하지 않은 진술이 만들어진다.

**세 번째 행은 만들지 않는다.** Inquiry 레인이 `inquiry_execution` + `inquiry_verification`을 합친 결과를
저장하지 않는 것과 같은 이유다. 이미 append-only인 두 사실의 세 번째 복사본은 **어긋나는 쪽이 된다.**

`ReviewDeliveryTruth`가 답하는 질문은 **딱 두 개**이고, 둘의 차이가 이 패키지의 전부다.

### `sellerActed()` — 승인한 답변이 채널로 **떠났는가**

진행처럼 보이지만 아닌 세 상태를 이 레인의 어휘로 가려낸다.

| 상태 | `sellerActed()` | 왜 |
|---|---|---|
| API `POSTED` | ✅ | 채널이 받았다 |
| API `DELIVERY_UNKNOWN` | ✅ | 도착했을 수 있고, 재시도는 하지 않았다 |
| `SELLER_SUBMISSION_OBSERVED` | ✅ | collector가 판매자의 제출을 봤다 |
| `SUBMISSION_OBSERVED_CONTENT_UNVERIFIED` | ✅ | 이후 채널 읽기에 답변이 있다 |
| `OPERATOR_REPORTED_SUBMITTED` | ✅ | 판매자가 올렸다고 기록했다 |
| `REFUSED` | ❌ | 게이트가 막았거나 채널이 거부했다. **한 바이트도 안 나갔다** |
| `COMPOSER_FILLED` | ❌ | 답변창에 들어가 있을 뿐. 제출은 판매자만 누른다 |
| `SUBMISSION_ABORTED` | ❌ | 올리지 않기로 했다. 리뷰는 큐에 **그대로 남아야 한다** |

### `verifiedDelivery()` — 채널이 **승인한 그 문장을** 들고 있는가

`ReviewExecutionVerification.memoryEligible()`에 **위임한다**(재진술하지 않는다). `VERIFIED` 하나만 참이다.
Cafe24 댓글은 다시 읽어 승인 초안과 해시를 비교할 수 있고, NAVER 답글은 **구조적으로 불가능하다**
— export에 답글 본문이 없다. 가이드 레인의 천장은 「답변이 생겼다, 내용은 모른다」다.

## §3 세 소비자가 정본을 쓴다

### OperationsCase — 세 번째 「판매자가 조치함」

`deriveReview`가 `replyState == ANSWERED`와 triage 결정에 이어 `sellerActed()`를 본다.

- **`review.replyState`는 건드리지 않는다.** 그 컬럼은 마켓플레이스 관측값이고 수집(`IngestionService`)만
  쓴다. execution은 reviewnary 자신의 행위다. 한쪽이 다른 쪽을 덮어쓰게 하면 **둘이 어긋나는 것을 알아챌
  수단이 사라진다.**
- `CaseResolution`은 `SELLER_ACTED` 그대로. 이 어휘에는 sent·executed·verified에 해당하는 단어가 **여전히
  없고**, 채널이 무엇을 들고 있는지는 이벤트에 인용된 토큰(`;lane=…;status=…;verification=…`)에만 있다 —
  Inquiry 레인이 execution 토큰을 인용하는 방식과 동일하다.

### Decision log — `REPLY_EXECUTION`

`review_reply_execution`을 읽는 7번째 trail. `from`은 `ReviewExecutionStatus`, `to`는
`ReviewExecutionVerification`이고, 확인할 것이 없던 행(`REFUSED`)은 `from`이 null이고 상태가 `to`에 온다
— `to`만 보는 독자가 거부를 전송으로 오독할 수 없다. operator 보고는 위에서 직접 읽으므로 execution 행만
넣는다(한 행위가 두 줄로 보이면 안 된다).

**안전 문장이 조건부가 됐다.** 「마켓플레이스에는 아무것도 전송되지 않습니다」는 결정만 기록하던 화면에서
참이었다. API 실행을 보여주는 로그에는 **거짓**이므로 `decisionLogDisclosure(entries)`가 기록을 보고
고른다 — 채널 capability 플래그가 아니라 **이 리뷰에 실제로 일어난 일**로 판단한다.

### Answer Memory — 승인은 `USER_APPROVED`, 검증된 전송만 `EXECUTOR_SENT_VERIFIED`

`review/memory/ReviewAnswerMemoryHook`이 Inquiry 레인의 훅과 **같은 두 행위, 같은 두 강도**를 쓴다.
판매자가 양쪽 화면에서 하는 일이 같기 때문이다.

| 행위 | 강도 | `origin_ref` | 쓰는 곳 |
|---|---|---|---|
| 승인 | `USER_APPROVED` | `review-approved:<review>:<version>` | `ReviewReplyService.decideApproval` |
| 해시 read-back 성공 | `EXECUTOR_SENT_VERIFIED` | `review-verified:<review>:<version>` | `ReviewReplyExecutionService.executeCafe24` |

- **`VERIFIED` 하나만 memory가 된다.** 가이드 레인의 판매자 자기보고는 기록되고 신뢰되고 표시되지만
  memory가 될 수 없다 — 판매자가 composer에서 고친 문장과 구별할 방법이 없고, memory는 **회사가 무슨 말을
  했는가에 대한 주장**이다. 제품이 가진 가장 강한 강도로 미검증 문장을 선례로 만들면 안 된다.
- **`RULE` 초안은 기억하지 않는다.** 회사 템플릿을 그 리뷰에 재생한 것이고, 승인은 그 리뷰를 결정하지만
  회사가 그 주제를 어떻게 답하는지는 말하지 않는다 — `ReviewReplyAdapter.isAnswer`에 위임한다(검색 경로와
  쓰기 경로가 같은 판단을 두 군데서 하면 어긋난다).
- **고객 문장은 저장되지 않는다.** 리뷰 본문은 topic signature를 만들 때 `VocPreviewSanitizer`로 마스킹해
  읽고 버린다. Inquiry 훅과 동일.
- **`InquiryAnswerAdapter`는 `origin_review_id != null` 행을 건너뛴다.** 안 그러면 리뷰 답변이
  「과거 문의 답변」으로 제목이 붙고, `ReviewReplyAdapter`가 이미 읽고 있으므로 **한 답변이 두 source
  type으로 인용된다** — 검색이 실제보다 근거가 튼튼해 보이기 시작하는 방식.

## §4 의도적으로 하지 않은 것

- **`review.replyState` 쓰기** — §3 참조. 마켓플레이스 관측값이다.
- **마켓플레이스 WRITE 동작 변경** — 게이트, 멱등성 키, 이중 게시 fence, Cafe24 adapter, 가이드 mint 수명,
  승인 계약 전부 그대로. execution 서비스에 추가된 것은 기록 후에 호출되는 best-effort 훅 하나뿐이고,
  전송은 그 시점에 이미 끝나 있다.
- **`V128` 백필** — 이 마이그레이션 이전의 승인·검증 전송은 memory를 쓰지 않는 화면이 기록한 것이다. 지금
  행을 만들면 아무도 기록하지 않은 행위에 `created_at`과 강도를 **발명하는** 것이 된다. 그 답변들의 검색
  경로는 그대로다(`ReviewReplyAdapter`가 승인에서 읽는다) — 강도를 가진 데이터셋만 다음 승인부터 채워진다.
- **`origin_review_id` FK** — Inquiry 쪽 두 origin 컬럼과 같은 이유로 FK가 아니다. memory는 **회사가 한
  말**이고, 그 말을 촉발한 행이 새 id로 재수집되거나 삭제된 뒤에도 참이다.
- **`AnswerMemoryWriteFenceTest`의 writer 확대** — fence가 **고의적 확대가 되게** 만들었다. 보호 대상은
  「writer가 둘」이 아니라 「모든 writer가 사람이 실제로 한 일을 기록한다」이고, 네 번째 항목도 어떤 인간
  행위를 기억하는지 한 문장으로 설명해야 `ALLOWED`에 들어간다.

## §5 검증

| 항목 | 결과 |
|---|---|
| backend 전체 | **5,204 tests · 0 failures** (655 suites, 58 skipped) |
| frontend 전체 | **3,902 tests · 0 failures** (286 files) |
| 신규 `ReviewDeliveryTruthSpineTest` | 병합 3개 · 조치 판정 5개(9개 verification 전수 포함) · 배치 2개 · memory 4개 |
| `V128` | 실제 Postgres 15에 적용 → 컬럼·부분 인덱스·comment 확인, **재실행 멱등** 확인, scratch DB 삭제 |

라이브 실행은 없다. 마켓플레이스 호출 0, 승인 0 ⇒ `docs/evidence/INDEX.md` 행 없음.
