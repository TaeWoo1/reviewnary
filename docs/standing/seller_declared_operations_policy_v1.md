# Seller-declared Operations Policy v1 — 판매자가 정한 처리 기준이 다음 판단에 들어간다

2026-10-10 · `2a9436e9` 기준 · 마이그레이션 **1** (`V129`) · 모델 호출 **0** · 마켓플레이스 호출 **0** ·
WRITE 동작 변경 **0** · 승인 **0** ⇒ evidence 행 없음.

제품에서 **판매자가 타이핑한 것이 판정의 결론을 바꾸는 첫 자산**이다. 그래서 아래의 모든 컬럼은 필드이기 전에
fence다.

---

## §1 이것이 아닌 것

**correction / guidance / memory의 승격이 아니다.** 세 저장소는 의미가 그대로다 —
`seller_guidance`는 「다음에도 참고」(판단이 **보여질** context, 규칙 아님), `review_triage_corrections`는
리뷰 하나에 대한 판매자의 말, `answer_memory`는 회사가 과거에 한 말. 정책은 그 어느 것도 읽지 않고, **읽을 수
있는 협력자도 request 필드도 없다**. `SellerOperationsPolicyFenceTest`가 소스 트리에서 그 부재를 단정한다
(`AnswerMemoryWriteFenceTest`와 같은 도구).

**triage 규칙이 아니다.** `ReviewTriageRules.tier`와 SQL 쌍둥이 `ReviewRepository.TRIAGE_TIER_RANK`는
손대지 않았다 — 정책은 리뷰를 확인 필요/지켜보기/참고 사이로 옮길 수 없고, 큐의 정렬·카운트도,
`review.reply_state`도 건드릴 수 없다. `contracts/review-eval/naver/v1/RUBRIC.md` §5의 go/no-go는 무관하다.

**근거 우선순위 규칙이 아니다.** `KnowledgeAuthority` rank는 그대로다. 반대 방향에서 이미 금지돼 있던 것이기도
하다 — `AnswerStyleSafetyFloor.CURRENT_EVIDENCE_PRIORITY`가 근거 순서를 뒤집는 판매자 지시를 거부하고,
`OperationsPolicyFence`가 같은 이유로 여기서도 거부한다.

**바꾸는 것은 한 필드다:** `proactive_case.recommended_action_type` — **판매자에게 여전히 오는 일**을 어떻게
처리할지.

## §2 key — 기존 어휘 그대로

`aspect` × `problem`, 즉 issue memory가 이미 돌고 있는 닫힌 `IssueVocabulary`이고, `signature_key`는
**`review_issues.signature_key`와 같은 문자열**(`IssueSignature.signatureKey()`, 예 `배송:지연`)이다. 파생이고 두
번째 identity가 아니다.

그래서 「같은 문제」가 정책 화면과 반복-문제 화면에서 같은 것을 뜻하고, case ↔ policy 매칭이 **ingest마다 돌는
추출이 이미 써 둔 행에 대한 색인 조회**가 된다. 어휘 밖의 `aspect`/`problem`은 저장되지 않고 **거부**된다 —
추출기가 만들 수 없는 problem으로 키를 잡은 규칙은 영원히 아무것도 매칭하지 않으면서 「무시되는 규칙」으로
읽힌다.

## §3 fence — 다섯 개, 넷은 구조적

| # | 속성 | 어떻게 지키나 |
|---|---|---|
| 1 | **HUMAN_APPROVAL** | `RecommendedActionType`의 AUTO 두 값(`NO_ACTION`·`MONITOR_REPEAT_ISSUE`)을 쓰기 시점에 거부. `authority()`에서 **파생**하므로 enum에 새 AUTO 값이 생기면 그날부터 지배된다 |
| 2 | **NO_EXECUTION** | 구조적 — 패키지에 approval·execution·mint·Cafe24 협력자가 **하나도 없다** |
| 3 | **NO_MONEY_WITHOUT_A_PERSON** | `REFUND_OR_COMPENSATION`·`CANCEL_OR_EXCHANGE`는 정책의 action이 될 수 있다(추천은 실행이 아니다). 둘 다 HUMAN이고 카드는 계속 대기한다. 돈이 일정표에 따라 움직이는 조합은 (1)이 도달 불가로 만든다 |
| 4 | **NO_TRIAGE_OVERRIDE** | 구조적 — `ReviewTriageRules`·`TriageDisplayDecision`·`TRIAGE_TIER_RANK`·`setReplyState` 호출자 0 |
| 5 | **NO_EVIDENCE_PRIORITY** | 구조적 — `KnowledgeAuthority`·`KnowledgeSpineService` 호출자 0, 정책에 authority 필드 자체가 없음 |

**넷이 구조적이고 하나만 검사인 것은 의도다.** 협력자의 부재로 지켜지는 속성은 새 분기로 되돌릴 수 없고,
`OperationsPolicyFence.refuse`는 정당한 필드(`action`)가 유일하게 위반할 수 있는 속성 하나를 위해 있다.

**이것이 이번 패키지의 유일한 의도적 축소다.** 「앞으로 같은 문제는 지켜보기만」은 실재하는 운영 요구인데
AUTO action을 허용하면 그게 된다 — 그리고 그건 정확히 *look을 제거하는* 규칙이다(이 런타임의 모든 파생 신호가
지키는 규율: `OperationsCaseRules` — *"can add a look, never remove one"*). 1★ 심각 불만이 조용히 큐에서
사라지는 경로를 standing rule로 열 수 없다고 판단했다. 판매자가 반복 문제를 그만 보고 싶을 때의 경로는 **이미
있다** — issue lifecycle과 opportunity dismissal, 둘 다 객체별이고 둘 다 되돌릴 수 있다.
**제품 소유자가 뒤집고 싶다면 `OperationsPolicyFence.refuse` 한 줄이다.**

## §4 scope — 조용한 확대 금지

```
scope ∈ {ORG, PRODUCT}        판매자가 **말한다**. productId에서 유도하지 않는다.
ck_..._scope_shape            PRODUCT ⇒ product_id not null, ORG ⇒ product_id null
```

`seller_guidance`는 case의 상품에 operator 이름이 있었는지로 scope를 **유도**한다
(`CaseKnowledgeService:398`) — 그래서 「이 상품만」을 고른 판매자가 이름 없는 상품에서 **회사 전체 행**을 받고
아무 통보도 못 받았다. 여기서는 scope가 판매자에게서 오고, bind할 수 없는 PRODUCT 요청은 **400으로 거부**한다 —
ORG 행으로 바꾸지 않는다. 세 경우 모두 거부되고 **행이 남지 않는다**: 거부가 행을 남기면 그게 다른 이름의 확대다.

- 상품 미지정 → `이 상품에만 적용하려면 상품을 지정해 주세요.`
- operator 이름 없는 상품 → `상품 이름이 확인되는 상품을 선택해 주세요.`
- 다른 org의 상품 → 거부

읽기 쪽도 같은 절반을 지킨다: PRODUCT 규칙은 **자기 상품만** 매칭하고, 상품 없는 리뷰는 PRODUCT 규칙을
전혀 매칭하지 않는다(`KnowledgeSpineScope`와 같은 fence).

## §5 versioned, 그리고 은퇴

`version`은 **action이나 note가 실제로 바뀔 때만** 오른다 — `org_knowledge_sources.version`과 같은 규율:
지난달 case를 그때의 판(revision) 기준으로 되읽어야 하고, no-op 저장에 버전이 움직이면 그게 불가능해진다.
바뀌지 않은 재선언은 **아무것도 쓰지 않고 어떤 case도 건드리지 않는다**.

은퇴는 삭제가 아니다(`KnowledgeDocumentService.setActive`가 가진 수명주기, `seller_guidance`가 못 가진 것).
은퇴한 규칙은 새 case를 결정하지 않고, 결정했던 case에 대해서는 계속 답할 수 있다. partial unique index가
(scope, key)를 풀어 주므로 다른 규칙을 새로 세울 수 있다.

trail은 append-only이고 **DB 트리거로 강제**된다(`operations_case_event`와 같은 모양) — V99가 이름 붙인 패턴의
세 번째 사례이고 네 번째 패턴이 아니다.

## §6 overlay — RULE 다음, 모델 앞

```
CaseDecider = { RULE, SELLER, AGENT }        ← V129가 세 번째 값을 추가
```

`OperationsCaseProcessor.applyPolicy`가 **정책이 case에 닿는 유일한 지점**이고, 규칙이 넘긴 뒤 모델에 묻기 전에
호출된다. 따라서:

- **규칙과 모순될 수 없다** — 별점이 정리한 case(`AUTO_RESOLVED`/`MONITORING`)는 이 줄 전에 return한다
- **fence를 약화할 수 없다** — disposition은 `NEEDS_DECISION`, authority는 `HUMAN`으로 위에서 정해지고 여기서
  **쓰이지 않는다**. fence가 AUTO action을 쓰기 시점에 거부하므로 그걸 원할 수 있는 저장된 정책이 없고,
  이 메서드에는 낮출 코드가 없다. 구조 테스트가 메서드 본문에 `AUTO_RESOLVED`·`setStatus`·`setClosedAt`·
  `setDisposition`·`RequiredAuthority.AUTO`가 없음을 단정한다
- **handling만 바꾼다** — 한 필드

**모델을 묻지 않는다.** 그게 결정론 overlay의 실익이다: 판매자가 이미 정한 처리를 위해 vendor call을 사지 않는다.
대신 `confidence`·`evidenceCount`를 빌려오지도 않는다 — 조사하지 않은 case의 confidence는 측정 없는 숫자다.

`POLICY_APPLIED` 이벤트가 policy id·revision·scope·매칭된 key를 metadata로 남기므로 「왜 이렇게 추천됐나」가
규칙이 두 번 개정된 뒤에도 답해진다.

**리뷰 전용이다.** key가 리뷰 issue memory의 것이고 문의에는 그런 key가 없다 — 고객 질문에서 닫힌 problem
서명을 추출하는 것이 없다. 이 어휘가 아닌 것으로 문의를 매칭하는 정책은 「같은 문제」의 두 번째 정의가 된다.

## §7 재판정 — 매칭되는 열린 case만

정책 변경이 「앞으로」에 대한 약속일 뿐이면 지금 보고 있는 큐에는 아무 말도 하지 않는다 —
`OperationsSignal.signature`는 대상의 별점·본문·상품에서 파생되므로 대부분의 리뷰에서 그건 **영원히**다.

`OperationsPolicyRedecider`의 네 fence:

| fence | 내용 |
|---|---|
| **matching only** | 정책의 `aspect:problem` key를 증거로 가진 열린 리뷰 case만. `배송:지연` 규칙은 `표면:오염` 카드를 건드릴 기회가 아니다 |
| **open only** | `PREPARED`만. 닫힌 case는 재개되지 않고 재스탬프되지 않는다 — 결정된 것은 trail이 기록한 revision 아래서 결정됐다 |
| **deterministic only** | `OperationsCaseProcessor.applyPolicy`, run이 쓰는 그 메서드. **모델 호출 0**, 마켓플레이스 0, 검색 0 — 그래서 규칙을 몇 번 고쳐도 비용이 없다 |
| **never weaker** | 한 필드만 쓰고 action은 fence상 HUMAN이므로 카드를 닫거나 authority를 낮추거나 승인·mint·전송할 수 없다. 기다리고 있던 case는 끝난 뒤에도 기다리고 있다 |

**은퇴도 재판정이다.** 규칙을 되돌리면 `applyPolicy`는 아무것도 못 찾고 false를 반환 — 그러면 카드는 **철회된
규칙의 답**을 계속 들고 있게 된다. 그래서 `clear`가 `decidedBy == SELLER`인 카드의 추천을 지우고 `RULE`로
되돌린다(규칙도 조사도 없는 case가 정직하게 무엇인지로). **재조사하지 않는다** — 판매자가 설정을 고쳤다고
카드마다 모델 호출을 사는 건 그들이 요청한 비용이 아니고, 다음 run이 정상 예산으로 조사한다.
`AGENT`가 결론 낸 카드는 건드리지 않는다 — 그 결론은 애초에 이 규칙의 것이 아니었다.

상한 `MAX_PER_CHANGE = 200`: 판매자 요청 안에서 돌기 때문이다. 넘치는 꼬리는 다음 run이 **같은 메서드로 같은
결과를** 낸다 — 상한은 긴 꼬리의 신선도를 비용으로 치르고 정확성은 치르지 않는다.

## §8 검증

| 항목 | 결과 |
|---|---|
| backend 전체 | **5,237 tests · 0 failures** (657 suites, 58 skipped) |
| frontend 전체 | **3,908 tests · 0 failures** (286 files) · `tsc` 통과 |
| E2E (`CaseTeachToReplyFlowTest`, MockMvc + 실 Postgres) | 5 — 선언·provenance 3 fact · 두 체크의 독립(guidance 0행) · 처리 없음/문제 없음 거부 · `policyProblemKo` 유무 · 중단 후 카드 정리 |
| 화면 (`OperationsCase.test.tsx`) | 4 — 문제 없는 건엔 체크박스 없음 · 처리 선택 전 비활성 + 독립 scope 전송 · provenance 한 줄 + 중단 · 규칙 없는 건엔 둘 다 없음 |
| `SellerOperationsPolicyTest` (실 Postgres · 실 추출) | 16 — key 동일성 2 · 쓰기 fence 3 · revision/은퇴 2 · overlay precedence 4 · case 영향 3 · 재판정 4 |
| `SellerOperationsPolicyFenceTest` | 9 — 승격 경로 0건 · 실행 협력자 0건 · generic override 0건 · `CaseDecider.SELLER` writer 정확히 2개 · `applyPolicy` 본문에 종료 필드 0 · allowed action 전부 HUMAN |
| `V129` | 실 Postgres 15에서 제약 **전수 확인** — scope shape(PRODUCT 무상품 / ORG 유상품 모두 거부) · 라이브 ORG 규칙 1개 제한 · PRODUCT 규칙 공존 · 은퇴 후 key 해방 · `active=false` ⇒ `retired_at` 필수 · audit UPDATE/DELETE 거부 · `RETIRED` + action 거부 · `decided_by='SELLER'` 허용 / `'ROBOT'` 거부 |

테스트는 **H2가 아닌 실 Postgres**(`@AutoConfigureTestDatabase(replace = NONE)`)와 **실
`RuleBasedIssueSignatureExtractor`**로 돈다 — key가 이 테스트가 발명한 것이 아니라 추출이 실제로 쓰는 것임이
주장의 전부이기 때문이다. 단, 테스트 스키마는 Hibernate가 만들므로 **partial unique index는 테스트에서
강제되지 않는다**; 위 수동 검증이 그 절반을 담당한다.

## §9 제품 연결 — 기존 「다른 처리가 맞다」 흐름에만 (2026-10-10, 2차)

**새 설정 페이지는 만들지 않았다.** 판매자가 이미 쓰는 Case의 처리 변경 폼에 **두 번째 선택**을 더했다.

```
POST .../cases/{caseId}/correction
  correctedActionType, note
  remember,       scope          ← 「다음에도 참고하기」   (seller_guidance — 참고할 맥락)
  applyToFuture,  policyScope    ← 「앞으로 같은 문제도 이렇게 처리」 (정책 — 다음 판단을 정한다)
```

**네 필드이고 둘을 재사용하지 않는다.** 두 행위는 결과·저장소·수명주기·fence가 다르다 — 맥락을 남기고 싶었던
판매자가 같은 체크로 규칙을 선언할 수 없어야 하고, scope를 공유하면 「이 상품 지침 + 회사 전체 규칙」을 한 번에
말할 수 없다. 체크박스 기본값도 다르다: 「유사 건에 재사용」은 ON(맥락을 남기는 건 비용이 없다),
「앞으로 같은 문제도 이렇게 처리」는 **OFF**(아직 보지 않은 일을 정하는 선택이므로 하는 것이지 잊고 안 끄는 게
아니다).

**서버가 거부할 선택지는 그리지 않는다.** `policyProblemKo`가 null이면 체크박스 자체가 없고(문의 ·
추출된 문제가 없는 리뷰 · 판매자가 「중요하지 않음」 한 문제), 처리 방법을 고르기 전에는 비활성이다
(처리 없는 기준은 기준이 아니다). 체크한 뒤에야 범위와 「무엇이 같은 문제인가」가 나타난다 —
어휘 라벨(`배송 지연`)로, 리뷰 문장이 아니라.

**적용된 Case에는 한 줄과 하나의 출구만.**

```
판매자님이 정한 처리 기준이 적용됐습니다 · 배송 지연 · 이 상품        [이 기준 사용 중단]
```

바로 아래 「다른 처리가 필요하면 … 처리 변경」과 **같은 줄 계열**(muted 문장 + 오른쪽 텍스트 컨트롤)이다 —
같은 대상에 대한 같은 종류의 진술이고, 테두리 있는 카드는 각주가 본문보다 무거워지는 일이다. 규칙의 문장도,
개정 번호도, 설정 화면 링크도(없다) 없다. 중단은 **기존 retire 라우트**이고, 누른 뒤 case를 다시 읽는다 —
그 중단이 이 카드에 무엇을 했는지가 누른 이유다.

`appliedPolicy`는 `POLICY_APPLIED` 이벤트가 아니라 **overlay에서** 읽는다: 이벤트는 「과거 어느 순간 규칙이
결정했다」를 말하고 overlay는 「지금 어느 규칙이 서 있는가」를 말한다. 규칙을 은퇴시킨 뒤 pane을 다시 연
판매자는 **지금 상태**를 봐야 한다.

**IA 변화 0.** 새 페이지·새 라우트·새 메뉴 항목 없음. `applyScope`·`thisProduct`·`wholeCompany`는 teach 흐름이
쓰던 라벨을 **그대로** 재사용했다(두 번째 taxonomy를 만들지 않는다).

## §10 남은 것

**문의 레인은 비어 있다.** §6 참조 — 닫힌 problem 서명을 고객 질문에서 추출하는 것이 없는 한 열 수 없다.

**`/api/operations-policies`의 list·options·history는 호출자가 없다.** declare는 correction 흐름이, retire는
provenance 줄이 쓴다. 「내가 정한 기준 전체」를 한 화면에서 보는 것은 설정 페이지의 일이고, 그걸 새로 만드는
것은 `docs/product_assembly_ia_v1.md` §8의 FE/IA freeze(A7, 2026-08-18)를 건드린다 — 제품 소유자 결정이다.
API는 그 결정을 기다리는 상태로 완성돼 있다.
