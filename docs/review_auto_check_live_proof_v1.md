# Review Auto-Check — Live Proof v1 (2026-10-09 → 10-10)

> **무엇을 증명한 문서인가.** 「자동 리뷰 확인」 lane이 실제 NAVER 스마트스토어센터에서 끝까지 도는지를
> 라이브로 확인한 기록이고, 그 과정에서 드러난 결함 네 개와 그 수정을 함께 담는다. 계약은
> `docs/review_auto_check_v1.md`가 갖는다 — 이 문서는 **관측**이지 계약이 아니다.
>
> **Scope.** NAVER · REVIEW · READ only. marketplace WRITE **0**(전 구간), 코드가 바뀔 때마다 재측정.

## 0. 환경

| | |
|---|---|
| 기준 커밋 | `ec608cab`에서 시작, 결함 수정 후 이 커밋 |
| 대상 | demo org `7146c50f…` · NAVER seller account `bdccb7a7…` (`demo@sellerops.ai`) |
| 실행 | 설치된 resident helper(`~/Library/Application Support/reviewnary-helper`), `REVIEWNARY_EXECUTION_PROVIDER=ASIDE` |
| 플래그 | `SELLEROPS_REVIEW_AUTO_CHECK_ENABLED` — 구간마다 ON, 매 구간 종료 시 OFF |
| 기준선 | 리뷰 4725 · WRITE `review_reply_outcome|execution|submission_ref` = **4 / 2 / 23** |

## 1. AUTH return-check — PASS

판매자가 **제품이 보지 않는 곳에서** 로그인하고 돌아오는 길.

```
22:49:35  tick today=1            SCHEDULED job 1건  requested 2026-10-09..2026-10-09
22:50:07  settle AUTH_REQUIRED
22:54:35  tick pausedAuth=1       새 job 0 · paused_reason=PAUSED_AUTH · next_check_at +6h
          ↑ next_check_at을 일부러 due로 되돌린 뒤의 tick이다. due인데도 읽지 않았다.
23:10:19→23:10:25  CHECK NOT_SIGNED_IN   (로그아웃 상태 복귀 — resume 0 · read 0)
          판매자가 다른 Aside 탭에서 직접 로그인. 제품은 그 사실을 모른다(sign-in 이벤트 0 · job 0).
23:48:09→23:48:12  CHECK SIGNED_IN
23:48:53  resume → ac-rs-bdccb7a7-1791557333  SCHEDULED  requested 2026-10-10..2026-10-10
23:49:24  SETTLED OBSERVED  observed=3  inserted=0  changed=0
```

- trigger는 **전 구간 SCHEDULED**, OPERATOR 작업 누계 14 → 14(`collectNow` 0).
- 복구 세션(`aw_sign_in_session_started`) **0건** — 확인은 probe 1회이고 창을 앞으로 가져오지 않는다.
- 한 번의 복귀에 요청이 3개 나가도 데스크를 쓰는 것은 1회다(나머지는 `busy`로 즉시 거절).
- WRITE delta **0** · 리뷰 4725 불변.

## 2. 결함 넷과 그 수정

### 2-1. dev 서버에서 이 화면의 모든 `await` 후처리가 죽어 있었다

`CollectNowAction`의 `live` ref는 언마운트에서 내려가기만 하고 다시 올라가는 곳이 없었다. React
**StrictMode**(dev 전용)가 하는 mount→cleanup→mount 한 번에 영원히 false가 되고, 그 뒤로 이 컴포넌트의
모든 `await` 이후가 조용히 버려진다 — probe는 나가서 `SIGNED_IN`을 받아오는데 resume은 호출되지 않았고,
화면에도 로그에도 아무 말이 없었다. 라이브에서는 **production 번들로 바꾼 뒤 같은 조작이 즉시 성공**해서
갈렸다. 수정: effect가 시작할 때마다 `live`를 다시 세운다. 회귀: StrictMode 아래 resume까지 가는 테스트.

### 2-2. 배포 빌드에서 도우미가 CSP에 막혀 있었다

CSP는 build에서만 주입되고, bridge origin은 **`VITE_ENABLE_AGENT_BRIDGE=true`일 때만** `connect-src`에
들어갔다. 그 플래그의 실제 역할은 개발자용 dock을 띄우는 것이다 — 즉 dev 플래그 하나가 **출고된 앱이
판매자의 도우미에 닿을 수 있는지**를 결정하고 있었고, 없으면 수집·로그인 복구·sign-in check가 전부
「도우미가 응답하지 않습니다」가 된다. 수정: 도우미 origin은 Agent Runtime과 같은 자격으로 **항상** 정책에
들어간다. 플래그는 dock만 가린다. 기존 테스트 하나가 **결함을 정답으로 못 박고 있었고**(「플래그가 없으면
origin도 없다」) 반대로 다시 썼다.

### 2-3. 성공한 뒤에도 pause가 풀리지 않았다

읽기가 OBSERVED로 끝나고 데스크가 READY인데 설정 행은 `PAUSED_AUTH`로 남았다 — 그 값을 지우는 유일한 손이
claimer의 **다음 차례**였고 그것은 6시간 뒤였다. 그동안 화면은 이미 로그인한 가게에 로그인하라고 말하고,
복귀할 때마다 쓸모없는 probe를 한 번씩 더 쓴다. 수정: **읽기가 도착하면 그 자리에서 푼다.** settle listener를
자리 하나에서 목록으로 바꿔 두 lane이 같은 보고를 듣게 하고(catch-up은 다음 창으로, 설정은 벽을 내려놓는다),
OBSERVED가 오면 pause를 지운다. 누가 읽었는지는 묻지 않는다 — 벽은 채널 세션에 대한 사실이고 성공한 읽기
하나면 거짓이 된다. 6시간 유보도 함께 사라진다(그것은 **벽에 대한** backoff였다). 되돌리기만 하고 당기지는
않는다.

### 2-4. 「오늘은 아직 리뷰가 없다」가 「화면을 읽을 수 없다」로 기록됐다

10-10 01:44·01:48, 자동 확인이 두 번 연속 `SURFACE_UNREADABLE / READING_REFUSED`로 끝났다. read-only로
관찰한 결과 화면은 멀쩡했다:

```
리뷰 관리 #/review/search, 기간 2026-10-10 ~ 2026-10-10
  .ag-row                       0
  .ag-root-wrapper              1        ← 그리드는 그려져 있다
  .ag-overlay-no-rows-wrapper   보임     ← ag-Grid 자신이 "리뷰가 존재하지 않습니다."
```

이 매장은 하루 3~12건이고 새벽 2시에 오늘치가 0건인 것은 정상이다. **하루가 시작될 때마다 lane이 고장 나
보이는 상태**였고, 고장 난 것은 「행이 0이면 그리드가 없는 것」이라는 한 줄이었다. 수정은
`docs/review_auto_check_v1.md`의 계약으로 들어간다(§ 빈 기간).

> **팝업은 원인이 아니었다.** 로그인 직후 센터 **메인**(`#/home/dashboard`)에 「스마트스토어센터 공지」
> 대화상자가 떠 있었지만, 읽기가 가는 **리뷰 관리 route에는 그 대화상자가 없다**(같은 세션으로 확인:
> `dialogs: []`, 그리드 정상 15행). READ·sign-in·legacy 랜딩이 모두 같은 deep link를 쓰고 있었고, 센터
> 메인으로 들어가는 경로는 없다. 그래서 팝업 자동 닫기는 **넣지 않았다** — 관측된 적 없는 원인에 대한
> 마켓플레이스 클릭을 심는 일이고, 그건 「숨은/연쇄 플랫폼 클릭 금지」 펜스가 가장 조심하는 자리다.
> 실제로 리뷰 route를 막는 팝업이 관측되면 **그 정확한 서명만** allow-list 한다.

## 3. 빈 기간의 계약 — 그리고 꾸미지 않은 귀속

읽기 성공의 조건은 넷이고, 넷이 모두 참일 때만이다: **예상 route** · **요청한 기간과 화면이 말하는 기간의
일치** · **그려진 그리드** · **NAVER 자신의 empty-state**. 그 결과는:

| | |
|---|---|
| outcome | `OBSERVED`, `observed_count = 0` |
| `identity_verdict` | **`UNRESOLVED`** — 행이 없으면 상품번호가 없고, 상품번호가 없으면 그 화면이 이 조직의 가게라는 증거가 없다 |
| 저장 | 0행 |
| coverage | **전진하지 않는다** (`window_start/end` 미기록) |
| AUTH pause | 해제된다 — 빈 읽기도 세션이 살아 있다는 증거다 |

**store identity를 MATCH로 꾸미지 않는다.** 증거 없이 「이 가게가 맞다」고 적으면 그 기록을 믿는 다음 사람이
확인할 방법이 없다. walk에서 이런 창을 만나면 그 날을 걸었다고 적는 대신 **거기서 멈추고 이름을 남긴다**
(`EMPTY_PERIOD_UNATTRIBUTED` — 포화를 뜻하는 `DAY_SATURATED`는 전혀 다른 이야기였다).

### 3-1. 행과 무관한 store identity 증거 — 있다, 그러나 지금은 대조할 상대가 없다

read-only 조사 결과 리뷰 관리 화면에는 행과 무관한 고정 증거가 **둘** 있다: 전역 내비의 **판매자 계정 식별자**와
좌측 내비의 **스토어 표시명**. 숨은/비공개 API가 아니라 화면에 찍힌 값이다. 백엔드에도 대조할 자리가 이미
있다 — `seller_accounts.store_identity`(64자). 그러나 이 계정의 그 칼럼은 **null**이라 지금 대조할 상대가
없다. 따라서 **B를 유지**한다. 연결 시점에 그 값을 채우면, 빈 기간도 `MATCH`로 귀속해 coverage까지 닫을 수
있다 — 그 설계는 이 문서의 범위가 아니다.

### 3-2. 거절이 자기 이름을 갖는다

`READING_REFUSED` 한 단어가 여덟 개의 서로 다른 페이지 사실을 덮고 있었고, 10-10 새벽에 그것이 한 시간을
먹었다 — 기록만으로는 `GRID_NOT_FOUND`였다는 것을 알 수 없어 판매자 Mac의 helper 로그를 열어야 했다. 이제
`GRID_NOT_FOUND` · `MODEL_UNREADABLE` · `MODEL_SHAPE_CHANGED` · `ROWS_NOT_LOADED` · `ID_LINK_MISMATCH` ·
`TOO_MANY_ROWS` · `ROUTE_MISMATCH`가 그대로 job의 `failure_code`에 남는다. 모르는 단어는 여전히
`READING_REFUSED`로 떨어진다.

## 4. 빈 기간 live proof — PASS

로그인된 상태, 오늘(2026-10-10) 리뷰 0건.

```
02:49:50  tick → ac-e37076d7-1791567831  SCHEDULED  requested 2026-10-10..2026-10-10
02:50:0x  SETTLED OBSERVED  observed_count=0  inserted=(null)
          identity_verdict = UNRESOLVED        ← 꾸미지 않았다
          window_start/window_end = (없음)      ← coverage를 적지 않았다
helper    aside_naver_review_read {ok:true, rows:0, windowStart:"2026-10-10", windowEnd:"2026-10-10",
                                   labelledTotal:0, gridReadMode:"EMPTY_STATE", emptyPeriod:true,
                                   identity:"UNRESOLVED", received:0, inserted:0, changed:0}
```

| 확인 | 결과 |
|---|---|
| outcome | **OBSERVED(0)** — `SURFACE_UNREADABLE` 아님 |
| `paused_reason` | **null** 유지 · `next_check_at` 평소 주기(+60분)로 복귀 |
| readiness | `localAgent READY` · `latestAttemptOutcome` **FAILED → SUCCESS** |
| coverage | `coverageThrough` **2026-10-09 불변** · gap 0 |
| WRITE delta | **0** (`4 / 2 / 23` 그대로) |
| 리뷰 | **4725 불변** |

**정직하게 적는 편차 하나.** `lastSuccessAt`은 움직이지 않는다(10-09 23:49 그대로). 읽기 기록(`sync_job`)은
귀속된 전달에서만 쓰이기 때문이고, 그것이 B의 직접적인 결과다. 화면은 「정상」이라고 말하지만 freshness
문장은 어제의 시각을 가리킨다 — 빈 날이 이어지면 그 간격이 벌어진다. 고치지 않고 기록한다.

## 5. 남은 것

- **2-3(settle 즉시 pause 해제)의 라이브 증명은 미완.** §4 시점의 행은 이미 pause가 없었다. 증명하려면
  로그아웃 → 벽 → 로그인 → 복귀를 한 번 더 돌려야 한다. 단위 회귀는 있다.
- 벽이 아닌 실패로 멈춰 있을 때 복귀마다 probe를 한 번씩 쓴다(수집 0 · resume 0이라 해는 없다).
- 10-09 22:49 tick이 park해 둔 것보다 5분 일찍 due가 된 건이 설명되지 않았다 — 별도 조사 메모로 남긴다.
