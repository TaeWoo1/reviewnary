# Order Context Foundation v1 — 주문이 자기 상품과 도착을 알게 된다 (2026-10-10)

**목표.** 최소 한 채널에서 **Product + Order + Fulfillment + provenance/time**이 이어진 post-purchase
context를 **실제 데이터로** 증명한다.

**결과.** 쿠팡에서 이어졌다. 실제 Postgres(production schema, Flyway 132) 위에서 한 줄이
`FINAL_DELIVERY` · 채널 상품 식별자 · canonical 상품명 · 연결 시각을 함께 말한다. 그리고 **마켓플레이스
호출 0으로** 이미 수집돼 있던 쿠팡 주문 **180건 중 174건**이 축을 얻었다 — 그중 **133건이 배송 완료**다.

**남은 하나.** Product leg는 식별자를 **한 번도 수집한 적이 없으므로** 기존 행으로 증명할 수 없다.
그 한 조각만 read-only 라이브 한 번이 필요하고, §8에 manifest를 준비해 두었다.

전제 감사: `docs/post_purchase_timeline_audit_v1.md`. 그 문서 §5의 결정 다섯 중 넷이 승인되어 이 arc가
되었다.

---

## 1. 조사 — 공식 계약 두 개를 찾았고, 하나는 못 읽었다

### 1.1 쿠팡 — 찾았다. 그리고 그것이 D2의 절반이다

`ChannelOrderStatusVocabulary`가 쿠팡 코드를 번역하지 않은 이유는 「코드 이름이 영어로 읽히는 것은
확인이 아니다」였다. 쿠팡은 그 확인을 **문서로 발행한다** —
`GET .../ordersheets/{shipmentBoxId}/history`의 `deliveryStatus` 표. 전사:
`docs/vendor/coupang-openapi/get-ordersheet-history.md`.

그 문서는 두 가지를 더 알려 준다.

- **식별자로 하나를 읽는 exact lookup이다.** `shipmentBoxId`는 이 저장소가 이미
  `channel_orders.external_order_id`로 보관하는 값이다.
- **상태 변경마다 `updatedAt`을 준다** — 감사 §2.4가 「쿠팡에는 채널이 준 시각이 없다」고 적었던 그
  빈칸의 답이다.

**그런데 capability를 선언하지 않았다.** reader 없이 선언하면 조용히 일어나지 않는 lookup을 광고하는
것이고(`ExactOrderLookupCapability`가 막으려 존재하는 실패), reader를 만드는 것은 **주문 N건에 호출
N번**이라는 비용 결정이다. 계약은 vendored 되어 있고 선언은 비어 있다 — 그 상태를
`PostPurchaseLinkageFenceTest`가 고정한다. **§8.2가 이것을 다음 한 걸음으로 적는다.**

### 1.2 NAVER — 못 읽었고, 그 사실을 설계에 넣었다

공식 문서는 `https://apicenter.commerce.naver.com/llms/post-v1-pay-order-seller-product-orders-query.md`에
있고 **이 개발 환경에서 그 호스트에 도달할 수 없다**. vendored 사본
(`get-v1-pay-order-seller-product-orders.md`)은 응답 하위 구조를 「상세는 OAS 참조」로 생략한다.

기댈 수 있었던 것은 둘이고 둘 다 저장소 밖이다 — 커머스API 운영자가 같은 경로 패턴으로 답한 다른 필드
(`data[n].productOrder.logisticsCompanyId`,
[discussion #3466](https://github.com/commerce-api-naver/commerce-api/discussions/3466))와, 사용자가
「채널 상품 번호(`data.productOrder.productId`)」라고 적은 글
([#1637](https://github.com/commerce-api-naver/commerce-api/discussions/1637)).

**그래서 확정하지 않고, 틀려도 안전한 설계를 했다.** 필드가 없으면 `null` → 참조 없음 → 무귀속이고,
그것은 이 arc 이전의 모든 주문이 말하던 것이다. 라이브 한 번이 있는지 없는지 답하고, 있으면 그 자리에서
연결이 생긴다. `ChannelProductRefSource.NAVER_PRODUCT_ORDER_QUERY`의 javadoc이 무엇이 확인되고 무엇이
안 되었는지 적고 있다.

**그리고 추가 호출이 0이다.** 이 저장소는 금액(`initialPaymentAmount`) 때문에 이미 이 endpoint를
배치로 POST하고 응답의 나머지를 버린다. 식별자는 그 버려지던 응답 안에 있다.

### 1.3 D4 — NAVER lifecycle: 범위를 넓히지 않았고, 조사가 더 나쁜 것을 찾았다

`lastChangedType`의 값은 공식 문서로 확인되지 않았다(검색 결과: `PAY_WAITING` · `PAYED` ·
`DISPATCHED` · `PURCHASE_DECIDED` · `CLAIM_*` · `COLLECT_DONE`; 2026-04 GitHub 이슈는 파라미터명을
`lastChangedStatusCode`로 부른다 — **이름조차 변동 가능**).

**더 중요한 것은 `DISPATCHED`가 「발송 처리」라는 점이다.** 판매자가 송장을 등록했다는 사실이고, 물건이
도착했다는 사실이 아니다. 그리고 #3466의 제목이 **「배송완료 상태 필드 요청」**이다 — 판매자들이 네이버에
그 필드를 **요청하고 있고**, 운영자 답변은 그 질문에 답하지 않는다. 같은 글의 판매자는
`DISPATCHED`를 배송완료 목록으로 쓰고 있다.

그래서 D4의 결론은 **「범위를 넓히면 배송 완료가 생긴다」가 아니다.** 넓히면 **발송 처리**가 생기고,
그것을 배송 완료로 읽으면 D3가 금지한 추정을 다른 자리에서 하는 것이 된다. 수집 범위는
`lastChangedType=PAYED` 그대로이고, manifest는 §8.3이다.

---

## 2. D2 — 어휘표가 다섯 줄이 되었다. 그리고 둘은 승격되지 않았다

번역에는 **서로 다른 증거 둘**이 필요하다. 하나만으로는 안 된다 — 관측만 있으면 도착한 글자를 우리가
해석한 것이고, 문서만 있으면 이 판매자의 계정에서 실제로 쓰이는지 모른다.

| (채널, 코드) | 증명하는 축 | 관측 | 계약 |
|---|---|---|---|
| NAVER · `PAYED` | 결제 완료 | 기존 | vendored |
| COUPANG · `ACCEPT` | **결제** 완료 | 2026-08-06 승인 run (7건) | `deliveryStatus` 표 |
| COUPANG · `INSTRUCT` | 발송 준비 중 | 지금 DB의 전이 25·26건 | 같음 |
| COUPANG · `DELIVERING` | 배송 중 | 2026-08-06 (13건) | 같음 |
| COUPANG · `FINAL_DELIVERY` | **배송 완료** | 2026-08-06 (30건) | 같음 |

`Confirmation` record가 축과 **증거 문서 경로를 같은 자리에** 들고 있고, 테스트가 그 파일이 디스크에
있는지 확인한다(`ExactOrderLookupCapability.CONTRACT_DOCS`와 같은 기법). 증거가 지워진 선언은 지워진
뒤에도 권위 있어 보인다. 주석에 적힌 증거는 다음 줄이 추가될 때 따라오지 않는다.

**승격되지 않은 둘이 이 변경의 절반이다.**

- **`DEPARTURE`** — 공식 문서의 **영문판이 *Shipped*, 한국어판이 *배송지시***다. 물건이 떠났다는 말과
  떠나라고 지시했다는 말이다. 같은 코드에 대한 플랫폼 자신의 두 문장이 다르면 그것은 확인이 아니다.
  실제 DB에 6건 있고, 계속 raw로 남는다.
- **`NONE_TRACKING`** — 「추적 없이 판매자가 보냈다」이고 우리 세 축의 어느 값에도 그대로 들어가지 않는다.

**그리고 각 줄은 자기 축까지만 말한다.** `FINAL_DELIVERY`는 배송 완료를 증명하고 **결제를 증명하지
않는다**. 배송된 주문이 결제되지 않았을 리 없다는 것은 상식이지만 이 코드가 말한 것은 아니고, 상식으로
축을 채우기 시작하면 세 축을 나눠 둔 이유가 사라진다. 그래서 133건의 배송 완료 주문은 **「배송 완료 ·
결제는 확인되지 않음」**으로 읽힌다. 그 문장이 이상하게 들리는 것이 정확하다.

### 2.1 저장하지 않고 **읽는 시점에** 계산한다 — migration 0

발송 축을 `channel_orders`에 컬럼으로 넣지 않았다. 어휘표를 SQL에 한 번 더 적는 일이고, 그 사본은 다음에
표가 넓어질 때 따라오지 않는다. `axesFromStored`를 지나는 기존 읽기 경로(주문 목록·상세·`OrderFact`)가
**어휘표 한 줄이 늘어난 것만으로** 실제 데이터 위에서 배송 완료를 말하기 시작했다.

`normalized_status`는 수집 시점의 캐시이고 **읽는 코드가 하나도 없다**(`SellingStatus`의 주석에 언급만).
백필하지 않았고 V132가 그 컬럼에 경고 주석을 달았다.

---

## 3. D1 — 주문의 상품 참조는 **줄의 것**이다

`channel_orders` 한 행의 알맹이가 채널마다 다르다. NAVER의 행은 상품주문 하나이므로 상품도 하나다.
**쿠팡의 행은 묶음배송 하나이고 `orderItems`가 여럿일 수 있다** — 그 행에 컬럼 하나를 놓으면 두 상품이
든 묶음에서 한 상품이 그 주문 전체의 상품이 된다. 같은 이유로 `OrderStatusEventView`가 전이를 줄 수마다
복제하지 않고, 같은 이유로 `statusVaries`가 존재한다: **줄의 값을 결제 단위로 올려 적지 않는다.**

그래서 자식 표 `channel_order_products`(V132)이고, **세 상태가 서로 구별된다.**

| 상태 | 뜻 | 이어지는 작업 |
|---|---|---|
| 행이 없다 | 채널이 식별자를 주지 않았다 | 수집 범위를 넓히는 일 |
| 행이 있고 `product_id` null | 식별자는 받았고 일치가 없다 | 상품을 수집하는 일 |
| `product_id`가 있다 | 증명된 연결 | 아무것도 |

하나로 접으면 「우리가 못 읽었다」와 「채널이 안 줬다」와 「연결이 아직 안 됐다」가 같은 빈칸이 되고,
셋은 서로 다른 작업으로 이어진다.

**연결의 유일한 근거는 정확 일치다** — `channel_products (channel_id, external_product_id)`.
`ChannelProductRef`가 문의 귀속에서 이미 선언해 둔 규칙 그대로다: 이름으로 다시 시도하지 않고,
placeholder 상품을 만들지 않고, 후보 중 첫 번째를 고르지 않는다. 동명이인 상품이 canonical Demo Org에
실제로 있고, 이름은 판매자가 언제든 바꾸는 값이다.

**식별자는 얼고, 연결은 한 번만 채워진다.** V132의 trigger 둘. 식별자가 바뀔 수 있으면 어제의 연결이
오늘 다른 상품을 가리키고, 연결이 다시 쓰일 수 있으면 틀린 연결을 틀린 채로 덮어쓸 수 있다 — 그 둘은
「판매자가 본 적 있는 주문의 상품이 조용히 달라졌다」로 같이 끝난다.

**해소는 다시 돌릴 수 있다.** 주문이 상품보다 먼저 수집되는 것은 사고가 아니라 흔한 순서다(주문
routine 60분, 상품 수집은 그보다 드물다). 식별자는 연결 없이도 저장되고, 상태가 바뀌지 않은 재동기화도
연결을 시도하며, `bindPending`이 나중에 같은 정확 일치를 다시 한다.

**provenance는 닫힌 어휘다.** `ChannelProductRefSource`가 채널·endpoint·필드 경로를 들고 있다. 자유
문자열은 두 달 뒤 세 가지 표기가 섞인 열이 되고, 그러면 「이 식별자는 믿을 만한 자리에서 왔나」가 문자열
비교 문제가 된다. 채널이 키의 일부인 이유는 어휘표와 같다 — **출처의 채널이 주문의 채널과 다르면 아무것도
저장하지 않는다**(두 채널의 상품번호는 같은 11자리 공간을 쓴다).

> 이 울타리가 작동하는 것을 Postgres proof가 먼저 보여 주었다. 첫 시도에서 테스트 채널 코드
> (`CPROOF-…`)로 주문을 넣었더니 참조가 **하나도 저장되지 않았다** — 그 채널에는 출처가 없다. 테스트
> fixture가 틀렸고 코드가 옳았다.

---

## 4. D3 — 추정하지 않는다. 그리고 **이름이 규율이다**

`observedAt`은 「채널이 이때 바꿨다」는 뜻이다. 그 자리에 우리 기록 시각을 적으면 쿠팡 주문 180건이 전부
「채널이 우리 sync 시각에 상태를 바꿨다」고 말하게 되고, 그 시각으로 「배송 완료 N일 뒤」를 계산하는 다음
arc는 **자기 sync 간격을 배송 기간으로 읽는다.**

- `channel_order_products`의 시각은 전부 우리의 것이고 이름이 그것을 말한다 — `bound_at` ·
  `first_seen_at` · `last_seen_at`. `delivered_at` 같은 열 하나가 생기면 그 뒤로는 그 열이 채널의
  사실인지 우리의 관측인지 **열 이름만으로 알 수 없다.**
- `OrderContextFenceTest`가 source 위에서 `setObservedAt(...)`의 인자가 채널이 준 값뿐임을 단정하고,
  **그 호출이 사라지면 울타리가 아무것도 지키지 않으므로 호출 수까지** 센다.
- **D2와 D3은 독립이다.** 코드의 뜻을 알게 된 것과 그 일이 언제 일어났는지를 아는 것은 다른 사실이다 —
  쿠팡 전이는 이제 「결제 완료 → 발송 준비 중」으로 읽히면서 `observedAt`은 여전히 `null`이다.
  `OrderRecordDetailServiceTest`가 그 둘을 같은 테스트에서 따로 단정한다.

---

## 5. 증명

### 5.1 실제 데이터, 마켓 호출 0 — 기존 쿠팡 주문 180건

| raw 코드 | 행 | 어휘표가 지금 말하는 것 |
|---|---|---|
| `FINAL_DELIVERY` | **133** | 배송 완료 (발송 축) |
| `ACCEPT` | 29 | 결제 완료 (결제 축) |
| `DELIVERING` | 12 | 배송 중 (발송 축) |
| `DEPARTURE` | 6 | **번역되지 않음** |
| | **174 / 180** | 축을 얻었다 |

### 5.2 실제 Postgres, production schema

`ChannelOrderPostgresProofIT`(opt-in, `SELLEROPS_PG_PROOF=1`)를 일회용 DB에 돌려 **real Flyway가
V1..V132를 적용**했고 6/6 통과. 사후 조회가 보여 준 줄:

```
BOX-1642f0c4… | FINAL_DELIVERY | 15421619093 | 선바로 일체형 전선몰딩
```

**한 줄에 Order · Fulfillment · 채널 식별자 · canonical 상품**이 있고, 참조가 없는 주문은
`(ref 없음)`으로 구별된다. 그 IT는 또한 상품이 주문보다 늦게 와도 연결되는 것과, 연결된 뒤 DB가 상품
교체·식별자 수정을 거부하는 것을 단정한다.

V132의 제약·trigger는 **별도 scratch DB에서 14가지를 개별로** 확인한 뒤 drop했다 — 테스트 프로파일은
H2 + entity DDL이라 trigger와 check가 실행되지 않는다. 확인 항목: 연결 없는 식별자 허용 ·
`product_id`↔`bound_at` 분리 불가(양방향) · 한 주문 안 중복 거부 · 다른 주문의 같은 상품 허용 ·
null→값 허용 · 상품 교체 거부 · 연결 해제 거부 · `bound_at` 수정 거부 · 식별자 수정 거부 · 출처 수정
거부 · 다른 주문으로 이동 거부 · 재동기화의 `last_seen_at` 갱신 허용 · `product_id` FK.

### 5.3 회귀

```
backend    666 suites · 5,298 tests · 0 failures · 60 skipped   (이전 664 / 5,277 / 58)
frontend   287 files  · 3,919 tests · 0 failures · tsc 통과      (변화 없음 — UI 금지)
```

새 테스트: `ChannelOrderProductBinderTest`(9) · `OrderContextFenceTest`(5) ·
`ChannelOrderStatusVocabularyTest`(10, 재작성) · `ChannelOrderPostgresProofIT` +2(opt-in) ·
NAVER/쿠팡 connector 투영 +4 · `PostPurchaseLinkageFenceTest`(5, 재작성).

**다시 쓴 테스트 다섯 건은 설계된 실패다.** 그 테스트들은 좁은 진실을 고정했고 표가 정당하게 넓어지는
날 깨지도록 되어 있었다 — 그리고 깨졌고, 실패가 감사 문서를 가리켰다. 규율은 하나도 버리지 않았다:
글자는 여전히 정확히 맞추고, 저장된 행은 여전히 어떤 **부정도** 증명하지 못하고, 확인되지 않은 코드는
여전히 번역되지 않는다. 바뀐 것은 확인된 줄의 **수**다.

---

## 6. 만들지 않은 것

- **Review Opportunity · UI · 메시지 발송 — 0.** 새 화면·라우트·메뉴 0, frontend 변경 0.
- **cross-channel 고객 추정 — 0.** 재료도 없다: 구매자 필드는 어느 표에도 없다.
- **새 범용 workflow — 0.** 새 scheduler·새 lane·새 플랫폼 없음. 상품 참조는 기존 주문 수집 경로에
  붙었고, 읽기는 기존 `OrderRecordDetailResponse`로 나간다.
- **marketplace WRITE — 0.** 이 arc의 모든 수치는 로컬 DB와 저장소 source에서 읽었다. **라이브 승인을
  요청하지 않았고 소비하지 않았다.**
- **쿠팡 exact lookup capability 선언 — 0.** 계약은 vendored, 선언은 비어 있음(§1.1).

---

## 7. 바꾸지 않고 보고하는 것

1. **`docs/multi-channel-connector-roadmap.md` §4.1을 수정하지 않았다.** 그 표의 결은
   채널 × DataType × method × status이고, 발송 축은 DataType이 아니다. 쿠팡 `ORDER_SUMMARY`는 이미
   선언돼 있고 이 arc는 그 범위를 넓히지 않았다 — 같은 응답의 같은 필드를 더 읽었을 뿐이다. 다만 §4.1을
   읽는 사람이 「쿠팡 주문은 이제 발송 축을 증명한다」를 알고 싶을 수 있으므로 **보고한다**.
2. **`ChannelOrderPostgresProofIT.flywayAppliedV32AndSchemaExists`가 이미 낡아 있었다.**
   `maxVersion == 32`를 단정하는데 저장소는 V131이었다 — opt-in이라 아무도 돌리지 않은 동안 썩었다.
   `>= 32` + 「V32가 적용되었다」로 바꿨다. 등식은 per-order 획득과 무관한 이유로 매번 깨진다.
3. **쿠팡 history endpoint의 공식 샘플이 자기 스키마와 어긋난다** — `details` 래퍼를 생략하고, 다섯
   항목에 같은 `updatedAt`을 준다. reader를 만드는 날 두 모양을 모두 견뎌야 하고, 다섯 전이가 같은
   밀리초인 응답은 시각이 아니라 자리표시자다. vendored 문서의 「조심할 것」 절에 적어 두었다.
4. **`shipmentBoxId`가 틀릴 때 404가 아니라 500이 온다** — 「없음」과 「장애」를 응답으로 구별할 수 없다.
   그 reader는 500을 「주문이 없습니다」로 읽어서는 안 된다.

---

## 8. 다음 — 준비했고 실행하지 않은 것

### 8.1 Gate 1 — Product leg의 라이브 증명 (READ_ONLY, 승인 1회)

식별자를 **한 번도 수집한 적이 없으므로** 기존 180행으로는 증명할 수 없다. 기존에 이미 호출하는
endpoint 하나를 같은 scope로 한 번 더 읽으면 답이 나온다.

**2026-10-11 — 전용 run kind를 만들었다: `orders-resync`.** 그 전에 준비해 본 manifest는 기존
`orders` kind였고 `mode: WRITE`로 찍혔다 — pristine DB에서 시작하므로 **운영자가 자격증명을 입력**해야
했고, 그건 로컬 vault WRITE이며 CLAUDE.md에 따라 자기 몫의 명시적 WRITE 승인이 필요하다. 그 manifest는
실행하지 않고 revoke했다(prepared process 종료 · run env 제거 · DB drop).

**분리가 핵심이다.** 자격증명 handoff는 **자기 bootstrap, 자기 WRITE manifest, 자기 날**의 일이고
(`wing-credential-bootstrap.sh handoff`), `orders-resync`는 **그 일이 자기 안에서 일어나는 것을
거부한다** — baseline이 「자격증명이 이미 저장되어 있지 않으면 시작하지 않는다」이다. 그 거부가
`READ_ONLY` 주장을 **구조적으로 벌어 오는** 방법이고, 주장만 하는 것과 다르다.

| 항목 | 값 |
|---|---|
| run kind | `orders-resync` |
| 채널 / 계정 | COUPANG / **이미 연결된** 계정 (자격증명 저장됨) |
| surface | **기존** `ORDER_SUMMARY` 수집 (`GET .../ordersheets`) — 새 surface 0 |
| mode | **READ_ONLY** (run kind에서 파생. 기본값은 WRITE) |
| maxActions | `sync=1, credential=0, test=0, re-sync=0` |
| baseline | Coupang 계정 1 · 자격증명 1 · `channel_order_products` **0** |
| 전제 조건 | `order-wire-shape=true`가 백엔드에 **armed** — 아니면 preflight가 거부한다 |
| 답하는 질문 | `orderItems[]`에 `sellerProductId`가 있는가, 몇 %가 채워져 있는가 |
| **성공 기준** | `channel_order_products` 행 > 0, 그리고 `sellerProductId present=N/N nonNull=M` |
| **증명하지 않는 것** | canonical `product_id` 연결 — §8.1b |

**관측기가 armed인지 preflight가 확인한다.** manifest는 run이 실제로 실행할 수 있는 capability만 적을 수
있다(승인 계약 §2). 관측기가 꺼진 백엔드는 수집하고 아무것도 기록하지 않으므로, 참조가 0건일 때
「키가 없었다」와 「값이 비었다」를 구별할 수 없다 — 승인 한 번을 답 없이 쓰는 것이고, 그 실패를 막기
위한 검사다.

### 8.1b Gate 2 — canonical 연결 (SELLER_PRODUCTS READ, 별도 승인)

Gate 1의 참조는 **unbound로 떨어지는 것이 정상이다.** 정확 일치의 다른 한쪽인
`channel_products`가 비어 있기 때문이고, 그것을 채우는 것은 **두 번째 마켓플레이스 surface**
(`SELLER_PRODUCTS`)다. 한 surface, 한 manifest, 한 승인 — gate 1이 조용히 두 번째로 손을 뻗으면 한 줄로
두 승인을 쓰는 것이 된다.

Gate 2가 증명할 것: 상품 카탈로그 1회 READ 뒤 `ChannelOrderProductBinder.bindPending`이 같은 정확
일치를 다시 시도해 `product_id`가 채워지는 것. 코드는 이미 있고(해소는 다시 돌릴 수 있다, §3) 필요한
것은 승인뿐이다.

**관측기를 왜 같이 켜는가.** 승인은 1회이고, 참조가 0건 나왔을 때 **「키가 없었다」와 「값이 비어 있었다」를
구별할 수 없으면** 그 승인은 답 없이 소비된다. 둘은 서로 다른 작업으로 이어진다 — 요청을 넓히는 일과,
이 판매자의 줄에 왜 상품이 없는지 묻는 일. 관측기는 그 구별을 `present=2/2 nonNull=1` 형태로 돌려준다.

관측 범위(`ORDER_WATCHED_KEYS`)는 세 묶음이고 순서가 질문의 순서다 — (1) 줄이 상품을 지목하는가
(`sellerProductId` 외), (2) 없을 때의 대체 식별자, (3) **이 응답에 배송 시각이 있는가**
(`deliveredDate` · `inTransitDateTime` · `invoiceNumber` · `deliveryCompanyName`). (3)이 하나라도
있으면 §8.2의 per-order history 호출(과 그 N번 비용)이 필요 없다.

**값이 나가지 않는 것은 테스트가 단정한다.** 구매자 이름·수령인·주소·이메일을 **일부러 포함한**
주문 본문으로 `summaryLines()`에 그 어느 것도, 식별자도, 금액도, 상태 값도, 페이징 토큰도 나타나지 않음을
확인한다(`CoupangWireShapeObserverTest`). 그리고 관측을 켠 수집 결과가 끈 것과 **동일**하며 요청 수도
같다 — 관측기는 이미 손에 있는 본문을 한 번 더 파싱할 뿐이다. 두 flag(상품·주문)는 분리되어 있고 둘 다
기본 OFF이며, 그 기본값을 fence test가 고정한다.

NAVER도 같은 성질이다 — `POST /product-orders/query`를 이미 호출하므로 **추가 호출 0**이고, 다음
routine tick이 새 mapper로 읽으면 `productId`가 있는지 그 자리에서 드러난다.

### 8.2 쿠팡 배송 시각 (D3이 기다리는 유일한 사실)

`GET .../ordersheets/{shipmentBoxId}/history`가 전이마다 `updatedAt`을 준다 — 지금 `observed_at`이
0/399인 그 빈칸. 계약은 vendored 되어 있고, 남은 것은 **주문 N건에 호출 N번**을 받아들일지와 reader를
만들지의 결정이다. 그때 `ExactOrderLookupCapability`에 COUPANG 줄이 생기고
`PostPurchaseLinkageFenceTest`가 깨진다 — 의도된 것이다.

### 8.3 NAVER lifecycle (D4)

`lastChangedType` 값 집합이 공식 문서로 확인되지 않았고 파라미터명조차 변동 가능하다. 그리고 넓혀서
얻는 것은 **발송 처리**이지 배송 완료가 아니다(§1.3). manifest: 기존 `ORDER_SUMMARY` surface,
`lastChangedType` 하나를 `DISPATCHED`로 **한 번** 호출해 응답이 오는지와 `productOrderStatus` ·
`lastChangedDate`가 무엇인지만 확인, WRITE 0, 수집 범위는 그 결과를 본 뒤에 결정.
