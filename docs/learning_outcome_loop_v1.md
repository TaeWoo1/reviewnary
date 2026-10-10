# Learning & Outcome Loop v1 — 한 일과 그 결과가 다음 판단에 들어간다

**날짜** 2026-10-10 · **브랜치** `feat/review-auto-check-v1` · **상태** 구현 완료, 전체 테스트 통과

이 문서가 소유하는 것: 교정이 offline 평가 집합이 되는 길, 개선이 적용되고 관찰되고 결과가 되는 길, 그리고 그
결과가 다음 Case 조사의 근거로 되돌아오는 길. 기존 문서를 덮어쓰지 않는다 — `docs/opportunity_engine_v1.md`,
`docs/repeated_issue_v1.md`, `docs/slices/production-triage-feedback-draft-v1.md`가 각자의 설계를 계속 소유하고,
여기는 그 셋 사이의 **끊긴 지점과 그것을 이은 방식**만 적는다.

---

## 1. 감사 — 무엇이 끊겨 있었나

세 곳에서 끊겨 있었고, 세 곳 모두 **설계는 완성되어 있고 문이 없었다.**

### A. 교정은 평가 집합이 될 수 없었다 — 코드는 있고 호출자가 없었다

`TriageFeedbackService.disposition` · `freezeSnapshot` · `freezeSilverSnapshot`은 feedback spine이 들어올 때
작성되고 문서화되고 단위 테스트까지 되어 있었다. 그리고 **`main/`에 호출자가 하나도 없었다** — controller 없음,
runner 없음, schedule 없음. 그 결과:

- 판매자가 남긴 모든 교정은 **disposition 되지 않은 채** 쌓여 있었고,
- 스냅샷은 **한 번도 잘린 적이 없었고**,
- `snapshot_version` 컬럼 셋은 **테스트 코드만** 찍고 있었고,
- 잘린 집합을 **읽어 낼 길이 없었다** — 즉 「offline learning corpus」에 corpus가 없었다.

추가로 세 가지가 말해질 수 없었다: 스냅샷이 존재한다는 사실(member row의 stamp 외에 흔적이 없었다),
버전 문자열이 이미 쓰였다는 사실(`freezeSnapshot(org, "v1")`을 두 번 부르면 두 컷이 한 이름으로 **조용히
합쳐졌다**), 그리고 §7.4의 「silver는 correction 스냅샷에 병합될 수 없다」.

### B. 적용과 결과 — 두 원장이 서로를 몰랐다

```
improvement_opportunity        ACCEPTED / DISMISSED   ← 제안에 대한 판매자의 결정
review_issues.lifecycle_state  OBSERVING → ACTING → VERIFYING → RESOLVED   ← 문제에 대한 판매자의 기록
```

채택은 어떤 이슈도 움직이지 않았고, 이슈를 움직여도 어떤 제안도 언급되지 않았다. 그리고 **적용은 어디에도
기록되지 않았다**: `OpportunityCard`가 브라우저에서 `createOrgKnowledge` / `createProductKnowledgeSource`를
직접 불렀고, opportunity row는 그 사실을 배우지 못했다. 「채택했다」와 「실제로 했다」가 한 단어였다.

결과도 측정되지 않았다. `IssueChangeKind.IMPROVED`는 이미 있지만 **오늘을 기준으로 미끄러지는** 창
(최근 4주 vs 그 앞 4주)이고, 「지금 줄어들고 있나」에 답한다. 「9월 12일에 한 일이 효과가 있었나」에는 답할 수
없다 — 해결됨이 도착할 때(조치 후 4주 조용)쯤에는 그 조치를 재야 할 baseline이 창 밖으로 미끄러져 나가 있다.

### C. 조사에는 결정만 가고 결과는 가지 않았다

`CaseInvestigationTools.getPastSellerDecisions`는 REPLY_APPROVED · REVIEW_TRIAGED · TRIAGE_CORRECTED를
건네고 **결과는 하나도** 건네지 않았다. `getRelatedIssues`는 제목과 근거 수만 — 생명주기도, 조치도, 결과도 없었다.
접착 리뷰를 보는 조사는 이 회사가 8월에 접착에 손을 댔고 불만이 5분의 1로 줄었다는 것을, 혹은 늘었다는 것을
알 수 없었다.

---

## 2. 패키지 A — Correction Learning Corpus (offline, flag-gated)

**문 하나와 manifest 하나.** 모든 stamp는 여전히 `TriageFeedbackService`를 지나간다 — stamper는 한 곳이다.

| 자산 | 역할 |
|---|---|
| `V130__triage_feedback_snapshot.sql` | 컷의 manifest. append-only(trigger), `(org, version)` unique **양쪽 kind 공통** |
| `SnapshotKind` | `CORRECTION` / `SILVER`. `MIXED`도, 둘을 한 번에 자르는 길도 없다 (§7.4) |
| `TriageCorpusService` | disposition → cut → read. 버전 재사용 거부, 빈 컷 거부, withheld 명시 |
| `TriageCorpusRow` | **privacy boundary.** `reviewIdFingerprint` + 닫힌 어휘뿐 |
| `TriageCorpusDocument` | manifest + rows + **`usage`** — §3의 세 허용과 세 금지가 파일 안에 실려 나간다 |
| `TriageCorpusController` | `/api/triage-corpus`, `sellerops.triage-corpus.enabled` 없으면 라우트 자체가 없다 |
| `SellerFeedbackCorpusFenceTest` | classifier · prompt · 표시되는 tier · gold set에 닿을 수 없음을 **소스에서** |

### 왜 row에 리뷰 본문이 없는가

이것은 판단이 아니라 **복사**다. `contracts/review-eval/naver/v2/labels.json`이 이미 그 경계를 그어 두었다 —
«ONLY the review-id fingerprint and the operator's judgment … Never the body, the raw 리뷰글번호, the rating, the
date, the body length, the stratum, the product, or any seller identity. The harness re-derives rating, length and
stratum from the local database at evaluation time.» 본문을 실은 corpus는 **같은 내용에 대한 두 번째, 더 느슨한
경계**이고, 새는 쪽은 느슨한 쪽이다. `SellerFeedbackCorpusFenceTest`가 record component 이름으로 이것을 고정한다.

### 누가 disposition 하는가 — 이미 정해져 있던 결정, 그리고 그것을 플래그로 표현한 이유

`CorrectionDispositionKind`가 spine이 들어올 때 답해 두었다: «Assigned by a person holding the rubric. That is a
cost, and it is the cost of the guarantee.» 판매자가 자기 교정을 `CLASSIFIER_ERROR`로 읽는 것은 disposition이
막으려고 존재하는 바로 그 drift다 — 한 org의 취향이 global classifier의 정확도 정의가 되는 것. 이 제품에는 그것을
표현할 role 컬럼이 없다(모든 계정이 `OWNER`). 그래서 **플래그**다: 라우트는 rubric holder가 운영하는 곳에만
존재하고 다른 어디에도 없다. 새 IA도, 판매자 화면도 0.

### 의도적으로 좁힌 것

**SILVER 컷에는 document가 없다.** 행동은 human-QA 큐의 순위를 매기고 drift audit을 유발할 수 있고, label도
측정도 아니다(§7.1). 내보낼 평가 집합을 주는 것은 약한 증거를 강한 증거와 똑같이 보이게 만드는 일 —
§7.4가 금지한 「단일 병합 파일」을 endpoint 하나씩 도달하는 방식이다. `corpus(silverVersion)`은 그 문장으로
거부한다.

---

## 3. 패키지 B — Improvement Outcome (적용, 그리고 그 뒤 4주)

### 적용은 결정 쪽이다

`OpportunityStatus.APPLIED` · `OpportunityEvent.APPLIED` · `improvement_opportunity.applied_at/by/ref/ref_kind`.
`OpportunityEvent`의 javadoc이 그은 선 — «these are decisions, not outcomes» — 을 그대로 지킨다: 적용은 판매자가
FAQ를 **썼다**는 행위이고, 불만이 줄었는지는 아무 말도 하지 않는다. 그 답은 4주 뒤에야 존재하고, 존재할 때는
`improvement_outcome`이며 여기 event가 아니다.

`AppliedArtifact`는 셋이고 그 구분이 bookkeeping이 아니다. `ORG_KNOWLEDGE` / `PRODUCT_KNOWLEDGE`는 SellerOps가
**가리킬 수 있는 변화**(판매자 자기 서가에 서 있는 행)이고, `SELLER_DECLARED`는 **판매자의 말**이다. 전자만
문제의 조치로 기록된다 — `isRecordedChange()`가 그것을 한 곳에서 말한다.

### 결과는 판정이 아니라 row다 — anchor가 전부다

적용의 순간에 **얼었다**: 날짜, 창, 그 앞 4주의 근거 수, 그리고 측정 자체가 의미를 갖게 하는 것 —
**그 기간에 리뷰가 몇 건 들어왔는지**. V131의 trigger가 `applied_on` · `baseline_*` · `observe_days` ·
`scope` · `product_id`의 UPDATE를 거부한다. 사후에 편집된 baseline은 편집한 사람이 원한 어떤 수든 될 수 있고
하류의 누구도 알 수 없다.

### 세 거부가 어떤 판정보다 먼저 온다

`ImprovementOutcomeRules` — **새 threshold는 0개**다. 모든 수가 `ReviewIssueThresholds`의 것이고, 추가된 것은
규칙이 아니라 **anchor**다(오늘 앞의 창이 아니라, 적용한 날에 끝난 창).

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 창 기간에 리뷰 0건 | `INCONCLUSIVE` / `NO_COVERAGE` — 「불만 0건」과 「리뷰 0건」은 같은 수다 |
| 2 | 리뷰가 이전 비율의 `IMPROVE_MAX_RATIO` 미만 | `INCONCLUSIVE` / `COVERAGE_DROPPED` — 근거 60% 감소는 리뷰 60% 감소로 이미 설명된다 |
| 3 | baseline이 주당 `IMPROVE_MIN_BASELINE_WEEKLY` 미만 | `INCONCLUSIVE` / `BASELINE_TOO_SMALL` |
| 4 | 뒤 ≤ 앞 × `IMPROVE_MAX_RATIO` | `IMPROVED` / `EVIDENCE_DOWN` |
| 5 | 뒤 ≥ 앞 × `SURGE_RATIO` | `WORSENED` / `EVIDENCE_UP` |
| 6 | 그 사이 | `UNCHANGED` / `EVIDENCE_FLAT` |

세 거부 모두 **수를 그대로 보고한다**. 「판단 보류」 혼자는 측정을 잃어버린 제품으로 읽히고, 「판단 보류 · 적용 전
4주 11건 → 뒤 4주 0건 · 그 기간에 들어온 리뷰가 없어 비교할 수 없습니다」는 재고 나서 과장을 거부한 제품으로
읽힌다. 거부가 둘 중 더 값진 산출이므로 수를 받는 쪽이 거부다.

`UNCHANGED`의 띠(0.40 ~ 2.0)가 넓은 것은 의도다. 30% 이동을 결과라고 부르는 측정은 **모든** 조치에 대해 결과를
만들어 낸다.

### 생명주기 — 한 번의 전이, 좁은 확장

`IssueLifecycleState.sellerMayRecordRemediation()` = OBSERVING · NEEDS_REVIEW · ACTING → `VERIFYING`,
actor `OPERATOR`, reason `IMPROVEMENT_APPLIED`.

`sellerMayStartActing`을 지나 `markRemediated`를 다시 부르는 길은 한 번의 누름을 두 번으로 만들고, 같은 순간에
조치 중과 개선 확인 중을 기록에 찍는다 — 일이 같은 초에 시작되고 끝났다고 적은 기록은 두 질문 어느 것에도
답하지 않는다. **`systemMayTransitionTo`는 손대지 않았다**: 자동 패스는 여전히 일이 끝났다고 선언할 수 없고,
`RESOLVED`는 여전히 `VERIFYING`에서 조용한 몇 주를 거쳐서만 닿는다. 2026-09-13 결정이 OBSERVING을
`sellerMayStartActing`에 넣은 것과 같은 성질의 확장이다 — **사람이 말할 수 있는 것**이 넓어졌고 시스템이
결론 내릴 수 있는 것은 그대로다.

### 언제 읽는가

`ImprovementOutcomeReadListener` — `ReviewSegmentIngestedEvent`에, AFTER_COMMIT · REQUIRES_NEW · best-effort.
`ReviewIssueImportRefreshListener`와 **같은 event에 나란히** 걸린 이유 둘: `reviewissue`는 `opportunity`를 알 수
없고(의존이 한 방향이다), 그리고 둘이 독립적으로 실패해야 한다. 새 scheduler 0. 창을 읽을 수 있게 만드는 것은
날짜가 아니라 **리뷰가 들어왔다는 사실**이고, 이것이 그 순간이다. 손으로 부르는 길은
`POST /api/opportunities/outcomes/read` 하나이고 멱등이다.

창은 **한 번만** 읽는다. 뒤에 다시 계산하면 같은 이름 아래 다른 측정이 되고, 그 다음은 또 다르다.

---

## 4. 패키지 C — 결과가 다음 조사의 근거가 된다

`CaseInvestigationTools.getPastOutcomes(productId)` → `[o]` 줄. `[d]` 줄이 판매자가 **무엇을 결정하는지**를
말하고, `[o]` 줄이 **그래서 어떻게 됐는지**를 말한다 — 조사에 이 판매자의 습관을 가르치는 것과 여기서 무엇이
통했는지를 가르치는 것의 차이다.

```
[i1] 반복 문제 「접착 탈락」: 근거 18건 (이 리뷰도 근거) · 판매자 상태: 개선 확인 중
[o1] 이 회사가 「접착 탈락」에 한 조치(상품 상세·안내 보완) 2026-09-01 뒤 결과: 근거 줄었습니다
     — 적용 전 4주 12건 → 뒤 4주 2건 (같은 문제가 적게 들어왔습니다)
```

- **settled만, 상한 3개, 최신 순.** 아직 확인 중인 창은 아무 말도 하지 않고, 그것을 건네면 「아직 모릅니다」가
  소견으로 인용될 수 있다.
- **ORG 조치는 모든 상품에, PRODUCT 조치는 자기 상품에만.** `SellerPolicyOverlay.applies`와 같은 no-widening.
- **인과를 말하는 낱말이 없다.** `OutcomeVerdict`의 어휘가 근거에 대한 것이고(근거 줄었습니다, 해결했습니다가
  아니다), `ImprovementOutcomeRulesTest`가 그 금지를 고정한다.
- 프롬프트 **v5 규칙 11**은 정보보다 **절제**가 더 많다: 효과가 있었다고 단정하지 말 것, 「판단 보류」를 약한
  긍정으로 읽지 말 것, 「근거 늘었습니다」면 같은 조치를 다시 권하기 전에 판매자 판단이 필요하다는 것, 그리고
  판매자 상태가 조치 중·개선 확인 중이면 이미 하고 있는 일을 새로 시작하라고 권하지 말 것.

버전: `case-investigation-prompt/v5` · `case-tools/v3` · `case-evidence/v3`.

---

## 5. 금지된 것, 그리고 그것을 지키는 것

| 금지 | 어떻게 지키는가 |
|---|---|
| online learning | `SellerFeedbackCorpusFenceTest` — corpus 패키지는 classifier · prompt · 표시되는 mark · `labels.json`에 닿을 수 없다 |
| Seller Policy 자동 생성 | `ImprovementOutcomeFenceTest` — `opportunity` 패키지 어디에도 `SellerOperationsPolicy`가 없다 |
| generic rule 자동 변경 | 같은 fence — `ReviewTriageRules` · `ReviewTriageTier` · `KnowledgeAuthority` · `setReplyState` 전부 부재 |
| marketplace WRITE 확대 | `OpportunitySafetyFenceTest`(기존) — 마이그레이션 외 WRITE 경로 변화 0 |
| 적용이 결론이 되는 것 | 같은 fence — `apply`는 `RESOLVED` · `setLifecycleState` · `setDismissed`에 닿을 수 없다 |
| 새 workflow 플랫폼 / 새 IA | 새 페이지·라우트·메뉴 0. 기존 개선 기회 카드와 반복 문제 화면만 |

`OpportunitySafetyFenceTest`의 save 울타리는 **세 번째 writer를 이름으로** 넓혔다(`decisions` · `trail` ·
`outcomes`). 한 store씩 이름을 적는 것이 이것을 count가 아니라 gate로 만드는 것이다.

---

## 6. 검증

| | |
|---|---|
| backend | **663 suites · 5,269 tests · 0 failures · 58 skipped** |
| frontend | **287 files · 3,919 tests · 0 failures** · `tsc --noEmit` 통과 |
| 새 backend 테스트 | `SellerFeedbackCorpusFenceTest`(5) · `TriageFeedbackCorpusIT`(6) · `ImprovementOutcomeRulesTest`(6) · `ImprovementOutcomeLoopIT`(8) · `ImprovementOutcomeFenceTest`(3) · `CaseInvestigationOutcomeEvidenceTest`(4) |
| 새 frontend 테스트 | `OpportunityCard.test.tsx` +5 · `IssueOutcomes.test.tsx`(4) |
| 마이그레이션 | V130 · V131을 **실제 Postgres 15** scratch DB에 적용하고 제약 23개를 개별로 확인한 뒤 drop |

Postgres에서 개별 확인한 것: snapshot kind / row_count / `(org,version)` 유일성(양쪽 kind 공통) / append-only
update·delete 거부 · `APPLIED` 상태와 날짜의 불가분 / ref 없는 ref_kind 거부 / trail event 어휘 / outcome scope
짝 / settled와 evaluated_at의 동치 / verdict 어휘 / application당 outcome 1개 / 판정 쓰기 허용 / anchor 6컬럼
freeze.

테스트 프로파일은 H2 + entity DDL이므로 **trigger와 check constraint는 테스트에서 실행되지 않는다** — 그래서
Postgres 확인이 별도 단계이고, fence test는 migration 텍스트에서 trigger의 존재를 고정한다.

---

## 7. 보고 — 바꾸지 않고 보고하는 것

### 7.1 제품에 남아 있는 결함 하나 (이번에 만든 것이 아니다)

`FAQ_SUPPLEMENT`를 상품 서가에 저장하면 `KnowledgeMentionCheck`가 그 aspect를 언급한다고 답하기 시작하고,
`OpportunityRules.guidanceKindOf`가 같은 이슈에서 `PRODUCT_GUIDE_SUPPLEMENT`를 유도한다. 그러면 저장했던
`FAQ_SUPPLEMENT` row는 **다시 join되지 않고**, 카드는 다른 kind · 검토 전으로 돌아온다. **이 동작은 오늘도
있었다** — 채택 배지가 사라지는 형태로. 패키지 B가 그것을 더 눈에 띄게 만든다(적용이 이제 의미 있는 기록이므로).

고치지 않은 이유: `list`의 의미를 바꾸는 일이고 Home의 `preparedDrafts` 집계까지 닿는다. 대신 **기록이 사라질 수
없는 곳에** 두었다 — `GET /api/opportunities/outcomes?issueId=`는 제안이 아니라 **문제**를 주소로 읽으므로
derivation과 무관하다. 카드의 kind 뒤집힘 자체는 제품 결정이다.

### 7.2 열려 있는 항목이 닫혔다고 적지 않았다

`docs/slices/production-triage-feedback-draft-v1.md` §6의 「who performs disposition」은 그 문서의 열린 항목으로
그대로 두었다. 이번 작업은 **운영 가능하게** 만들었을 뿐이고(rubric holder가 운영하는 곳에만 존재하는 플래그),
누가 그 사람인지는 제품 소유자의 결정이다. canonical 문서를 조용히 다시 쓰지 않는다.

### 7.3 Inquiry lane은 비어 있다

`improvement_outcome`의 key는 review issue이고, 문의에서는 닫힌 문제 서명을 뽑는 것이 없다. `getPastOutcomes`는
상품을 통해 문의 조사에도 닿지만(같은 상품의 ORG·PRODUCT 조치), 문의 자체가 만들어 내는 개선 기회는 없다.
`SellerPolicyOverlay`가 같은 이유로 review-only인 것과 같다.

### 7.4 silver corpus는 내보내지지 않는다

§2 마지막. 되돌리기는 `TriageCorpusService.corpus`의 kind 검사 한 줄이지만, 그 줄을 지우는 것이 §7.4를 어기는
방식이다.
