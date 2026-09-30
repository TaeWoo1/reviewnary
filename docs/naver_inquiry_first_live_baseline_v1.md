# NAVER Inquiry — First Live Baseline v1 (2026-09-30)

**Verdict: `PASS` · CLOSED.**

첫 실판매자 NAVER 스마트스토어 계정을 canonical org에 연결하고, 공식 커머스 API의 **문의 두 lane을
각각** 읽어 canonical에 저장하고, 같은 범위를 다시 읽어 중복이 생기지 않는 것까지 확인한 기록이다.
marketplace WRITE는 0이고, 수집은 전부 READ다.

이 문서는 Cafe24 first live baseline(`docs/cafe24_first_live_baseline_v1.md`, `PASS`·CLOSED)에
이어지는 두 번째 채널 baseline이며, 그 판정을 바꾸지 않는다.

---

## 1. 환경

| 항목 | 값 |
|---|---|
| 날짜 | 2026-09-30 (UTC 표기) |
| 배포 commit | `366ef27b` = tag `shadow-naver-cafe24-v1-rc12`, detached·exact·clean |
| **제품 코드 변경** | **없음.** 이 baseline은 **env 전용** 변경으로 수행됐다 |
| host | pilot EC2, canonical public host = DuckDNS, sslip.io는 extra host로 유지 |
| org | `c412c3d5…` (NAVER 신원 org — Cafe24 baseline과 같은 org) |
| seller account | `847bafaa…` · channel `NAVER` |

이 run을 위해 켠 env는 넷뿐이다.

```
SELLEROPS_CONNECTOR_NAVER_ENABLED=true
SELLEROPS_CONNECTOR_NAVER_ADVERTISED_EGRESS_IPS=3.39.210.89
SELLEROPS_CONNECTOR_NAVER_INQUIRY_PRODUCT_QNA_ENABLED=true
SELLEROPS_CONNECTOR_NAVER_INQUIRY_CUSTOMER_ENABLED=true
```

`NAVER_ENABLED`만으로는 문의가 켜지지 않는다 — NAVER는 공식 문의 리소스가 **둘**이고 각자 자기
플래그를 갖는다. 두 플래그가 모두 꺼져 있으면 connector는 `INQUIRY`를 **광고조차 하지 않는다**.

**그대로 OFF로 둔 것**: marketplace WRITE(publish execution 2종), Coupang,
`SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED`, responsibility scheduler, responsibility
investigation, proactive, mock/seed. 네 줄 외에는 아무것도 건드리지 않았고 배포 후에도
`366ef27b`는 이동하지 않았다(`git describe --tags --exact-match HEAD` exact, `status --porcelain` 빈 값).

---

## 2. 연결과 credential

| 항목 | 값 |
|---|---|
| `seller_accounts.connection_status` | **`CONNECTED`** (생성 05:47:41Z) |
| `connector_credentials` 행 수 | **1** (`seller_account_id`에 UNIQUE 제약) |
| `connector_class` / `auth_type` | `API` / **`API_KEY`** |
| 저장 형태 | `encrypted_payload` + `iv` + `encryption_key_fingerprint` 존재 |
| `granted_scopes` | 공백 — NAVER는 OAuth scope 모델이 아니라 **애플리케이션 권한 그룹** 모델이다 |
| `channel_connection_status` | `CONNECTED` · **`consecutive_failures` 0** · `last_error` 없음 |
| 마지막 성공 | 07:23:06Z |

**credential 재사용은 실측으로 증명됐다** — 저장된 이 한 행으로 job **7건**이 전부 성공했고, 재입력도
재발급도 없었다. Cafe24가 OAuth2(`mall.read_*` 3종)인 것과 달리 NAVER는 애플리케이션
client id/secret이며, 토큰은 매 호출마다 전자서명으로 발급되어 캐시된다.

---

## 3. egress IP와, 판매자만 줄 수 있었던 권한

NAVER는 애플리케이션의 **`API 호출 IP`**에 등록된 주소에서만 호출을 받는다. 그래서 이 채널은
"고정 outbound IPv4"가 **연결의 전제**다(Cafe24에는 없던 조건이다).

실측(`deploy/pilot/egress-check.sh`, read-only):

```
host outbound      : 3.39.210.89
container outbound : 3.39.210.89
advertised (env)   : 3.39.210.89
ok: actual == advertised
```

host와 backend 컨테이너가 **같은** Elastic IP로 나가고, 그 값이 제품이 판매자에게 등록하라고
표시하는 값과 **같다**. 세 값이 어긋나면 첫 호출이 `403 GW.IP_NOT_ALLOWED`로 죽는다. smoke에도
`egress-check: host and container outbound IP agree with ADVERTISED` 항목으로 남아 있다.

두 번째 전제는 **문의 API 그룹 권한**이다. 두 endpoint 모두 애플리케이션이 이 권한을 갖고 있어야
하고, 없으면 403이며 **우회하지 않는다** — 판매자가 자기 애플리케이션에 권한을 추가하는 행동이다.
이 run에서는 **403이 나지 않았다**(§4). 즉 권한이 실제로 부여된 상태에서 읽었다.

---

## 4. 수집 — run 목록과, lane별 결과

### 4.1 run 목록 (account `847bafaa…`)

| 시각(UTC) | data type | trigger | status | total | success | skipped | failed |
|---|---|---|---|---|---|---|---|
| 05:47:45 | ORDER_SUMMARY | MANUAL | SUCCESS | 5 | 5 | 0 | 0 |
| **05:49:43** | **INQUIRY** | **SCHEDULED** | **SUCCESS** | **2** | **2** | **0** | **0** |
| 05:49:44 | ORDER_SUMMARY | SCHEDULED | SUCCESS | 0 | 0 | 0 | 0 |
| 06:49:46 | INQUIRY | SCHEDULED | SUCCESS | 0 | 0 | 0 | 0 |
| 06:49:47 | ORDER_SUMMARY | SCHEDULED | SUCCESS | 12 | 12 | 0 | 0 |
| 07:09:24 | INQUIRY | MANUAL (`/sync`) | SUCCESS | 0 | 0 | 0 | 0 |
| **07:23:05** | **INQUIRY** | **MANUAL (`/backfill`)** | **SUCCESS** | **2** | **0** | **2** | **0** |

**7 runs · 7 SUCCESS · 0 failures · `failed_rows` 총합 0.** ORDER_SUMMARY 행은 같은 계정의
맥락으로만 싣는다 — 이 baseline의 판정 범위는 INQUIRY다.

### 4.2 lane별 — 각 endpoint를 따로 기록한다

runtime은 data type 하나에 커서 하나를 주고 NAVER 문의 리소스는 둘이므로, 한 run이 두 lane을
차례로 훑고 커서가 각 lane의 위치를 따로 기억한다. connector가 lane별로 로그를 남기기 때문에
`source=`로 귀속이 보존된다 — 두 lane을 동시에 켜도 어느 endpoint의 결과인지 섞이지 않는다.

**최초 READ (05:49:43, routine 14일 창):**

| lane | endpoint | window | page | rows | 결과 |
|---|---|---|---|---|---|
| `PRODUCT_QNA` | `GET /external/v1/contents/qnas` | `2026-09-16T14:49:43.173+09:00` ~ `2026-09-30T14:49:43.173+09:00` | 1 | **1** | **200** |
| `CUSTOMER_INQUIRY` | `GET /external/v1/pay-user/inquiries` | `2026-09-16` ~ `2026-09-30` | 1 | **1** | **200** |

**bounded backfill (07:23:05, 같은 범위 재읽기):**

| lane | window | page | rows |
|---|---|---|---|
| `PRODUCT_QNA` | `2026-09-16T00:00:00.000+09:00` ~ `2026-10-01T00:00:00.000+09:00` | 1 | **1** |
| `CUSTOMER_INQUIRY` | `2026-09-16` ~ `2026-09-30` | 1 | **1** |

**401 / 403 / 429 / WARN / ERROR = 0 / 0 / 0 / 0 / 0** — 전 run 전 lane. 403이 없다는 것이
문의 API 그룹 권한이 실제로 통과했다는 증거다.

두 lane의 창 표기가 다른 것(`PRODUCT_QNA`는 offset 붙은 datetime, `CUSTOMER_INQUIRY`는 날짜)은
결함이 아니라 **두 endpoint의 계약이 원래 다르기** 때문이다.

### 4.3 「지금 동기화」와 「기간 지정 수집」은 다른 일을 한다

07:09:24 run은 **의도한 bounded backfill이 아니었다.** 세 가지가 그것을 말한다: 창이
`2026-09-30T15:49:46.745+09:00`부터 시작해 **직전 run의 `windowTo`에서 이어받았고**, 움직인 커서가
`cursor_key=primary`이며 값이 `"bounded":false`였고, `backfill` 커서 행은 생기지 않았다.
즉 눌린 것은 `POST /sync`(「지금 동기화」)였다.

제품 결함이 아니다 — routine 창이 이미 소진된 상태에서 새 20분 조각에 문의가 없었던 정상적인
0건이다. 다만 **0건을 읽은 run은 dedup 증거가 될 수 없다**(dedup이 일할 기회가 없다). 그래서
「기간 지정 수집」 패널(`POST /backfill`)로 같은 범위를 다시 읽는 §6이 필요했다. 이 구분을 기록해
두는 이유는, 두 버튼이 같은 화면에 있고 둘 다 `trigger=MANUAL`로 기록되기 때문이다.

---

## 5. canonical 저장과 귀속

`inquiries`의 NAVER 행은 **2건**, lane마다 정확히 1건이다.

| `source_subtype` | `external_id` | `received_at` | `status` | 답변 본문 | `answered_at` | `source_product_ref` |
|---|---|---|---|---|---|---|
| `NAVER_PRODUCT_QNA` | `naver-qna:689162087` | 2026-09-17 01:52:31Z | `ANSWERED` | 있음 | **NULL** | `557622761` |
| `NAVER_CUSTOMER_INQUIRY` | `naver-payinq:325973790` | 2026-09-17 22:14:54Z | `ANSWERED` | 있음 | 2026-09-18 01:37:41Z | `557622761` |

두 행 모두 `seller_account_id` = `847bafaa…`, `data_origin` = `REAL`.

**개인정보는 저장되지 않았다.** 고객 문의 응답에는 `customerId`/`customerName`이 실제로 내려오지만
`author`는 **두 행 모두 NULL**이다 — audit이 정한 계약이 유지됐다.

두 가지는 결함이 아니라 계약이다.

- **`PRODUCT_QNA`의 `answered_at`이 NULL**인데 답변 본문은 있다. 상품 문의 리소스에는 **답변 시각
  필드가 없다**. 고객 문의에는 있고, 실제로 채워졌다. 한 테이블의 같은 칼럼이 lane에 따라 다르게
  비는 것이며, 없는 값을 추측해 넣지 않았다.
- **`product_id`가 두 행 모두 NULL**이다. `source_product_ref`에는 채널상품번호가 제대로 들어왔지만
  붙일 canonical product가 없다 — NAVER `PRODUCT`를 수집하지 않았고, `PRODUCT`는 routine data
  type이 아니라 스케줄도 생기지 않는다. 귀속 실패가 아니라 **상대가 아직 없는 상태**다.

전체 canonical: `inquiries` CAFE24 **2** + NAVER **2**. Cafe24 행은 이 run에서 변하지 않았다.

---

## 6. 멱등성 — 같은 범위를 두 번 읽었다

`POST /backfill`로 **최초 READ와 같은 범위**(`2026-09-16` ~ `2026-09-30`)를 다시 읽었다.

```
SUCCESS · totalRows=2 · successRows=0 · skippedRows=2 · failedRows=0
```

두 lane이 각각 `rows=1`을 **실제로 다시 받았고**(§4.2), 그 2건이 모두 **이미 있는 행으로
인식됐다**. `success_rows`는 처리된 행이고 `skipped_rows`는 이미 있다고 인식된 행이므로,
`skipped 2`가 바로 dedup이 작동한 증거다. 0건을 읽어서 0건이 저장된 것과는 다른 사실이다.

**중복 검사**

| 기준 | 중복 그룹 |
|---|---|
| `(org_id, channel_id, external_id)`, `external_id IS NOT NULL` | **0** |
| `(org_id, channel_id, content_hash)`, `content_hash IS NOT NULL` | **0** |

canonical NAVER 문의는 여전히 **2건**, distinct `external_id` **2**개.

**커서 lane 분리도 증명됐다.** `sync_cursors`의 natural key는
`(org_id, seller_account_id, data_type, cursor_key)`이고, backfill은 새 행을 썼다:

```
847bafaa | INQUIRY | backfill | {"qna":{"from":"2026-09-16T00:00:00.000+09:00",
                                 "to":"2026-10-01T00:00:00.000+09:00","page":1,"done":true},
                                "customer":{"from":"2026-09-16","to":"2026-09-30",
                                 "page":1,"done":true},
                                "active":"PRODUCT_QNA","bounded":true}
```

같은 시점의 `primary` 행은 **한 글자도 움직이지 않았다**(`updated_at` 07:09:25 그대로,
`"bounded":false`). operator의 창이 routine의 위치를 밀어내지 않는다.

`primary` 커서는 두 lane이 한 문자열 안에서 각자 전진하는 것도 보여준다 — `qna`와 `customer`가
자기 `from`/`to`/`page`/`done`을 따로 들고 있다.

**기록해 둘 사실 하나**: skip된 두 행의 `updated_at`은 backfill 시각으로 갱신됐다(`created_at`과
달라졌다). insert가 아니라 "다시 봤다"는 기록이며, 행 수도 `external_id`도 변하지 않았다.

---

## 7. routine 재발생

| 항목 | 값 |
|---|---|
| 계정 생성 | 05:47:41Z |
| 스케줄 자동 생성 | **05:48:56Z** (75초 뒤) |
| 생성된 스케줄 | `INQUIRY`, `ORDER_SUMMARY` — `INTERVAL` **60분**, `enabled` |
| `REVIEW` 스케줄 | **없음** |
| 실제 실행 | 05:49, 06:49 두 tick 모두 실행됨 |

판매자가 스케줄을 만들지 않았다. self-pilot reconciler가 connector가 광고한 routine data type에
대해 만들었고, 광고는 capability가 `CONFIRMED`일 때만 일어난다. NAVER 문의 두 lane은 코드에
`VERIFICATION_STATUS = "CONFIRMED"`로 박혀 있고, 그 둘을 보수적으로 접어 `INQUIRY`가
`CONFIRMED`가 된다 — 한 lane이라도 미검증이면 type 전체가 `NEEDS_VERIFICATION`이 되어 routine
스케줄이 생기지 않는다.

**`REVIEW` 스케줄이 없는 것이 정확하다.** NAVER API connector는 `REVIEW`를 광고하지 않는다(NAVER
리뷰는 공식 API가 아닌 별도 경로이고, 이 baseline에서 OFF로 유지했다). 판매자가 화면에서 본
"리뷰는 이 API 채널에서 제공하지 않음"과 같은 사실이다.

---

## 8. marketplace WRITE

**0.** 실행·승인·의도·초안·제출 테이블 **14종**을 전부 세었고 모두 비어 있다:

`inquiry_execution` · `inquiry_approval` · `inquiry_action_intent` · `inquiry_proposal` ·
`inquiry_reply_draft` · `review_reply_execution` · `review_reply_submission_ref` ·
`review_reply_approval` · `review_reply_draft` · `review_reply_outcome` · `review_import_launch` ·
`review_import_plan` · `review_import_segment` · `inquiry_import_batch` — **전부 0.**

connector가 이번에 사용한 endpoint는 두 개이고 둘 다 `GET`이다. publish execution 플래그 2종은
`false`이며, `REVIEW_IMPORT_UNATTENDED`도 `false`다.

---

## 9. host 검증

| 항목 | 결과 |
|---|---|
| `deploy/pilot/smoke.sh` | **37 ok · 0 failed** — `NAVER on and an advertised call IP is set` 및 `egress-check … agree with ADVERTISED` 포함 |
| 마이그레이션 | **113 / 113** 적용, 실패 0, baseline 행 없음 |
| 컨테이너 | backend · edge · frontend · agent-runtime · postgres — 5개 정상(health 있는 4개 healthy) |
| `deploy/pilot/backup.sh` | **성공** — 2026-09-30T07:24:53Z · `360,838` bytes · off-host S3 업로드 완료(ETag 확인) · `cred=instance-role` |
| 제품 화면 노출 | **operator 확인** — 문의 목록에 NAVER 2건 실제 노출 |

화면 총계가 **3건**(Cafe24 1 + NAVER 2)인 것은 정확하다. Cafe24 행 2개 중 하나는 스레드
**REPLY**이고 「답글은 문의가 아니다」 기준으로 목록에 서지 않는다. NAVER 2건은 둘 다 `ANSWERED`라
「미답변 문의」 큐에는 보이지 않는 것이 정상이다.

---

## 10. 판정과 backlog

**`PASS` · CLOSED.** 완료 기준 전부 충족:

| 기준 | 결과 |
|---|---|
| NAVER seller account CONNECTED | ✅ `847bafaa…`, credential 1행, `consecutive_failures` 0 |
| 실제 Inquiry 1회 이상 READ | ✅ 05:49:43 routine run, 두 lane 각 200 / rows 1 |
| canonical 저장 확인 | ✅ 2건, lane별 1건, `seller_account_id` 귀속 |
| UI 노출 확인 | ✅ operator 확인 |
| 동일 범위 재수집 후 중복 없음 | ✅ `skipped 2` · 중복 그룹 0 · 행 수 불변 |
| connector failure 0 | ✅ 7/7 SUCCESS, `failed_rows` 0, 403/401/429 0 |
| marketplace WRITE 0 | ✅ 14개 테이블 전부 0 |
| backup 성공 | ✅ off-host S3, ETag |
| evidence docs | ✅ 이 문서 + `docs/evidence/INDEX.md` |

### backlog — 고치지 않고 기록한다

1. **`content_hash`가 문의 전 행(4/4) NULL**이라 `uq_inquiries_hash`가 **inert**하고 `external_id`가
   dedup을 단독으로 담당한다. Cafe24 baseline과 같은 사실이며, 채널이 늘어도 그대로다.
2. **NAVER 발급 체크리스트가 문의 권한을 말하지 않는다.** `NAVER_ISSUANCE_TUTORIAL`의
   `select_api_group` 단계 문구는 "상품·주문(판매자)" 기준으로 쓰여 있고, 문의 두 endpoint가 요구하는
   **문의 API 그룹 권한**을 언급하지 않는다. 체크리스트를 그대로 따른 판매자는 주문은 되고 첫 문의
   수집에서 403을 만날 수 있다. 이번 run에서는 권한이 이미 부여돼 있어 드러나지 않았다.
3. **문의가 product에 붙지 못한다.** 채널상품번호(`source_product_ref`)는 들어오지만 NAVER
   `PRODUCT`를 수집하지 않으면 붙일 canonical product가 없고, `PRODUCT`는 routine type이 아니라
   스케줄이 생기지 않는다. 문의를 상품 맥락과 함께 보려면 `PRODUCT` 수집 경로가 필요하다.
4. **14일 routine 창 밖은 미측정.** 두 문의가 2026-09-17이라 14일 창 안에 들어왔고, Cafe24 리뷰에서
   났던 "창 밖이라 0건" 문제가 이번에는 발생하지 않았다. 그러나 그보다 오래된 NAVER 문의가 있는지는
   **재지 않았다** — 없다는 뜻이 아니다.
5. **TalkTalk은 coverage limitation**이다. 커머스 API에 TalkTalk 문의 endpoint가 존재하지 않으므로
   그 경로의 문의는 수집되지 않는다. 0건과 같은 뜻이 아니다.
6. **「지금 동기화」와 「기간 지정 수집」이 둘 다 `trigger=MANUAL`로 기록된다.** 두 버튼은 서로 다른
   창을 읽고 서로 다른 커서를 움직이는데, job 행만 보면 구분되지 않는다(커서 행이나 로그를 봐야
   안다). §4.3이 이번에 그 혼동을 실제로 겪은 기록이다.
