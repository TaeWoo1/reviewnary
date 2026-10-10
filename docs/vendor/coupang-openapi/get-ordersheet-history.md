# GET .../ordersheets/{shipmentBoxId}/history — 배송 상태 변경 히스토리 조회

**출처.** `https://developers.coupang.com/hc/en-us/articles/360033792934-Searching-Delivery-Status-Change-History`
(이전 호스트 `developers.coupangcorp.com`에서 302로 이동). 한국어판:
`https://developers.coupangcorp.com/hc/ko/articles/360033792934-배송상태-변경-히스토리-조회`.
**전사 일자 2026-10-10.** 공개된 페이지를 옮겨 적은 것이고, 이 저장소는 이 endpoint를 **호출한 적이 없다**.

**이 문서가 왜 vendored 되었나.** `ChannelOrderStatusVocabulary`가 쿠팡 상태 코드를 번역하지 않은 이유는
「코드 이름이 영어로 읽히는 것은 확인이 아니다」였다. 그 확인을 공급하는 것이 이 문서의 `deliveryStatus`
표다 — 라이브에서 코드가 도착하는 것을 본 것(2026-08-06)과, 그 코드가 무엇을 뜻하는지를 플랫폼이
문서로 말한 것은 **다른 두 증거**이고, 번역에는 둘 다 필요하다.

## Endpoint

| | |
|---|---|
| Method | `GET` |
| Path | `/v2/providers/openapi/apis/api/v5/vendors/{vendorId}/ordersheets/{shipmentBoxId}/history` |
| Host (예시) | `https://api-gateway.coupang.com` |
| URL API name | `GET_ORDERSHEET_HISTORY` |
| 대상 마켓 | 한국 · 대만 |

**READ 전용.** 이 문서에는 상태를 바꾸는 필드가 없다(상태 변경은 별도의 PATCH endpoint들이다).

## 요청 파라미터

둘 다 path 이고 둘 다 필수다. query 파라미터는 문서에 없다.

| 이름 | 타입 | 설명 |
|---|---|---|
| `vendorId` | string | 쿠팡이 발급한 판매자 vendor ID (예: `A00012345`) |
| `shipmentBoxId` | number | 묶음배송번호. PO 목록 조회 API에서 얻는다 |

**식별자로 하나를 읽는다** — 기간 스윕이 아니다. `ExactOrderLookupCapability`가 요구하는 모양이고,
`shipmentBoxId`는 이 저장소가 이미 `channel_orders.external_order_id`로 보관하고 있는 값이다.

## 응답 스키마

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | number | 서버 응답 코드 |
| `message` | string | 서버 응답 메시지 |
| `data` | object[] | 히스토리 레코드 목록 |
| `data[].shipmentBoxId` | number | 묶음배송번호 |
| `data[].details` | object[] | 상태 이력 항목 |
| `data[].details[].deliveryStatus` | string | 상태 코드 (아래 표) |
| `data[].details[].deliveryStatusDesc` | string | 상태 설명. 샘플에서는 한국어 |
| `data[].details[].updatedAt` | string | **상태가 바뀐 시각.** ISO-8601, `YYYY-MM-DDThh:mm:ss.ssssss±hh:mm` |

`updatedAt`이 응답의 **유일한** 시각 필드다.

## `deliveryStatus` 열거값

| 값 | 영문판 | 한국어판 |
|---|---|---|
| `ACCEPT` | Payment completed | 결제완료 |
| `INSTRUCT` | Product being prepared | 상품준비중 |
| `DEPARTURE` | **Shipped** | **배송지시** |
| `DELIVERING` | In transit | 배송중 |
| `FINAL_DELIVERY` | Delivered | 배송완료 |
| `NONE_TRACKING` | 판매자 직접 발송으로 배송 연동이 없어 추적 불가 | — |

## 이 문서를 믿을 때 조심할 것 — 세 군데

1. **`DEPARTURE`는 두 판이 서로 다르게 말한다.** 영문은 *Shipped*, 한국어는 *배송지시*다. 전자는 물건이
   떠났다는 말이고 후자는 떠나라고 지시했다는 말이다. 같은 코드에 대한 플랫폼 자신의 두 문장이 다르면
   그것은 확인이 아니다 — 그래서 `ChannelOrderStatusVocabulary`는 `DEPARTURE`를 **번역하지 않는다**.
2. **샘플이 스키마와 어긋난다.** 샘플은 히스토리를 `data` 아래에 바로 놓고 `details` 래퍼와 최상위
   `code`/`message`를 생략한다. 바인딩은 두 모양을 모두 견뎌야 하고, 어느 한쪽을 가정해서는 안 된다.
3. **샘플의 다섯 항목이 모두 같은 `updatedAt`을 가진다.** 문서 작성의 산물로 보이지만, 실제 응답에서
   시각이 구별되는지는 **관측으로만** 확인된다. 다섯 전이가 같은 밀리초에 일어난 응답을 받으면 그것은
   시각이 아니라 자리표시자다.

## 에러

| HTTP | 메시지 | 문서가 말하는 원인 |
|---|---|---|
| 400 | Invalid vendor ID | `vendorId`가 틀렸다 |
| 500 | Internal Exception | `shipmentBoxId`가 틀렸다 |

`shipmentBoxId`가 틀렸을 때 404가 아니라 500이 온다는 것은 **「없음」과 「장애」를 응답으로 구별할 수 없다**는
뜻이다. 이 endpoint를 쓰는 reader는 500을 「주문이 없습니다」로 읽어서는 안 된다.
