# Operations Daily Loop v1 — 감사, 그리고 이은 세 자리

2026-10-11 · 브랜치 `feat/review-auto-check-v1` · `ed5b2da7` 기준 · 마이그레이션 **0** · 새 테이블 **0** ·
새 라우트·화면·메뉴 **0** · 모델 호출 **0** · 마켓플레이스 호출 **0** · WRITE 동작 변경 **0** · 승인 **0**
⇒ evidence 행 없음. 프론트엔드 변경은 **문구뿐**(§3).

이 문서가 소유하는 것: **하루의 고리가 실제로 한 바퀴 도는가**에 대한 감사와, 돌지 않던 세 자리를 이은 방식.
각 단계의 설계는 그 단계의 문서가 계속 소유한다 — `docs/responsibility_runtime_v1.md`(런타임·Case),
`docs/review_auto_check_v1.md`(자동 관찰), `docs/review_approval_path_v1.md`·
`docs/review_delivery_truth_spine_v1.md`(승인·실행·결과), `docs/seller_declared_operations_policy_v1.md`·
`docs/learning_outcome_loop_v1.md`(다음 판단에 들어가는 것). 여기서는 **그 사이**만 다룬다.

---

## §1 고리 — 단계별 감사 결과

| 단계 | 누가 소유하나 | 확인한 것 | 판정 |
|---|---|---|---|
| **자동 관찰** | `ReviewAutoCheckReconciler`(계정당 60분, 판매자 설정) · `ResponsibilityRunCoordinator`(2시간 창, 공식 API) | 두 lane 모두 실제로 읽고, 읽은 것은 canonical ingest를 지나 `ReviewSegmentIngestedEvent`까지 간다(반복 문제·개선 결과 갱신 포함) | **돈다** |
| **관찰 → Case** | `OperationsCaseProcessor.discover` | 템플릿 source(Cafe24 리뷰·문의, NAVER·쿠팡 문의)는 Case가 된다. **배포가 지명하지 않은 스토어의 NAVER 리뷰는 되지 않았다 — 즉 평범한 배포 전부** — §2 | **끊김 → 이음** |
| **관찰 중단 → 판매자** | `observeGaps` → `OBSERVATION_GAP` Case → Home 「확인하지 못한 것」 | 채널 source의 AUTH/미연결은 카드가 된다. **자동 확인 lane의 로그인 대기는 어디에도 없었다** — §3 | **끊김 → 이음** |
| **Case → 판매자 판단** | Case 화면(`/customer-operations/cases/:id`) · 큐 · Home | 문의는 `/inquiries/:id`, 리뷰는 `/reviews/reply/:id`로 간다 — 전용 상세 화면 없음, 판단은 그 일을 소유한 화면에서 | 돈다 |
| **판단 → 승인 → 실행** | `InquiryPublishService` · `ReviewReplyService`/`ReviewReplyExecutionService` | 승인 경계·단일 사용 mint·멱등 commandId·이중 게시 fence 전부 그대로이고 Case에서 도달할 수 없다 | 돈다 |
| **실행/결과 → Case** | `OperationsCaseReconciler` + 두 delivery truth reader | 판정은 옳다(문의 execution·리뷰 `sellerActed()`). **리뷰는 그 판정이 최대 2시간 늦게 일어났다** — §4 | **끊김 → 이음** |
| **결과 → memory/policy** | `answer_memory`(승인·검증 전송) · `seller_guidance`→Knowledge Spine · `review_issues` · `improvement_outcome` | 판매자가 Case에서 고친 처리·남긴 맥락·선언한 기준이 모두 저장되고, 저장된 곳을 읽는 쪽이 있다 | 돈다 |
| **memory/policy → 다음 Case** | `SellerPolicyOverlay`(`applyPolicy`) · `ProactiveReviewInvestigator` · `CaseInvestigationTools` | 판매자 기준은 규칙 다음·모델 앞에서 결정론으로 적용되고(`decidedBy=SELLER`), 반복 문제는 모델 없이 읽힌다 | 돈다 (단서: §7) |

**세 자리 모두 「설계가 없었다」가 아니라 「두 lane이 서로를 몰랐다」였다.** 그래서 고친 것은 규칙이 아니라
질문을 받는 쪽이고, 새 상태·새 테이블·새 화면은 하나도 생기지 않았다.

---

## §2 끊긴 곳 ① — 제품이 스스로 읽은 리뷰가 Case가 되지 않았다

### 측정

「이 스토어를 아무도 보지 않는 동안 읽어도 되는가」에 답하는 자리가 **둘**이고, 서로 다른 것을 묻는다.

| lane | 권한의 출처 | 코드 |
|---|---|---|
| responsibility 창 | **배포**가 조직과 계정을 지명 | `AsideMarketplaceAccess` ← `AsideMarketplaceTarget.resolve` |
| 자동 확인(standing) | **판매자의 제품 설정** `review_auto_check` | `ScheduledAsideJobService.dispatch`: 「The seller's setting is the whole of this lane's authorisation」 ← `resolveFor` |

제품은 2026-10-09에 그 결정을 판매자에게 옮겼다 — 연결을 마치면 행이 생기고, 60분마다 읽는다
(`review_auto_check_v1.md` §1). 그런데 `OperationsCaseProcessor.discoverDeviceReviews`는 **첫 줄에서
`resolve`를 물었다.** 즉 운영자가 env에 조직과 계정을 적어 두지 않은 평범한 배포에서는:

```
자동 확인 → 리뷰 READ → canonical ingest → 반복 문제 갱신 → …   그리고 Case 0
```

관찰은 돌고, 「확인할 일」은 비어 있다. 고리의 **첫 이음새**가 끊겨 있었고, 끊긴 쪽은 늘 제품이 스스로 읽은
쪽이었다.

### 이음 — 같은 권한을, 읽기를 일로 바꾸는 쪽에서도 묻는다

`unattendedStores()`가 **둘의 합집합**을 돌려준다: 배포가 지명한 스토어와, 자기 설정이 켜진 스토어.
두 번째 것도 반드시 `resolveFor`를 지나므로 **이 조직의 계정인지 · 이 recipe의 채널인지 · 읽을 화면이
있는지는 여전히 resolver가 정한다**. 설정 행은 계정만 말하고 기간·recipe·기기·경계는 말하지 않는다.
멈춘(`PAUSED_AUTH`) 행도 포함한다 — 벽은 판매자의 것이고, 그 행이 이미 배달한 읽기는 여전히 이 책임의 일이다.

**두 권한은 OR이고, 그게 요점이다.** 설정은 그 자체로 standing authorization이다 — 설정이 가리키는 스토어는
읽히고 그 리뷰는 일이 된다, 운영자가 어디에도 그 스토어를 적지 않아도. 그리고 먼저 있던 배포 허용목록은 그
앞의 **두 번째 승인 게이트가 아니다**: 배포가 지명한 스토어는 **설정 행이 아예 없어도** 전과 똑같이 일이 된다.
둘 중 어느 쪽도 필수가 아니고, 어느 쪽도 다른 쪽의 일을 빼앗지 못한다. 테스트 둘이 양쪽 방향을 고정하고
(`…SellersOwnSetting…` · `aStoreTheDeploymentNamedStillBecomesWorkWithNoSellerSetting`),
`OperationsCaseSafetyFenceTest.theStandingSettingIsNotGatedTwice`가 소스에서 같은 성질을 단정한다 — 이
클래스에는 `AsideMarketplaceAccess`가 **없고**, 설정 행을 쓰는 코드도 없다(만들거나 되살릴 수 있는 loop은
「누구의 스토어를 읽는가」를 loop이 정하는 것이고, Review Auto-Check v1이 모든 loop에서 바로 그것을 빼앗았다).

설정은 자료 유형으로 키가 잡히므로 **넓어진 것은 정확히 한 자리**다: 자동 확인은 오늘 REVIEW뿐이고, 그 행을
받는 스토어는 「기간을 지정해 읽을 수 있는 화면」을 가진 것뿐이다(`ReviewAutoCheckService#ensure` — Cafe24는
API로 오고, 쿠팡 화면은 기간을 고를 수 없어 행이 없다). 그래서 NAVER 문의 device lane과 쿠팡 리뷰 device
lane은 **오늘 가진 동작을 그대로 유지하고**, 설정이 문의까지 덮는 날 이 코드는 수정이 필요 없다.

**경계(hand-over boundary)도 그 lane에서 온다.** `firstSettledStandingRead`는 그 스토어에 대한
**SCHEDULED · OBSERVED · MATCH · BOUNDED** job의 `min(settled_at)`이다.

- **SCHEDULED만** — 운영자의 누름은 자기 화면을 보는 사람이지 그 스토어를 계속 보겠다는 약속이 아니다.
- **BOUNDED만** — PARTIAL 페이지는 뒤에 빈 곳이 있을 가능성을 배제하지 못하므로 그 뒤를 「새로 온 것」이라
  부를 수 없다(`firstSettledObservation`이 settled completeness만 쓰는 것과 같은 규율).
- **`settled_at`** — delivery는 job이 `CLAIMED`인 동안 기록되므로(`deliver`의 상태 가드) 이 시각은 그
  읽기가 저장한 행보다 **뒤**다. 경계가 된 읽기가 자기 자신이 가져온 리뷰로 Case를 열지 않는 이유다.
- responsibility 자신의 settled 관측이 있으면 **그것이 먼저**다. 없을 때만 standing lane이 답한다.

### 홍수 fence — 날짜 둘, 둘 다 울타리다

`createdAt > since`만으로는 안전하지 않다. 자동 확인 lane은 **빈 과거를 catch-up walk로 메우고**, 그
walk는 몇 달 전 리뷰를 **오늘 `created_at`으로** 저장한다. 그러면 전부 「지금 확인할 일」로 도착한다 —
2026-08-25 proactive bootstrap 감사가 측정한 「고객이 4,300일째 기다립니다」 바로 그 모양이다. 그리고 같은
감사가 `received_at` **단독**도 기각했다: 경계 뒤에 돌아간 backfill이 같은 backlog를 다른 문으로 붓는다.

그래서 `inquiryCandidates`·`reviewCandidates`가 **둘을 같은 instant에 대해** 묻는다 —
도착(`createdAt`)과 고객이 쓴 날(`receivedAt`), 둘 다 `ACQUISITION_REACH`(15일)로 이미 좁혀진 그 시각보다
뒤. 판매자에게서 무엇을 숨기는 규칙이 아니다: 문의 큐와 리뷰 원장은 그 행을 그대로 들고 있고, 이 둘이
정하는 것은 **예약된 책임이 그것에 대해 Case를 여는가**뿐이다.

채널당 경계가 **읽는 스토어들의 가장 이른 것**인 이유: 리뷰 행에는 판매 계정이 없다(채널만 있다). 보기
시작한 시점은 그중 첫 스토어가 시작된 시점이고, 나중에 추가된 스토어의 경계가 이미 보고 있던 것을 되돌릴
수는 없다. 「가장 이른 것」이 「전부」가 되지 않게 막는 것은 위의 나이 fence다.

---

## §3 끊긴 곳 ② — 멈춘 관찰이 운영 Home에 없었다

`PAUSED_AUTH`는 이 런타임이 말하는 모든 의미에서 **관측 공백**이다: 제품이 그 스토어를 계속 확인하겠다고
했고, 할 수 없고, 끝낼 수 있는 사람은 판매자뿐이다. 그런데 그 사실은 **계정 하나의 설정 패널에만** 있었고
고객 운영 Home은 아무 말도 하지 않았다. 큐가 그냥 조용해진다 — 멈춘 관찰이 절대 닮아서는 안 되는 모양이다.

`observeGaps`가 run source의 실패와 **같은 자리에서** 이 벽도 묻는다. 새 Case 종류도, 새 섹션도 없다: 기존
`OBSERVATION_GAP` · Home의 「확인하지 못한 것」 한 줄 · 그 줄의 링크 → `/connect/naver`(판매자가
「판매자센터 로그인」을 누르는 그 화면)이 그대로 쓰인다.

**다만 문장은 자기 것을 갖는다.** `SOURCE_AUTH_REQUIRED`의 「연결이 만료되어」는 **만료된 credential**에
대해 참이고 이 벽에 대해서는 거짓이다 — 이 벽은 **로그아웃된 브라우저**이고, 그 옆의 공식 API 연결은
멀쩡할 수 있다. 그 문장을 그대로 쓰면 판매자를 **깨지지도 않은 연결을 다시 승인하라고** 보내게 된다. 그래서
`CaseReason.SOURCE_SIGN_IN_REQUIRED` 하나를 더했다(마이그레이션 없음 — `reason`은 값 목록 CHECK가 없는
`varchar(48)`이다): 「판매자센터에 로그인이 필요해 확인하지 못했습니다.」, 추천 행동은 「… 판매자센터
로그인하기」. 프론트엔드도 같은 한 자리만 바뀐다 — `로그인 필요`(짧은 라벨) · `판매자센터에 로그인이
필요해`(문장) · `로그인`/`판매자센터 로그인하기`(링크). **연결 낱말은 그것이 참인 자리에 그대로 남는다**:
credential이 만료된 채널 source는 여전히 「연결 만료 · 재연결」이고, 테스트가 양쪽을 함께 고정한다.

세 수리에 세 문장이고, 고르는 규칙은 하나다: 채널 source가 실제로 auth로 실패했으면 그것(판매자가 owed
받는 source의 실패), 아니면 standing 벽, 아니면 미연결. 이유는 **episode가 열릴 때 한 번** 쓰이고 반복은
건드리지 않는다 — 저장된 코드는 쓰일 때의 뜻을 유지한다(`CaseReason.REVIEW_ROUTINE`의 규율).

- **사람이 풀 수 있는 벽만.** `PAUSED_DEVICE`는 다음 tick이 저절로 복구하므로(`review_auto_check_v1.md` §5)
  카드가 아니다 — 아무것도 요구하지 않고 스스로 사라지는 카드는 요구하는 카드까지 읽지 않게 만든다. 채널
  source에서 `SELLER_ACTIONABLE`이 AUTH/미연결 둘뿐인 것과 같은 규율이다.
- **두 번째 상태를 만들지 않는다.** 행이 상태이고 매 run 다시 읽는다. 판매자가 로그인해 lane이 멈춤을 놓는
  순간, **기존 복구 경로**가 카드를 닫는다(`RECOVERED` · `OBSERVED_AGAIN`).
- 벽이 계속 서 있으면 같은 카드가 갱신된다(`OBSERVED_AGAIN`), 두 번째 카드는 열리지 않는다.
- 범위의 한계를 적어 둔다: 공백은 **그 run이 관측한 계정**에 대해서만 말한다(`responsibility_run_source`가
  그 계정에 대해 한 행이라도 있는 경우). 그 조직이 그 채널에서 공식 API source를 하나도 갖지 않았다면 이
  카드도 없다 — run이 그 계정을 모르기 때문이고, 이 문서는 그 경우를 고치지 않았다.

---

## §4 끊긴 곳 ③ — 보냈는데 카드가 두 시간 더 묻는다

Review Delivery Truth Spine v1이 **규칙**을 고쳤다: `deriveReview`가 리뷰 답변 lane의 기록
(`sellerActed()`)을 세 번째 「판매자가 조치함」으로 읽는다. 고치지 못한 것은 **시점**이다 — 그 판정은
responsibility run 안에서만 돌았고, 창은 2시간 고정이다. 문의 lane은 `InquiryAnswerSettledEvent` 이후 이
공백이 없었다. 리뷰 lane에는 그 이벤트가 없었다.

```
ReviewReplySettledEvent(orgId, reviewId)        ← review/publish, 식별자 둘뿐
  ← ReviewReplyExecutionService.persist / readBack   (API POST · 가이드 관측 · read-back 승격)
  ← ReviewReplyOutcomeService.record                 (판매자 자기 보고)
  → ReviewReplyCaseConvergence → OperationsCaseReconciler.converge(org, REVIEW, reviewId)
```

- **두 번째 의견이 아니다.** run이 쓰는 `derive`/`apply` 그대로이므로, 움직여서는 안 되는 이벤트는 아무것도
  움직이지 않는다 — `REFUSED`(한 바이트도 안 나갔다) · `COMPOSER_FILLED`(제출은 판매자만) ·
  `SUBMISSION_ABORTED`(올리지 않기로 했다). 이 클래스는 그 이름들을 알지 못한다.
- **결론을 싣지 않는다.** 이벤트에는 lane·status·verification·본문이 없다. 「보냈는가」는
  `ReviewDeliveryTruthReader`의 낱말이고, 결론을 복사해 실으면 그 두 append-only 테이블과 어긋날 수 있는
  세 번째 사본이 된다.
- **전송에 영향을 줄 수 없다.** 행은 이미 커밋됐고 API lane에서는 공개 답변이 이미 떠난 뒤에 발행된다.
  publish는 발행 쪽에서도 listener 쪽에서도 삼켜진다 — 늦은 카드는, 이미 올린 답변에 500을 받고 손으로 다시
  올리는 것보다 작은 결함이다.
- **커밋 뒤에만 움직인다.** `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` +
  `@Transactional(REQUIRES_NEW)`. 두 writer는 자기 트랜잭션을 들지 않으므로 행은 이미 커밋된 상태에서
  발행되고 listener는 즉시 돈다 — **그래서 fallback이 필수다**: 트랜잭션 밖에서 발행된 이벤트는 fallback이
  없으면 **조용히 버려지고**, 오늘 동작하는 바로 그 경로에서 수렴이 사라진다. phase는 누군가 이 쓰기를
  트랜잭션으로 감싸는 날의 불변식이다 — 카드는 그것을 움직인 기록이 **durable해진 뒤에만** 움직이고,
  rollback은 카드를 그 자리에 그대로 둔다. 도착하지 않은 기록으로 정리된 카드는 아무것도 아닌 것에 대한
  결론이고, 두 시간 뒤의 run이 그것을 조용히 뒤집는다.
- 테스트가 **반증으로** 고정한다: 평범한 `@EventListener`로 바꾸면 rollback 트랜잭션에서 파생이 **불려
  버리고**(「불리지 않았다」와 「불렸지만 같이 롤백됐다」는 다른 성질이다 — 후자는 propagation 운에 달려
  있다), `fallbackExecution`을 빼면 트랜잭션 없는 발행이 버려져 카드가 움직이지 않는다. 둘 다 실제로 빨개지는
  것을 확인했다.
- 문의 레인의 listener도 **같은 두 애너테이션**을 받았다. 그쪽 publisher 역시 트랜잭션을 들지 않아 동작은
  그대로이고, 같은 불변식이 같은 문장으로 성립한다. 구조 테스트가 둘을 함께 고정한다.
- scheduler semantics 변화 0: run은 여전히 그 Case를 방문하고, 이미 정리된 것을 보고 지나간다.

---

## §5 바꾸지 않은 것

- **새 IA 0 · 새 workflow 플랫폼 0.** 새 라우트·화면·메뉴·설정 페이지가 없다. 세 자리 모두 기존 view 모양으로
  도착하고(공백 줄은 이미 `dataTypes`·`reason`·`to`를 그린다), 프론트엔드에서 바뀐 것은 **새 이유 하나의
  문구**뿐이다 — 새 컴포넌트도, 새 prop도, 새 상태도 없다.
- **마이그레이션 0 · 새 테이블 0 · 새 상태 0.** `ReviewAutoCheck`·`ScheduledAsideJob`은 **읽기만** 하고,
  Case 쪽은 기존 종류·기존 이유·기존 복구 경로를 쓴다.
- **Case의 status는 여전히 reconciler만 쓴다.** 어떤 새 경로도 승인하지 않고, 닫지 않고, 보내지 않는다.
- **`review.replyState`는 손대지 않았다** — 마켓플레이스 관측값이고 수집만 쓴다.
- **triage tier · `KnowledgeAuthority` · 판매자 기준 fence · answer memory writer 목록**: 호출자 0.
- **legacy proactive lane**: 인계 규칙(`takeOverLegacyCase`) 그대로.
- **자동 확인 lane의 권한·주기·기기 해결·일시정지 의미**: 한 줄도 바뀌지 않았다. 이 패키지는 그 lane에
  **아무것도 묻지 않고** 그 행과 그 job 기록을 읽는다.

## §6 검증

| | |
|---|---|
| backend 전체 | **669 suites · 5,332 tests · 0 failures · 60 skipped** |
| 새 backend 테스트 | `OperationsCaseProcessorTest` +4 · `ReviewReplyCaseConvergenceTest`(7) · `ReviewReplyExecutionServiceTest` +3 · `ReviewReplyOutcomeAnnouncementTest`(4) · `OperationsCaseSafetyFenceTest` +2 |
| frontend | **287 files · 3,921 tests · 0 failures** · `tsc --noEmit` 통과 (새 테스트 2 — 문구 한 자리) |
| agent-runtime · collector · contracts | diff 0 — 이 패키지는 그 어느 것도 건드리지 않았다 |

새 테스트가 고정하는 것:

- 배포가 아무도 지명하지 않은 상태에서 **판매자 설정만으로** 읽힌 리뷰가 Case가 된다(설정 전에는 0건).
- 경계 이전에 이미 저장돼 있던 리뷰, 그리고 **catch-up walk가 오늘 채워 넣은 작년 리뷰**는 Case가 아니다 —
  날짜 둘이 각각 하나씩 막는다.
- 로그인 대기는 공백 카드가 되고(`rr:gap;family=AUTH;types=REVIEW`, HIGH, HUMAN, `/connect/naver`), 같은 벽은
  카드를 **갱신**하고, 로그인하면 기존 복구 경로가 닫는다. `PAUSED_DEVICE`는 카드가 아니다.
- 검증된 Cafe24 전송 · 관측된 가이드 제출 · 판매자 자기 보고는 카드를 **즉시** 정리하고, 거절·composer
  채움·제출 중단은 카드를 그대로 둔다. 전송이 성공한 뒤 listener가 던져도 전송은 성공으로 남는다.
- 이벤트는 쓴 행에 대해 **한 번**만 발행된다(replay는 발행 0).
- 카드는 그것을 움직인 기록이 **커밋된 뒤에만** 움직인다: rollback된 트랜잭션에서는 파생이 **불리지도 않고**
  (`converge` 호출 0) 카드도 그대로이며, 같은 두 쓰기가 커밋되면 다음 창을 기다리지 않고 정리된다. 트랜잭션
  **없이** 발행해도 정리된다(두 writer가 실제로 그렇게 발행한다). 두 애너테이션을 각각 떼면 각각 다른
  단언이 빨개지는 것을 확인했다.
- 설정과 허용목록은 **OR**이다: 설정만으로도, 허용목록만으로도 일이 된다 — 그리고 소스에는 그 두 번째
  게이트가 없다.
- 로그인 벽은 **자기 문장**을 쓰고(「판매자센터에 로그인이 필요해」 · 「로그인 필요」 · 링크 「로그인」),
  만료된 credential은 여전히 「연결 만료 · 재연결」이다.

## §7 고치지 않고 보고하는 것

1. **문의 레인의 판매자 기준은 여전히 비어 있다.** 고객 질문에서 닫힌 problem 서명을 뽑는 것이 없다
   (`seller_declared_operations_policy_v1.md` §6·§10). 제품 소유자 결정이고, 발명하지 않았다.
2. **`/api/operations-policies`의 list·options·history는 호출자가 없다.** 「내가 정한 기준 전체」 화면은
   FE/IA freeze(A7)를 건드린다 — 그 문서의 열린 항목 그대로 둔다.
3. **Home의 「관찰」 패널은 standing lane의 마지막 읽기를 열거하지 않는다.** 그 패널은
   `responsibility_run_source`만 읽는다. 행동이 필요한 절반(멈춤)은 §3이 닫았고, 「언제 마지막으로 읽었나」를
   그 표에 한 줄 더 넣는 것은 view 필드와 프론트엔드 변경이며, 이 패키지는 프론트엔드 diff 0을 지켰다.
4. ~~공백 줄의 문구가 「연결 만료」다.~~ **제품 소유자 지시로 §3에서 닫았다**(2026-10-11) —
   `SOURCE_SIGN_IN_REQUIRED`와 그 문장. 연결 낱말은 그것이 참인 자리에 남아 있다.
5. **개선 결과(`[o]` 줄)는 조사 경로에서만 쓰인다.** Case 조사는 기본값 OFF이므로, 그 배포에서는 결과가
   다음 Case의 근거로 들어가지 않는다. capability 게이트대로의 동작이고 결함이 아니다 —
   `learning_outcome_loop_v1.md` §4가 그 경로를 소유한다.
6. **standing 읽기가 계속 PARTIAL이면 경계가 생기지 않는다**(Case 0건). fail closed이고 responsibility
   lane과 같은 규율이다 — 2026-10-11 라이브에서 `MATCH BOUNDED`가 관측됐으므로 도달 가능한 상태다.
7. **라이브 실행 0.** 이 패키지는 마켓플레이스를 호출하지 않았고 승인을 쓰지 않았다. 고리가 한 바퀴 도는
   것을 **실제 판매자 스토어에서** 보는 것은 `docs/review_auto_check_live_proof_v1.md` §6의 남은 한 칸
   (②번 복구 경로)과 같은 시팅에서 할 일이다.
