# 공급자 서버 범위와 API 계약 v1 (초안)

**상태:** 2026-10-17 초안. §0의 D1 · D2 · D3 · D4 · D6은 오너가 확정했고, D5(리뷰 사진)는 실제 내보내기 파일의
첨부 구조를 확인할 때까지 보류한다. D5에 걸린 §3.1 반입 열 목록은 그 확인 전에 구현하지 않는다.
**정본 관계:** 범위는 scope lock v1.14(`docs/product-scope-v1.md`) ①·⑦·⑧, 구역 분할과 보유 기준은
`docs/reviewnary_product_architecture_proposal_v1.md` §17.9가 정한다. 이 문서는 그 결정을 엔드포인트 · 저장
필드 · 상태 전이 · 삭제 시점으로 옮긴 것이며 새 제품 결정을 하지 않는다. 결정이 필요한 곳은 [PO] · [EXT] ·
[REPO]로 표시한다.

## 0. 오너 결정 (2026-10-17)

| # | 결정 | 함께 지킬 것 |
|---|---|---|
| D1 | 공급자 서버는 **별도 작은 앱** `vendor-server/` (Spring Boot 3.3.4 · Java 17, 기존과 같은 스택, 별도 Gradle 빌드) | 서버 산출물에서 채널 API 클라이언트와 로컬 credential vault를 뺀다. 기존 backend를 프로필로 막으면 endpoint 388개 · controller 104개를 하나씩 막아야 하고 "서버에 NAVER API 호출 코드가 없다"를 구조로 보일 수 없다. 별도 앱이면 fence test 하나로 증명된다(§10-1). **코드 분리는 설명 자료이지 플랫폼 허가를 대신하지 않는다**(§17.8). 대가: 로그인 · JWT 코드를 작게 다시 쓴다 |
| D2 | 파일럿 가입은 **초대 전용** | 판매자 · 담당자 · 설치 장치의 권한을 따로 두고, 각각의 회수 절차를 둔다(§2) |
| D3 | 담당자는 **2단계 인증 필수** | TOTP와 복구 코드로 시작한다. 공유 계정 없이 사람마다 계정을 둔다 |
| D4 | 답글이 필요 없는 리뷰는 **PC 저장 확인과 '대응 불필요' 판정이 모두 끝난 시점부터 90일을 상한**으로 보관 | 같은 리뷰를 다시 올려도 보관 시작점이 늦춰지지 않는다(§6) |
| D5 | 리뷰 사진은 **보류**. 공식 파일의 첨부 구조를 확인한 뒤 제공되는 첨부 1개를 우선 활용한다 | '여부 표시만'으로 제한하는 안은 채택하지 않았다(§3.1) |
| D6 | 설치는 **OS별 앱 묶음 + 기본 브라우저**(proposal §18.7 패키징 비교 A) | Mac · Windows 각각 설치 · 업데이트 · 복구를 검증한 뒤 지원으로 표시한다 |

## 1. 서버가 하는 일과 하지 않는 일

| 한다 | 하지 않는다 |
|---|---|
| 계정 · 설치 등록 · 라이선스 상태 | 채널 API 호출(Cafe24 토큰 엔드포인트만 예외, §5) |
| 담당자의 리뷰 파일 반입 · 열 제거 · 보관 | 문의 · 상품 · 지식 · 응대 기준 보관 |
| 리뷰 대행 작업의 상태 정본(승인 · 철회 · 착수 · 결과) | 초안 생성 · AI 호출(§17.9: 초안은 PC가 만든다) |
| Cafe24 토큰 중계와 응답 유실 복구 | 임의 명령 · URL · 파일 읽기 전달(§17.6) |
| 요청자 공인 IPv4 응답 | 판매자 PC로의 원격 접속 · 푸시 실행 |

**서버 → PC 방향의 요청은 없다.** PC가 항상 먼저 묻고(pull), 서버는 상태와 자료만 돌려준다. 서버가 PC에게 무엇을
"하라"고 지시하는 필드를 응답에 두지 않는다.

## 2. 행위자와 인증

| 행위자 | 인증 | 볼 수 있는 범위 |
|---|---|---|
| 판매자(휴대폰 · 브라우저) | 이메일 · 비밀번호 → 세션 JWT (기존 `JwtTokenProvider` 형태) | 자기 스토어의 리뷰 · 작업 |
| 설치(판매자 PC) | 장치 승인 흐름 → 불투명 설치 토큰(`rvi_` 접두사, 서버는 SHA-256만 저장) + 설치 공개키 | 자기 계정 스토어의 리뷰 · 작업 동기화, Cafe24 중계 |
| 담당자(공급자 직원) | 이메일 · 비밀번호 + TOTP(D3) | 배정된 스토어의 리뷰 업로드 · 승인된 작업 |
| 관리자 | 담당자와 같음 + 역할 | 배정 · 계정 관리. 원문 열람은 배정 범위로만 |

**권한과 회수는 행위자마다 따로 둔다(D2).** 판매자 계정 정지는 그 계정의 세션과 설치 토큰을 함께 끊는다. 설치
하나만 회수(분실 PC)해도 계정은 유지된다. 담당자 회수는 그 사람의 세션과 스토어 배정을 끊되, 이미 착수(`CLAIMED`)한
작업은 다른 담당자에게 넘기지 않고 `UNKNOWN`으로 두어 확인을 거친다. 담당자는 TOTP와 일회용 복구 코드를 쓰고(D3),
복구 코드는 해시만 저장한다.

설치 등록은 기존 helper 장치 인증(`auth/device/HelperDeviceService`, RFC 8628 장치 승인)과 같은 모양을 쓴다:
PC가 코드를 받아 보여 주고 → 판매자가 웹에서 그 코드를 승인 → PC가 토큰을 받는다. 차이는 PC가 토큰을 받을 때
**설치 공개키(X25519)를 함께 등록**한다는 점이다 — §5의 토큰 봉인에 쓴다. 개인키는 PC의 OS 비밀 저장소에만 있다.

## 3. 리뷰 교환

### 3.1 반입 — 담당자 업로드

`POST /v1/delegate/stores/{storeId}/review-uploads` (multipart, xlsx, 최대 10MB — 기존 업로드 한도와 같음)

1. 파일을 격리 경로에 쓰고 xlsx 형식을 확인한다(collector `sniffXlsxReadable`와 같은 검사).
2. 머리글을 `contracts/review-export/naver/v1`의 25개 열과 대조한다. 계약에 없는 열이 있으면 반입하지 않고
   담당자에게 알린다(조용히 무시하지 않는다 — 화면 변경을 놓치지 않기 위해).
3. **허용 열만 읽는다.** 아래 표의 열만 행 객체로 만든다. 나머지 열은 메모리에도 행 객체로 올리지 않는다.
4. `(storeId, 리뷰글번호)`로 중복을 거른다. 같은 번호에 본문 · 수정일이 바뀌었으면 새 리뷰가 아니라 **변경 사건**이다(§4.2 무효화).
5. 성공하면 원본 파일을 즉시 지운다. 실패하면 격리에 두고 `quarantine_until = 업로드 + 24h`에 정리 작업이 지운다.

| 저장(허용) | 원본 열 | 저장 안 함 | 원본 열 |
|---|---|---|---|
| 리뷰 번호 | 리뷰글번호 | 구매자 | 등록자 |
| 상품 번호 · 상품명 | 상품번호 · 상품명 | 주문 | 상품주문번호 |
| 평점 | 구매자평점 | 회원 입력 항목 | 유저정보 등록 항목 |
| 리뷰 본문 | 리뷰상세내용 | 운영 부가 정보 | 리뷰도움수 · 베스트리뷰(+선정일시) · 이벤트번호 · 혜택지급(+일시) · 풀필먼트사 · 리뷰이동일 |
| 리뷰 구분 · 사진/영상 여부 | 리뷰구분 · 포토/영상 | | |
| 작성 · 수정 시각 | 리뷰등록일 · 최종수정일 | | |
| 전시 상태 | 전시상태 | | |
| 답글 여부 · 답글 시각 | 답글여부 · 답글등록일시 | | |
| 관련 리뷰 | 관련리뷰글번호 · 관련리뷰상세내용 | | |

**위 표는 합성 계약 기준이며 실제 파일로 확인하기 전에는 확정하지 않는다(D5).** `contracts/review-export/naver/v1`은
실제 열 이름을 본떠 만든 합성 파일이다. 그 파일에 첨부가 없다고 해서 실제 내보내기에도 없다고 볼 수 없다.

| 항목 | 지금 아는 것 |
|---|---|
| 리뷰 첨부 | 네이버 판매자센터 FAQ: 엑셀로 내려받으면 리뷰 첨부(이미지/동영상) **1개**만 조회되고, 여러 첨부는 판매자센터에서 개별 확인하라고 안내한다 → 첨부 1개 경로는 있다 |
| 여러 사진 · 동영상 전체 | 엑셀만으로는 확보할 수 없다 |
| 첨부의 형태 · 어느 것이 실리는지 | 모른다 — 주소 문자열인지, 하이퍼링크인지, 시트에 삽입된 이미지인지, 첫 번째 첨부인지 실제 파일로 확인한다 |
| 기존 답글 본문 | 합성 계약의 열에는 없다. 첨부 안내가 이것까지 말해 주지는 않으므로 실제 파일로 같이 확인한다 |
| 상품 옵션 | 합성 계약의 열에는 없다. 같이 확인한다 |

확인 방법: 실제 내보내기 파일 하나의 **구조만**(열 이름 · 셀 종류 · 하이퍼링크 · 삽입 객체 유무) 기록하고, 내용은
저장소에 넣지 않는다. 그 결과로 합성 계약을 고치고(`naver/v1`을 고칠지, `v2`를 얹을지는 그때 정한다) 위 허용 열 표를
확정한다. 판매자센터에서 파일을 내려받는 일은 실계정 작업이므로 라이브 승인 정본
(`docs/standing/sellerops_live_approval_contract.md`)을 따른다. 그 전까지 담당자는 등록 직전에 화면에서 기존 답글을
확인한다(§4.3).

첨부를 쓰게 되면 첨부 파일도 리뷰 원문과 같은 보유 기준(§6)을 따르고, AI로 보낼 때는 §17.9의 사진 검사 경계를 따른다.

본문 지문은 `contracts/review-fingerprint/v1`(Java `ReviewBodyFingerprint`)을, 리뷰 식별 지문은
`contracts/review-id-fingerprint/v1`을 그대로 쓴다 — PC와 서버가 같은 값을 계산한다.

### 3.2 PC로 전달

- `GET /v1/stores/{storeId}/reviews?cursor=` (설치 토큰) → 새 리뷰와 변경 사건. 원문 그대로다(§17.9: 판매자에게는 원문).
- `POST /v1/stores/{storeId}/reviews/ack {cursor}` → PC가 로컬 DB에 저장했음을 확인한다.
- `POST /v1/stores/{storeId}/reviews/{reviewId}/disposition {noReplyNeeded: true, commandId}` → PC(또는 판매자)가 이 리뷰에
  답글이 필요 없다고 판정했음을 알린다. 저장 확인과 이 판정이 둘 다 있어야 D4의 90일이 시작된다. 판정 없이 저장만 된
  리뷰는 진행 중 자료로 남아 정리 안내 대상이 된다.

PC는 받은 리뷰를 기존 리뷰 레인(`reviews` 테이블, `external_id` = 리뷰글번호)으로 반입한다. AI로 보낼 때는 §17.9의
로컬 정제를 거친다 — 서버는 정제본을 만들지 않는다.

## 4. 리뷰 대행 작업 — 모바일 승인 상태 머신

### 4.1 작업 레코드

| 필드 | 내용 |
|---|---|
| `task_id`, `store_id`, `review_id` | |
| `state`, `state_version` | 상태와 낙관적 잠금 번호. 모든 전이는 `UPDATE … WHERE state = ? AND state_version = ?` (기존 `AgentRunRepository` 방식) |
| `draft_version`, `draft_body`, `draft_fingerprint` | 기존 `ReviewReplyFingerprint`(`review-reply-v1`)와 같은 계산 |
| `review_body_fingerprint` | 초안을 만들 때 본 리뷰 본문 |
| `generated_at`, `knowledge_fp`, `policy_fp`, `voice_fp` | §17.9 초안 메타 — 서버는 판단하지 않고 들고만 다닌다 |
| `edited_on_mobile`, `server_checks` | 휴대폰에서 고쳤는지, 서버의 지식 없는 검사(연락처 · URL · 길이) 결과 |
| `pc_recheck` | 휴대폰 수정 뒤 PC가 다시 검사한 결과(근거 · 금지 표현) |
| `approved_version`, `approved_fingerprint`, `approved_by`, `approved_at` | 승인은 정확한 버전 · 지문에 묶인다 |
| `claimed_by`, `claimed_at` | 착수한 담당자 |
| `outcome`, `posted_fingerprint`, `reported_at`, `report_method` | 담당자 보고 |
| `purge_after` | §6 보유 기준으로 계산 |

모든 쓰기 요청은 `commandId`를 받아 같은 요청의 재전송을 한 번으로 처리한다.

### 4.2 상태

```text
AWAITING_APPROVAL ──승인──▶ APPROVED ──착수──▶ CLAIMED ──보고──▶ POSTED · FAILED · UNKNOWN
   │   ▲                      │                  │
   │   └─휴대폰 수정(새 버전)   │취소               │취소 요청
   │                          ▼                  ▼
   ├─정책 지문 다름─▶ RECHECK_NEEDED            CANCEL_REQUESTED ──담당자 확인──▶ CANCELLED · POSTED
   ├─취소─▶ CANCELLED      CANCELLED
   └─리뷰 변경 · 삭제 · 이미 답글─▶ INVALIDATED   (APPROVED에서도 같은 사건이면 INVALIDATED)
```

- **취소:** `APPROVED`까지는 즉시 `CANCELLED`. `CLAIMED`에서는 `CANCEL_REQUESTED`가 되고 담당자가 저장 직전에 확인한다.
- **고정 기한 없음(2026-10-17 오너 결정).** 무효화는 사건으로만: 리뷰 변경 · 삭제(다음 반입에서 관측), 이미 답글
  있음(`답글여부=Y`), 판매자 취소, 미승인 건의 정책 지문 변경.
- **정책 재확인:** PC가 켜지면 `POST /v1/stores/{storeId}/tasks/policy-check {policy_fp, knowledge_fp, voice_fp}`.
  서버는 `AWAITING_APPROVAL` 중 지문이 다른 건을 `RECHECK_NEEDED`로 돌리고 목록을 돌려준다. `APPROVED`는 판매자의
  결정이라 상태를 바꾸지 않고 응답에 "철회 제안" 목록으로만 담는다. `CLAIMED` 이후는 건드리지 않는다.
- **`UNKNOWN`:** 담당자가 확인할 때까지 재등록하지 않는다. 다음 반입에서 `답글여부=Y`가 보이면 담당자가 확인해
  `POSTED`로 고친다.
- **증거 등급:** PC에 반영할 때 `POSTED`는 기존 쌍 `OPERATOR_REPORTED_SUBMITTED` + `UNVERIFIED`로만 기록한다(v1.14 ⑨,
  `attention/reply/OperatorOutcome`). `COMPLETED`는 쓰지 않는다. 다음 반입의 `답글여부=Y`를 별도 증거 등급으로
  올릴지는 [PO] — 이 계약에서 새 등급을 만들지 않는다.

### 4.3 엔드포인트

| 누가 | 요청 | 결과 |
|---|---|---|
| PC | `POST /v1/stores/{storeId}/tasks` {reviewId, draftVersion, body, 초안 메타} | `AWAITING_APPROVAL` 생성. 같은 리뷰에 열린 작업이 있으면 새 버전으로 갱신 |
| PC | `GET /v1/stores/{storeId}/tasks/changes?cursor=` | 상태 변경 동기화(서버가 정본) |
| PC | `POST /v1/tasks/{id}/pc-recheck` {draftVersion, 결과} | 휴대폰 수정본의 지식 기반 검사 결과 기록 |
| PC | `POST /v1/stores/{storeId}/tasks/policy-check` | §4.2 |
| 휴대폰 · PC | `GET /v1/stores/{storeId}/tasks?state=` · `GET /v1/tasks/{id}` | 리뷰 원문 + 초안 + 메타 + 표시(휴대폰 수정 · 미검사) |
| 휴대폰 · PC | `PUT /v1/tasks/{id}/draft` {baseVersion, body} | 새 버전, `edited_on_mobile`, 서버 검사. `baseVersion`이 최신이 아니면 409 |
| 휴대폰 · PC | `POST /v1/tasks/{id}/approve` {version, fingerprint, commandId} | 최신 버전 · 지문과 같을 때만 `APPROVED` |
| 휴대폰 · PC | `POST /v1/tasks/{id}/cancel` {commandId} | §4.2 취소 규칙 |
| 담당자 | `GET /v1/delegate/tasks?state=APPROVED` | 배정 스토어의 승인 건 |
| 담당자 | `POST /v1/delegate/tasks/{id}/claim` {stateVersion} | 한 명만 성공(조건부 UPDATE) |
| 담당자 | `GET /v1/delegate/tasks/{id}/preflight` | 저장 직전 재확인: 현재 상태 · 승인된 문장 · 지문. 응답을 못 받으면 등록하지 않는다 |
| 담당자 | `POST /v1/delegate/tasks/{id}/report` {outcome, postedFingerprint, checkedAt, method} | `POSTED` · `FAILED` · `UNKNOWN`, 또는 `CANCEL_REQUESTED`였으면 `CANCELLED` |
| 담당자 | `POST /v1/delegate/tasks/{id}/release` | 착수 전 반납 → `APPROVED` |

담당자 화면은 승인된 문장을 복사하는 것까지만 돕는다. 판매자센터에 대신 입력하거나 저장하지 않는다(§17.6).

## 5. Cafe24 토큰 중계

§17.7의 설계를 엔드포인트로 옮긴다. 서버가 쥐는 비밀은 **공급자 앱의 client secret 하나**다.

| 단계 | 요청 | 서버가 하는 일 |
|---|---|---|
| 연결 시작 | PC `POST /v1/cafe24/authorize` {mallId, localPort} | 기존 `Cafe24OnboardingService.start`와 같은 state(32바이트, 해시만 저장, TTL 600초, 설치 · 몰당 하나). 인가 URL 반환. `localPort`는 1024–65535만 |
| 콜백 | 브라우저 `GET /v1/cafe24/callback` (Cafe24에 등록하는 유일한 redirect URI) | state 검증 · 소진, `mall_id` 일치 확인, 코드 교환, **결과를 설치 공개키로 봉인**해 보관. 브라우저는 `http://127.0.0.1:{localPort}/connect/cafe24/result?status=…&ticket=…`로 보낸다(코드 · 토큰은 URL에 없음) |
| 수령 | PC `POST /v1/cafe24/pickup` {ticket} | 봉인된 결과 반환 |
| 갱신 | PC `POST /v1/cafe24/refresh` {mallId, refreshToken} | 멱등 키 = SHA-256(refreshToken). 그 키의 봉인 결과가 있으면 Cafe24를 다시 부르지 않고 돌려준다. 없으면 설치 · 몰당 하나만 진행(행 잠금), Cafe24 호출, 봉인 보관, 반환 |
| 저장 확인 | PC `POST /v1/cafe24/ack` {ticket 또는 키} | 봉인 결과 즉시 삭제 |

- 봉인 결과는 확인이 없으면 **최대 72시간** 뒤 정리 작업이 지운다(§17.9 표). 그 뒤 PC가 소진된 refresh token만
  가졌다면 재연결(OAuth)로 복구한다.
- 서버는 `(install_id, mall_id)` 묶음을 저장한다 — 연결한 설치만 그 몰을 갱신할 수 있게 하기 위한 최소 식별자다.
- 요청 본문의 refresh token은 처리 중 메모리에만 있고 로그 · 오류 추적에 남지 않는다. 봉인 전 평문을 DB에 쓰지 않는다.
- **PC 쪽 변경 [REPO].** 지금 `Cafe24Authorizer.authorize`는 부를 때마다 refresh하고, 유효한 access token을 재사용하는
  캐시가 없다(`Cafe24TokenClient` javadoc). 실제 HTTP 요청마다 부르는지는 호출부마다 다르다. 중계를 거치면 그만큼
  서버 왕복과 Cafe24 갱신이 생긴다. 판매자 PC 모드에서는 다음 순서로 바꾼다.
  1. 유효한 access token을 재사용한다. 기준은 고정 "2시간"이 아니라 **응답의 `expires_at`**이다(Cafe24가 만료 시각을
     돌려준다, `Cafe24TokenResult`).
  2. 만료 직전 여유 구간에 들어오면 갱신한다. 여유 폭은 구현 때 정하고 테스트로 고정한다.
  3. 같은 연결의 동시 갱신은 한 번만 한다. 지금의 프로세스 안 잠금(`Cafe24AccountRefreshLocks`)을 그대로 쓰고, 기다린
     호출은 갱신된 토큰을 받는다. 서버 쪽 단일 진행과 멱등 키가 프로세스 재시작 같은 나머지 경우를 맡는다.
- refresh token 수명은 2주라 PC가 2주 넘게 꺼져 있으면 재연결이 필요하다 — 연결 화면에 안내한다.
- Cafe24 개발자센터에 등록할 redirect URI는 이 서버의 콜백 하나다. 기존 pilot의 `/api/connect/cafe24/callback`과
  경로가 다르므로, 기존 앱을 재사용할지 새 앱을 등록할지는 [PO] (기존 pilot 연결을 끊지 않으려면 둘 다 등록).

## 6. 보유와 삭제 — §17.9 표의 실행 규칙

| 대상 | 삭제 시점 | 실행 |
|---|---|---|
| 업로드 원본 엑셀 | 반입 성공 즉시 / 실패는 24시간 | 반입 트랜잭션 후 삭제 + 정리 작업(15분 주기, 기존 `AuthHandoffJanitor` 형태) |
| 진행 중 리뷰 · 초안 · 승인 | 업무가 끝날 때까지 | 30일 넘게 움직임이 없으면 판매자에게 정리 안내만(자동 삭제 아님) |
| 완료된 작업과 그 리뷰 | `POSTED` · `FAILED` 확정 또는 `CANCELLED` · `INVALIDATED` 후 90일 | `purge_after` 기준 정리 작업 |
| 답글이 필요 없는 리뷰 | PC 저장 확인과 '대응 불필요' 판정이 **둘 다** 끝난 시점부터 90일 상한(D4) | 같은 정리 작업. 시작점은 한 번 정해지면 같은 리뷰의 재업로드로 늦춰지지 않는다 — 재업로드는 변경 사건(§3.1 4)만 만든다 |
| Cafe24 봉인 결과 | 저장 확인 즉시 / 최대 72시간 | ack 시 삭제 + 정리 작업 |
| Cafe24 OAuth state | 만료 후 | 정리 작업. 기존 backend는 이 표를 지우지 않는다 — 서버에서는 지운다 |
| 공인 IP 응답 | 저장하지 않음 | §7 |

**백업은 별도로 설계한다 — "최대 14일 더 남는다"고 말할 수 없다.** 기존 `deploy/pilot/backup.sh`는 호스트 안의
덤프만 `PILOT_BACKUP_KEEP_DAYS`(기본 14일)로 지운다. 외부(S3) 사본은 **버킷 수명주기 정책이 지우고, 스크립트는 원격
객체를 지우지 않는다**(업로더 자격증명이 PutObject 전용이라서 — 스크립트 주석 34–37행). 그 정책의 실제 설정은 아직
확인하지 않았다 [EXT]. 처리방침에 추가 보관 기간을 적는 것만으로 해결되지도 않는다 — 불필요해진 개인정보의 파기
의무를 기준으로 백업의 필요성과 삭제 방법을 함께 정한다.

1. 운영 데이터의 보관(§6 표)과 재해복구 백업의 보관 기간을 따로 정의한다. 백업 기간은 [PO].
2. 백업은 일반 조회에 쓰지 않고 접근을 복구 담당으로 제한한다.
3. 복원하면 서비스를 열기 전에 삭제 대상 목록을 다시 적용한다. 그러려면 삭제 기록(무엇을 언제 지웠는지 — 내용 없이
   식별자만)이 백업과 별도로 남아 있어야 한다.
4. Cafe24 봉인 결과는 일반 백업에서 뺀다(별도 테이블 · 덤프 제외). 72시간 뒤 쓸모가 없고 복원해서도 안 된다.
5. 호스트 덤프 · S3 사본 · 볼륨 스냅샷까지 실제 모든 사본의 삭제 시점을 검증한다.

EC2 볼륨 · 백업 대상의 저장 암호화도 AWS 콘솔에서 확인한다 [EXT].

## 7. 공인 IP 확인

`GET /v1/ip` (설치 토큰 필요 — 공개 IP 조회 서비스로 쓰이지 않게) → `{"ipv4": "x.x.x.x"}`

- **IPv4 전용 호스트명**(A 레코드만, AAAA 없음)으로 제공한다. NAVER 호출 IP 등록은 IPv4이고(`NaverAdvertisedEgress`가
  IPv4만 받음), PC가 IPv6로 서버에 닿으면 엉뚱한 주소를 안내하게 된다.
- 요청자 주소는 Caddy가 붙인 `X-Forwarded-For`의 마지막 값만 믿는다. Spring은 Caddy 컨테이너만 신뢰하는 프록시로 둔다.
- 저장 · 로그 없음. 현재 Caddyfile에는 `log` 지시어가 없어 접근 로그가 꺼져 있다 — 그 상태를 유지하고 fence로 고정한다.
- 연결 화면은 이 값을 "이 PC의 공인 IP"로 보여 주고, 서버에 닿지 않으면 지금의 수동 안내로 돌아간다(§17.9).

## 8. 라이선스

`POST /v1/installs/me/heartbeat` {appVersion, osFamily} → `{licenseStatus, validUntil, latestVersion}`

보내는 필드는 이 두 개뿐이다(§17.3: 스토어 ID · 원문 · 작업 목록을 몰래 붙이지 않는다). 서버에 닿지 않아도 로컬
업무와 이력 열람은 유지한다. 유예 기간은 [PO] 미정이며, 결제 문제로 데이터를 막거나 미확인 발송을 재시도하지 않는다.

## 9. 배치 — 기존 AWS 자산 재사용

`deploy/pilot`의 형태(Caddy가 TLS를 끝내는 단일 공개 출입구 + compose + Postgres + `backup.sh` · `restore.sh` ·
`preflight.sh`)를 그대로 쓰고, backend 대신 `vendor-server`를 넣는다. 기존 pilot compose는 보류 상태로 보존한다(v1.14 ⑧-2).

- 서버도 Sentry · 분석 도구 · 요청 본문 로그를 끈다(§17.9 경계 5). 오류는 본문 없는 코드와 요청 ID만 남긴다.
- 외부로 나가는 곳은 `*.cafe24api.com`의 토큰 엔드포인트 하나다. 기존 `OutboundGate`와 같은 허용 목록을 서버에도 둔다.

## 10. 구현 전에 고정할 테스트

1. **서버에 채널 클라이언트가 없다:** `vendor-server/` 소스에 NAVER · Coupang 호스트와 `com.sellerops.connector` 류
   import가 없음을 재귀 스캔으로 확인하고, 일부러 넣으면 실패하는 것을 함께 보인다.
2. **열 제거:** 계약 fixture의 `MUST-NOT-PERSIST` 표식이 DB · 응답 · 로그 어디에도 없음. 계약에 없는 열이 있는 파일은 거부.
3. **상태 경합:** 취소와 착수, 담당자 두 명의 착수, 휴대폰 수정과 승인이 동시에 와도 한 전이만 성립.
4. **중계 응답 유실:** Cafe24 성공 후 응답을 버리고 같은 refresh token으로 다시 요청 → Cafe24 호출 1회, 같은 결과.
5. **보유:** 시계를 옮겨 24시간 · 72시간 · 90일 경계에서 정확히 지워지고, 하루 전에는 남아 있음. 같은 리뷰를
   다시 올려도 90일 시작점이 바뀌지 않음. 백업을 복원한 뒤 삭제 기록을 다시 적용하면 지운 행이 남지 않음.
6. **봉인:** 서버 DB · 로그에 토큰 평문이 없음(합성 표식으로 확인), 다른 설치의 키로는 열리지 않음.

모바일 승인 화면과 담당자 화면은 A7 FE freeze에 대한 새 delta다 — 화면 단위로 따로 확인받은 뒤 만든다.
