# Post-purchase Timeline + Review Opportunity v1 — 감사 (2026-10-10)

**결론 한 문장.** 구매 뒤를 따라가는 timeline의 **등뼈가 schema에 없다** — 주문은 자기가 어떤 상품이었는지
모르고, 리뷰는 자기가 어떤 주문에서 왔는지 모르며, 「배송 완료」를 증명할 수 있는 주문의 집합은
**구조적으로 공집합**이다. 그래서 이 arc는 설계·구현으로 넘어가지 않고 **제품 결정 다섯 개**를 내놓는 데서
멈춘다. 없는 사실을 추정으로 메우면 그 추정이 판매자가 고객에게 하는 말이 된다.

이 문서는 방향 문서가 아니라 **측정 기록**이다. 모든 숫자는 2026-10-10 로컬 `sellerops` 데이터베이스
(org `7146c50f` = 데모 제조사, 라이브 수집 org)에서 읽었다.

---

## 1. 무엇이 있는가 — 실제 schema와 실제 행

| 표 | 행 | 무엇을 담는가 |
|---|---|---|
| `channel_orders` | **573** | 상품주문 한 줄의 현재 상태. 식별자 · 상태 코드 · 금액 · 결제 시각 |
| `channel_order_status_events` | **792** | append-only 상태 전이 이력 |
| `order_daily_summaries` | 203 | (org, 채널, 날짜) 집계 — **상품 단위가 없다** |
| `reviews` | 5,088 | 리뷰. `product_id` · `source_product_ref` · `received_at` |
| `inquiries` | 3,561 | 문의. `product_id` · `source_order_ref` · `order_binding` |
| `channel_products` | 300 | 상품 ↔ 채널 ↔ `external_product_id` |
| 반품 · 교환 · 클레임 | **표 자체가 없다** | — |
| 구매자 · 고객 | **표 자체가 없다** | V32가 의도적으로 투영하지 않는다 |

주문의 채널별 분포와 상태 코드:

| 채널 | 행 | raw 코드 | 정규화 | `status_changed_at` | 창 |
|---|---|---|---|---|---|
| NAVER | 393 | `PAYED` 393 | **PAID** 393 | 393 / 393 | 2026-08-21 ~ 09-05 |
| COUPANG | 180 | `FINAL_DELIVERY` 133 · `ACCEPT` 29 · `DELIVERING` 12 · `DEPARTURE` 6 | **UNKNOWN** 180 | **0 / 180** | 2026-08-16 ~ 09-05 |
| CAFE24 | **0** | — | — | — | — |

관측된 전이 (`channel_order_status_events`, 792행):

```
(첫 관측)→PAYED           393   observed_at 393/393   ← NAVER
(첫 관측)→ACCEPT           95   observed_at   0/95
ACCEPT→INSTRUCT            25   observed_at   0/25
ACCEPT→DEPARTURE           32   observed_at   0/32
INSTRUCT→DEPARTURE         26   observed_at   0/26
DEPARTURE→DELIVERING       63   observed_at   0/63
DELIVERING→FINAL_DELIVERY  54   observed_at   0/54    ← 「배송 완료」로 보이는 전이
DEPARTURE→FINAL_DELIVERY   10   observed_at   0/10
ACCEPT→FINAL_DELIVERY       9   observed_at   0/9
(첫 관측)→FINAL_DELIVERY   60 · →DEPARTURE 21 · →DELIVERING 3 · →INSTRUCT 1
```

---

## 2. 네 개의 끊긴 링크

### 2.1 주문은 자기 상품을 모른다 — 칸 자체가 없다

`channel_orders`의 16개 컬럼에 상품을 가리키는 것은 **하나도 없다**. `product_id`도, `external_product_id`도,
상품명도 없다. 수집 쪽도 읽지 않는다 — NAVER는 `DetailProductOrder(productOrderId, initialPaymentAmount)`
두 칸만 투영하고, Coupang은 `OrderItem(orderPrice)` 한 칸만 투영한다. 둘 다 `@JsonIgnoreProperties`이므로
응답에 상품 식별자가 있었다 해도 **버려진다**.

`channel_products.external_product_id`가 join 키로 이미 있으므로, 주문 쪽에 외부 상품 식별자 한 칸이
생기면 사슬은 닫힌다. 문제는 그 값이 응답에 있는지를 **이 저장소가 모른다**는 것이다 —
`docs/vendor/naver-commerce-api/get-v1-pay-order-seller-product-orders.md`는 **요청 파라미터** 문서이고
응답 스키마를 적지 않는다. Coupang은 `docs/vendor/`에 문서가 **하나도 없다**.

> 분류: **external research required** + 라이브 관찰. 기억으로 만든 필드명은 판매자의 실제 주문 위에서
> 실패한다.

### 2.2 리뷰는 자기 주문을 모른다 — 역시 칸이 없다

`reviews`의 25개 컬럼에 주문을 가리키는 것은 없다. 구매자를 가리키는 것도 없다. 리뷰는 `product_id`(5,088/5,088)
와 `source_product_ref`(NAVER 249)로 **상품에는** 붙지만 주문에는 붙지 않는다.

그리고 이 링크는 **채널 쪽에도 없을 가능성이 높다** — 리뷰 수집은 Action Window 기반이고 화면에서 읽는
것에 주문번호가 함께 보이는 일은 드물다. 확인하지 않았으므로 단정하지 않는다.

> 분류: **external research required**. 단, 이것이 없어도 **상품 단위**로는 리뷰를 따라갈 수 있다(§4).

### 2.3 「배송 완료」를 증명할 수 있는 주문의 집합은 공집합이다

세 사실이 겹쳐서 그렇게 된다.

1. **저장된 행은 발송 축을 증명하지 못한다.** `ChannelOrderStatusVocabulary.CONFIRMED`의 항목은
   **단 하나** — `(NAVER, PAYED) → 결제 완료`. 그 한 줄의 `fulfillment`도 `UNKNOWN`이다. 그러므로
   `OrderFulfillmentState.DELIVERED`는 **저장된 어떤 코드로도 도달할 수 없다.**
2. **`DELIVERED`로 가는 유일한 길은 Cafe24의 라이브 exact READ다** (`shipping_status = T`,
   `Cafe24ExactOrderReader`). `ExactOrderLookupCapability.declaredChannels()`는 `{CAFE24}` 하나다.
3. **그런데 Cafe24는 `channel_orders`에 0행이다.** 573개 주문은 전부 exact-lookup 계약이 없는 두 채널의
   것이다.

즉 **계약이 있는 채널에는 주문이 없고, 주문이 있는 채널에는 계약이 없다.** 이것은 데이터가 덜 모인
상태가 아니라 구조적 공집합이다.

### 2.4 전이에 채널이 준 시각이 없다

Coupang 전이 399건의 `observed_at`은 **0건** 채워져 있고, `channel_orders.status_changed_at`도 **0/180**이다.
남는 시각은 `recorded_at` — **우리 sync가 그것을 본 시각**뿐이고, 21일 동안 서로 다른 날짜는 **10일**이다.

그러므로 「배송 완료 N일 뒤」는 오늘 쓰면 **「우리가 그걸 본 날 + N일」**이다. 그 둘의 차이는 sync 간격만큼
이고, 데이터가 말하는 간격은 하루보다 크다.

### 2.5 (덧) 문의만이 주문을 가리킨다 — 그리고 3건이다

유일하게 존재하는 교차 링크다. `inquiries.source_order_ref` **3건** / 3,561건, 그중 `channel_orders`와
실제로 맞는 것 **2건**. 단방향(문의 → 주문)이고, 근거는 채널이 그 문의에 그 주문번호를 붙여 보낸 경우뿐이다
(`order_binding = SOURCE_EXACT`, 본문 추출 금지). 반품·교환은 어느 쪽에도 없다.

---

## 3. 그래서 Timeline은 무엇이 될 수 있나

**한 주문의 자기 이력은 이미 화면에 있다.** `GET /api/orders/{channelCode}/{accountId}/{parentOrderId}`가
`OrderRecordDetailResponse`로 돌려주는 것이 바로 channel-scoped post-purchase timeline이다 — 결제 단위로
묶인 상태 이력(`OrderStatusEventView`, 같은 전이를 줄 수마다 복제하지 않고 `lineCount`로 센다),
세 축을 따로 적은 라벨(증명되지 않은 축은 `null`), 그리고 **채널이 지목한 문의**(`OrderLinkedInquiryView`).
`observedAt`이 `null`이면 `null`로 둔다 — 우리가 기록한 시각을 그 자리에 적으면 「채널이 이때 바꿨다」는
말이 되기 때문이다.

이 timeline에 **더 붙일 수 있는 칸이 오늘은 없다.** 상품도, 리뷰도, 반품도, 구매자도 §2의 이유로 붙지
않는다. 새 timeline 객체를 만드는 것은 같은 정보를 두 번째 모양으로 복사하는 일이고, 그 사본은 드리프트한다.

**cross-channel 동일인 추정은 하지 않는다** — 지시이기도 하고, 애초에 추정할 재료가 없다. 구매자 필드는
어느 표에도 없고(V32의 privacy minimization), 이름·연락처로 잇는 길은 `InquiryOrderBinding`이 단일 값
`SOURCE_EXACT`로 이미 막아 둔 것과 같은 이유로 막혀 있다: 틀리면 **남의 택배 상태를 들은 고객**이 남는다.

---

## 4. Review Opportunity — 왜 오늘 선제 생성하지 않았나

선제 생성이 성립하려면 셋이 필요하다: **언제**(배송 완료 시각), **무엇에 대해**(주문된 상품), **누구에게**
(구매자). §2가 셋 다 없다고 말한다.

**만들 수 있었지만 만들지 않은 것: 채널 단위 리뷰 비율.** 주문 수와 리뷰 수는 둘 다 있으므로 비율을 쓸 수는
있다. 실제로 재 보면:

| 채널 | 주문 (창) | 그 창 + 3주의 리뷰 |
|---|---|---|
| NAVER | 393 (08-21~09-05) | 297 |
| COUPANG | 180 (08-16~09-05) | 14 |

**이 숫자는 비율이 아니다.** 297건은 **아무 시점의** 구매에 대한 리뷰이고(리뷰는 구매보다 몇 주 늦는다),
주문 창은 21일인데 리뷰는 16개월에 걸쳐 있다. 두 수의 관측 분모가 다르다. Coupang의 14는 더 분명하다 —
Coupang 리뷰는 4개월 동안 전체 55건뿐이고, 14는 「리뷰가 안 들어온다」와 「우리가 그 기간 Coupang 리뷰를
거의 읽지 못했다」를 **구별하지 못한다**. 비율처럼 보이는데 비율이 아닌 수를 화면에 올리는 것은
`docs/slices/attention-coverage-false-calm-v1.md`가 이름 붙여 둔 바로 그 함정이다.

**상품 단위는?** 리뷰는 상품에 붙지만(100%) **주문은 붙지 않는다**(§2.1). 그러므로 「이 상품은 많이
팔리는데 리뷰가 없다」는 문장의 앞 절을 이 저장소는 말할 수 없다. 뒤 절만으로 만든 카드는 「최근 리뷰가
줄었다」이고, 그것은 post-purchase 기회가 아니라 **우리 수집 coverage의 함수**다.

---

## 5. 제품 결정 — 다섯 개

| # | 결정 | 분류 | 결정 없이 가능한가 |
|---|---|---|---|
| **D1** | 주문에 **외부 상품 식별자** 한 칸을 투영할 것인가 | external research + 라이브 관찰 | 불가. 사슬의 등뼈 |
| **D2** | Coupang 상태 어휘를 **넓힐 것인가** — `ACCEPT·INSTRUCT·DEPARTURE·DELIVERING·FINAL_DELIVERY` | **product-owner decision** (전제는 충족된 것으로 보인다, 아래) | 불가 |
| **D3** | 전이 시각이 **우리 관측 시각뿐일 때** 그것으로 timing을 말할 것인가 | **product-owner decision** | 불가 |
| **D4** | NAVER 수집 범위를 `lastChangedType=PAYED` **밖으로** 넓힐 것인가 | external research + 라이브 승인 | 불가 |
| **D5** | 발송 금지가 유지될 때 Review Opportunity의 **행동**은 무엇인가 | **product-owner decision** | 불가 |

**D2에 대해 보고할 것이 하나 있다.** `ChannelOrderStatusVocabulary`는 자기 규칙을 이렇게 적어 두었다 —
「표를 넓히는 일은 실제 셀러 계정에서 전이를 관찰한 뒤에만 일어난다」. 그 전제는 **충족된 것으로 보인다**:
Coupang 코드 다섯 개는 실제 셀러 계정에서 승인된 라이브 run으로 관측됐고
(`docs/coupang_ordersheets_response_contract_hardening_v1.md` — 2026-08-06, `apr-5669c7c8…`,
FINAL_DELIVERY 30 · DELIVERING 13 · ACCEPT 7), 지금 데이터베이스에는 **순서가 일관된 전이**가 쌓여 있다
(`DELIVERING→FINAL_DELIVERY` 54건, `DEPARTURE→DELIVERING` 63건).

그래도 이 문서는 표를 넓히지 않았다. capability status를 올리는 일은 제품 소유자의 것이고
(`CLAUDE.md` — *Capability views (never promote a status)*), 승격은 `docs/evidence/INDEX.md`에 자기 행을
가져야 한다. **그리고 D2를 승인해도 D3가 남는다** — 뜻을 확정해도 시각이 생기지는 않는다(§2.4).

**D5가 가장 조용히 중요하다.** 발송이 금지되고 구매자 식별자가 없을 때 「리뷰를 요청하세요」 카드가
판매자에게 시키는 일은 **판매자센터에서 직접 하는 일**이다. 그것이 이 제품이 하려는 일인지는 측정으로
답할 수 없다.

---

## 6. 이 감사를 고정하는 것

없음은 런타임이 없으므로 **source와 migration 위에서** 주장한다 —
`backend/src/test/java/com/sellerops/order/PostPurchaseLinkageFenceTest.java`.

여섯 가지를 고정한다: `channel_orders`에 상품·구매자 칸이 없다 · `reviews`에 주문·구매자 칸이 없다 ·
`ChannelOrderStatusVocabulary`가 확인한 (채널, 코드)는 정확히 하나다 · **저장된 어떤 코드도 발송 축을
증명하지 않는다** · exact-lookup을 선언한 채널은 `{CAFE24}` 하나다 · 반품/클레임 표가 없다.

**이 테스트는 벽이 아니라 문패다.** D1이나 D2가 승인되는 날 해당 단정이 깨지고, 깨진 테스트가 이 문서를
가리킨다 — 「전에 없다고 적어 둔 것이 지금 있습니다, 감사를 다시 읽으세요」.

---

## 7. 지킨 금지 사항

- **cross-channel 동일인 추정 0** — 추정 코드도, 추정의 재료도 없다(§3).
- **마켓플레이스 호출 0** — 라이브 승인을 요청하지 않았고 소비하지 않았다. 모든 숫자는 로컬 데이터베이스와
  저장소 source에서 읽었다.
- **새 workflow 플랫폼 0 · 새 IA 0 · 새 화면 0 · 새 migration 0.**
- **발송 0** — 보낼 것을 만들지 않았다.
- **capability status 승격 0** — D2의 전제가 충족된 것으로 보인다고 **보고**했고, 표는 그대로 두었다.
