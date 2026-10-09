# Review Auto-Check v1 — 자동 리뷰 확인 (제품 계약 · 구현 계약)

**상태:** 구현 완료, 라이브 증명 대기 (2026-10-09). **범위:** NAVER REVIEW.
**브랜치:** `feat/review-auto-check-v1`.

## 1. 제품 계약

SellerOps는 판매자가 매번 「지금 수집하기」를 누르는 제품이 아니다.

- 정상 상태에서 Reviewnary가 **주기적으로 REVIEW를 READ**한다 (계정당 60분).
- 빠진 과거가 있으면 **같은 deterministic catch-up primitive**로 자동 복구한다.
- 「지금 확인」은 **수동 refresh**이고 secondary다.
- 로그인이 필요하면 자동 작업은 **PAUSED_AUTH**. 판매자가 로그인하면 **원래 작업을 이어간다**.
- **WRITE는 0.** credential / MFA / CAPTCHA 자동화는 없다.

### 승인 모델 — grant가 아니라 제품 설정

판매자에게 grant·scope·범위·기기·주기를 묻지 않는다. 연결 과정에 한 줄이면 충분하다:

> 연결하면 새 리뷰를 자동으로 확인합니다 — 설정에서 언제든 끌 수 있습니다.

| | |
|---|---|
| 테이블 | `review_auto_check` (V126) |
| 범위 | `org + sellerAccount + dataType(REVIEW) + mode(READ_ONLY)` |
| 기본값 | 연결된, 읽을 화면이 있는 계정에 대해 **ON** |
| 끄는 길 | 판매자가 설정에서 끈다 → `enabled=false`, `revoked_at` |
| 다시 켜지는 조건 | 판매자가 켤 때만. `revoked_at`은 **묘비**이고, 자동 채택 규칙이 이 행을 되살리지 않는다 |
| READ_ONLY | **기능 불변조건.** 쓰기 recipe는 존재하지 않고 구조 테스트가 거절한다. 칸은 그 사실을 행이 말하게 하려고 있다 |

**device_id는 없다.** 도우미는 승인 대상이 아니라 실행 수단이다. helper token 180일 만료·재설치·새 맥은
`HelperDeviceService.redeem`에서 매번 **새 device 행**을 만들고, 설정을 기기에 묶으면 그때마다 판매자에게
재동의를 요구하게 된다 — 기기 수명을 판매자 의사로 착각하는 일이다. `channel_id`도 없다:
`seller_accounts.channel_id`에서 유도된다.

## 2. trigger 세 값

`AsideTrigger`: `OPERATOR` / `SCHEDULED` / `RESPONSIBILITY` (V125가 CHECK로 고정).

| trigger | 승인 근거 | 기간 |
|---|---|---|
| `OPERATOR` | 누름 그 자체 | 자유 (화면이 보여주는 기간도 가능 — 사람이 보고 있다) |
| `SCHEDULED` | `review_auto_check` 행 | **필수**. windowless dispatch는 `AsideDispatch`와 CHECK가 둘 다 거절 |
| `RESPONSIBILITY` | 배포가 조직 + 계정을 지명 (`AsideMarketplaceAccess`) | 변경 없음 |

`SCHEDULED`가 기간을 반드시 지명하는 이유: 화면이 그때 보여주던 기간은 마켓플레이스의 설정이고 이 제품이
고른 적이 없다. 그 기간으로 읽으면 「어젯밤 자동 확인은 무엇을 덮었나」에 답할 수 없다.

`review_catch_up_run.trigger_source`(V127)가 walk의 출처를 들고 있고, 자식 job이 그 값을 가져간다 —
몇 시간에 걸친 walk와 한 번의 재개를 지나서도 감사 기록이 어긋나지 않는다. 재개는 이 값을 바꾸지 않는다.

## 3. 실행 — 두 모양

`ReviewAutoCheckReconciler.turn()` 하나가 계정 하나의 한 차례다.

1. **빈 과거가 있다** → catch-up walk (`startScheduled`). 기존 primitive 그대로, **어제까지**.
2. **과거가 닫혀 있다** → `today … today` 단일 dispatch. 부모(`catch_up_run_id`)가 없다 — 전진시킬
   경계가 없으므로 셀 것이 없다. `clientJobId = ac-<row8>-<today>`이므로 하루에 한 번으로 수렴한다.

자동 lane은 **별도 수집 구현을 만들지 않는다.** recipe, workflow, store fence, lease, single-use claim,
one-job-per-device는 전부 `ScheduledAsideJobService.dispatch` 하나를 지난다.

### device 해결 — 실행 시점에, 매번

1. 이 `sellerAccount` × recipe를 **마지막으로 OBSERVED한** job의 `device_id`가 아직 live면 그것.
2. 아니면 조직의 가장 새 live grant (`AsideHelperDevices.linked`).
3. 하나도 없으면 `PAUSED_DEVICE`.

「조직의 가장 새 grant」만 쓰면 맥이 둘인 판매자에게서 이 스토어에 로그인되어 있지 않은 쪽으로 일이 가고,
창 하나를 「로그인 필요」를 배우는 데 쓴다.

## 4. 사람이 앞선다

| 상황 | 동작 |
|---|---|
| 자동 확인이 읽는 중에 판매자가 「지금 확인」을 누름 | **진행 중인 그 작업을 관찰**한다. 409도, 두 번째 job도 없다 (`ScheduledAsideJobService.liveFor`, `ReviewCatchUpOrchestrator.inFlightChild`) |
| claimed 작업 | **취소하지 않는다.** 지금 판매자의 화면을 읽고 있고, 뜯으면 그 창을 잃는다 |
| 사람의 작업이 책상에 있을 때 tick | 비켜선다 (`Outcome.SKIPPED`). scheduler는 사람보다 앞서지 않는다 |
| 판매자가 설정을 끔 | 다음 창에서 즉시 효력. 게이트는 창마다 묻고 캐시하지 않는다 |

## 5. 멈춤 둘 — 하나는 시간이, 하나는 사람이 푼다

| | 언제 | 어떻게 풀리나 |
|---|---|---|
| `PAUSED_DEVICE` | live helper가 하나도 없다 | **다음 tick이 저절로** 복구. 설정 불변 |
| `PAUSED_AUTH` | 자식이 `AUTH_REQUIRED`로 settle (멈춘 창을 기억) | **scheduler는 재시도하지 않는다.** 판매자가 로그인할 때 그 창부터 이어진다 |

벽을 매시간 다시 때리는 lane은 판매자만 해결할 수 있는 일에 대해 소음을 만드는 제품이다.

### 로그인 → resume, 정확히 한 번

- 엔드포인트 하나: `POST /api/seller-accounts/{id}/collect-now/resume`. 입력은 `dataType`뿐.
- `PAUSED_AUTH → RUNNING` **조건부 상태 전이**가 1회성을 보장한다. 두 번째 알림은 RUNNING을 보고 no-op.
- **로그인 상태는 저장하지 않는다** (2026-10-08 제품 결정 유지). 새 컬럼 없음.
- 화면은 OPERATOR/SCHEDULED를 판단하지 않는다. 「로그인이 확인됐다」만 전하고, 서버가 그 row의 trigger
  그대로 이어간다.

## 6. coverage는 내부 invariant, UI는 freshness

`lastClosedDay = today(KST) - 1`.

- `coverageThrough = min(oldest.end, lastClosedDay)` — 아침 9시에 오늘까지 읽었다는 것은 사실이지만,
  「오늘까지 빠짐없이」는 오후 2시에 거짓이 된다.
- gap도 `lastClosedDay`까지. catch-up plan도 거기까지 — 오늘을 계획에 넣으면 그 창은 settle되고 아무것도
  증명하지 못하고 다시 계획된다(COMPLETE에 도달할 수 없는 walk).
- 오늘 읽기는 **freshness**로 표현한다: `AcquisitionHistory.lastSuccessAt` → 「오늘 13:40 확인」.
- 정상 UI는 freshness 한 줄. **gap이나 실패·멈춤이 있을 때만** 과거 확인 상태를 보여준다
  (`coverageSentence`는 `gapDays > 0`에서만 문장을 만든다).

증거 행(`scheduled_aside_job.window_start/end`)은 실제로 읽은 기간을 계속 말한다. clamp는 순수 merge
단계에서만 일어나므로 마이그레이션도, 저장된 증거의 재작성도 없다.

## 7. 왜 NAVER REVIEW만인가

「windowless 금지」가 범위를 정한다. 명시 window를 지킬 수 있는 실행기는 NAVER뿐이다:

- `collector/src/aside/naver-review-observe-runner.ts` — window 처리 있음. `start = end`(하루)도
  계획되고 실행된다 (`naver-review-window-read.test.ts`, 2026-10-09에 추가).
- `collector/src/aside/coupang-observe-runner.ts` — 없음. 그리고 `setWindowStart` 호출자는
  `NaverReviewObservationService` 하나뿐이라 Coupang 읽기는 지금도 coverage를 전진시키지 못한다.

Coupang 자동 확인은 runner의 기간 이동 + ingest의 requested/actual 검증이 먼저 필요한 **별개 패키지**다.

## 8. 배포 플래그

```
SELLEROPS_REVIEW_AUTO_CHECK_ENABLED=true     # 기본 false
SELLEROPS_REVIEW_AUTO_CHECK_POLL_INTERVAL_MS # 기본 300000 (런타임이 들여다보는 간격)
```

조직 목록도 계정 목록도 없다. 배포가 정하는 것은 「여기서 이 lane이 도는가」 하나이고, 「누구의 스토어를
읽는가」는 판매자의 설정이 정한다. Self-Pilot / Responsibility의 허용 목록은 다른 질문이며 넓혀지지 않는다.

## 9. 테스트

| 어디 | 무엇 |
|---|---|
| `backend/.../autocheck/ReviewAutoCheckTest.java` (15) | 기본 ON · 끄면 꺼진 채 · API 채널엔 설정 없음 · 설정 없이 SCHEDULED 없음 · windowless 거절 · gap→walk · 닫힌 과거→오늘 · 하루 한 번 · 누름이 앞섬 · tick이 비켜섬 · PAUSED_DEVICE 자동 복구 · device 우선순위와 fallback · PAUSED_AUTH는 사람이 푼다(1회성) |
| `backend/.../coverage/ReviewCoverageTest.java` | 오늘은 경계가 될 수 없다 · 오늘만 읽은 증거는 경계를 못 만든다 · 창의 닫힌 부분은 남는다 |
| `backend/.../coverage/ReviewCatchUpPlanTest.java` | 어제까지만 계획한다 |
| `collector/test/aside/naver-review-window-read.test.ts` | `today..today`가 계획되고 실행된다 · 미래는 거절 · 화면이 좁혀지지 않으면 읽지 않는다 |
| `frontend/.../ReviewAutoCheckToggle.test.tsx` (6) | 스위치 하나 · 멈춤은 꺼짐이 아니다 · 읽을 화면이 없으면 안 그린다 |
| `frontend/.../CollectNowAction.autoResume.test.tsx` | resume 한 번 · 새 수집을 시작하지 않는다 · 멈춘 것이 없으면 아무 일도 없다 |

마이그레이션 V1…V127은 실제 Postgres에 순서대로 적용하여 검증했고(2026-10-09), V125~V127의 CHECK /
partial unique는 insert probe로 동작을 확인했다 — 테스트 환경은 Flyway를 끄고 엔티티로 스키마를 만들기
때문에 CHECK가 테스트에 보이지 않는다.

## 10. 라이브 증명 (대기)

`docs/evidence/INDEX.md`에 행이 생기는 시점은 라이브 실행 뒤다. 필요한 것:

1. 백엔드/프론트엔드를 이 브랜치에서 **재시작** (`bootRun`은 시작할 때의 클래스를 계속 서빙한다).
2. `SELLEROPS_REVIEW_AUTO_CHECK_ENABLED=true`.
3. `demo@sellerops.ai`로 로그인 — NAVER 수집/catch-up을 가진 그 org.
4. 도우미(helper)를 foreground로 띄워 pairing이 가능한 상태로 둔다.
5. 판매자 행동: 연결 화면에서 「자동 확인 켜짐」을 확인하고, **아무것도 누르지 않고** 기다린다.
   - 과거에 gap이 있으면 walk가 시작되고, 없으면 `today..today` 읽기가 한 번 돈다.
   - 로그인 벽을 만나면 「판매자센터 로그인」을 눌러 **직접** 로그인한다 (id/pw/MFA/CAPTCHA 전부 사람).
6. 라이브 마켓플레이스 실행은 `docs/sellerops_live_approval_contract.md`의 단일 사용 승인이 필요하다.
