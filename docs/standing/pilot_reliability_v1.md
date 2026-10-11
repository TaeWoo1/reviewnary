# Pilot Reliability v1 — 72시간 무인 read-only 운전을 위한 감사

2026-10-11 · `71205663` 기준 · 마켓플레이스 호출 **0** · 모델 호출 **0** · 마이그레이션 **0** ·
FE 변경 **0** · 승인 **0** ⇒ evidence 행 없음. **라이브 soak은 아직 시작하지 않았다.**

이 문서는 새 기능이 아니다. 「지금 Daily Loop를 72시간 아무도 보지 않는 채로 돌릴 수 있는가」를 묻고,
**못 돌리게 만드는 결함 세 개만** 고치고, 다섯 가지 주장을 사후에 증명할 수 있는 최소 계측을 붙인다.
새 IA·화면·대시보드는 없다. Daily Loop 자체의 계약은 `docs/standing/operations_daily_loop_v1.md`가 그대로 소유한다.

---

## §1 감사 — 72시간을 견디는 자리와 견디지 못하는 자리

무인 운전에서 「고장」은 예외가 아니라 **조용함**이다. 그래서 각 단계를 「오류를 내는가」가 아니라
**「아무 일도 일어나지 않는 상태에 빠질 수 있는가, 그리고 거기서 혼자 나올 수 있는가」**로 읽었다.

| 단계 | 72시간 동안 몇 번 | 혼자 회복하는가 | 판정 |
|---|---|---|---|
| responsibility tick | 30초마다 8,640회 | 실패한 tick은 다음 tick이 재도출 | ✅ |
| window 생성 (2시간) | 36개 | `uq` per (책임, window), 지난 창은 `CANCELLED·MISSED`로 보임 | ✅ |
| run claim / lease | run당 1~3 attempt | lease가 끊기면 **같은 run 행**을 attempt+1로 되찾음 | ✅ |
| run 내 retry | 10분 → 30분, 창 안에서만 | 창을 넘기면 다음 창의 run이 수집 | ✅ |
| 공식 API 관찰 | run × source | 끊긴 관찰은 sync job을 보고 adopt 또는 orphan 종료 | ✅ |
| standing 읽기 (60분) | 72회 | `for update skip locked` + `next_check_at` 선전진 | ✅ |
| standing: 도우미 없음 | — | `PAUSED_DEVICE`, 주기 그대로, 다음 tick이 스스로 해제 | ✅ |
| standing: 로그인 벽 (오늘 읽기) | — | 6시간마다 한 번 다시 봄 (`AUTH_RETRY`) | ✅ |
| **standing: 로그인 벽 (walk)** | KST 자정마다 창 1개 | **없음 — 사람의 로그인을 목격해야만 풀린다** | ❌ **D1** |
| **standing: 자식이 보고 없이 죽음** | 도우미가 10분 비면 1회 | **없음 — walk가 RUNNING으로 영구 정지** | ❌ **D2** |
| Case 생성·갱신·중복 | run마다 후보 ≤200/source | signature 일치 = 아무것도 쓰지 않음, 열린 Case는 subject당 1개 | ✅ |
| 리뷰 재수집 중복 | 72회 × 같은 날 | `external_id` dedup, `reviewState`에 `reply_state` 없음 ⇒ signature 불변 | ✅ |
| OBSERVATION_GAP 반복 | run마다 event 1개 | 같은 episode는 Case 하나, 회복하면 닫힘 | ✅ |
| 예외 요약 메일 | run당 최대 1통, Case당 최대 1번 | `notified_at` | ✅ |
| **「이 책상이 바쁜가」** | dispatch·화면 poll마다 | 매번 `scheduled_aside_job` **전체**를 엔티티로 읽음 | ❌ **D3** |
| 마켓플레이스 WRITE | — | 구조적 부재 — 다만 fence가 **무인 lane을 덮지 않았다** | ⚠️ §3 |

세 ❌ 모두 **72시간이 찾아내고 짧은 테스트는 찾지 못하는** 모양이다. D1·D2는 「조용히 끝남」이고,
D3는 「설치가 늙을수록 비싸짐」이다.

## §2 고친 것 — 세 자리

### D1 — 벽에 주차된 walk는 다시 보지 않았다

2026-10-09 라이브에서 측정된 교착(`ReviewAutoCheckClaimer#AUTH_RETRY`의 주석이 그 측정을 들고 있다)은
**절반만** 닫혀 있었다. 「벽은 시간이 지나면 열리지 않지만 **세션**은 판매자가 어디서 로그인하든
돌아오고, 제품은 그것을 들을 방법이 없다 ⇒ 드물게 다시 본다」는 결론은 **walk가 없는 오늘 읽기**에만
적용됐다. 벽에 주차된 walk는 `turn()`의 첫 줄에서 `PAUSED_AUTH`로 돌아나가 probe에 닿지 못했다.

그런데 walk 쪽이 잃을 것이 더 많다. **KST 자정마다 하루치 창이 새로 열리므로**, 로그아웃된 판매자는
하루 안에 walk를 벽에 주차시키고 — 지금 이 조직의 상태가 바로 그 직전이다 — 그때부터 standing lane은
72시간 동안 아무것도 만들지 않는다. soak이 증명할 것이 없어진다.

고친 모양: 같은 6시간, 같은 전이, 그리고 **누가 물었는지만 다른 두 이름**.
`ReviewCatchUpOrchestrator.resumeAfterWait`는 `resumeAfterSignIn`과 한 개의 private 본문
(`resumePaused`)을 공유한다 — 벽에 걸린 walk를 잇는 길은 하나이고, 이름이 둘인 이유는 감사 기록에서
사람의 로그인과 lane의 probe가 구별되어야 하기 때문이다. 간격은 호출자가 들고 있다
(`ReviewAutoCheckClaimer.AUTH_RETRY`, 6시간): walk 없는 경로와 **같은 숫자가 한 곳에만** 있다.

- 창은 넘기지 않는다. `resume()`이 cursor를 주차된 창으로 되돌리고 `attempt`를 올린다 — 올리지 않으면
  `(device, clientJobId)` 멱등성이 끝난 행으로 수렴해 아무것도 일어나지 않는다.
- 기다린 시간은 상한에 넣지 않는다 (`endWaiting`). 사람이 로그인하는 시간은 기계가 일한 시간이 아니다.
- dispatch가 실패하면 `resumeAfterWait`의 트랜잭션이 되돌아가므로 walk는 벽에 그대로 주차돼 있다.
  도우미가 없으면 `PAUSED_DEVICE`(마켓플레이스를 열지 않으므로 공짜), 바쁘면 `SKIPPED`.

**6시간은 이 lane의 정상 cadence가 아니다.** 주기는 60분이고, 6시간짜리 probe는 제품이 들을 방법이
없는 사건 하나 — 판매자가 어딘가에서 다시 로그인했다 — 를 위해서만 존재하는 **복구 안전망**이다. 안전망이
자주 쓰이는 것은 안전망이 잘 돈다는 뜻이 아니라 **세션이 유지되지 않고 있다**는 뜻이다. 그래서 probe가 몇
번 돌았는지는 건강의 지표가 아니고, soak의 판정에 그렇게 쓰이지 않는다(§3 판정 기준).

### D2 — 보고하지 않고 죽은 자식은 walk를 영구히 묶었다

`advance()`에 닿는 길은 `ScheduledAsideJobService.settle()` **하나뿐**이다. 아무도 집지 않아 TTL(10분)이
지난 자식, 또는 lease(5분) 안에 보고하지 못한 자식은 bulk UPDATE로 `EXPIRED`가 될 뿐 **settle하지
않는다**. 그러면 walk는 `RUNNING`인데 어느 책상에도 일이 없고, 세 가지가 그 상태를 영구화한다:

1. `uq_review_catch_up_live`가 그 줄의 두 번째 live walk를 거부하므로 **새 walk를 영원히 시작할 수 없다**;
2. `ReviewAutoCheckReconciler`는 「이미 걷고 있다」를 읽고 매시간 공손히 물러난다 — 올바른 동작이다;
3. 판매자의 큐는 그냥 조용해진다 — 멈춘 관찰이 절대 닮아서는 안 되는 그 모양.

도우미가 10분 자리를 비운 대가가 그 설치의 남은 수명 전체다. 72시간이면 — sleep, 재시작, 끊긴
네트워크 — 반드시 한 번은 온다. 실제로 이 조직의 `review_catch_up_run` 이력에는 이미
`EXECUTOR_UNAVAILABLE`로 끝난 walk가 세 개 있다(그것들은 자식이 **보고를 하고** 죽은 경우다).

고친 모양: `stopIfStranded(catchUpRunId)` — **잇지 않고 내려놓는다.**

- 판정은 **walk의 id로만** 내린다 (`ScheduledAsideJobRepository.countLiveChildren`). 「이 계정에 살아 있는
  작업이 있나」로 물었다면, 도우미가 둘인 조직에서 다른 lane의 작업 하나가 멀쩡한 walk를 내려놓게 만들 수
  있다. one-live-job 규칙은 **기기**당이고 계정당이 아니다.
- `FAILED · CHILD_LOST`. 걸어 지난 창은 그대로 서 있다 — coverage는 그 창들이 남긴 증거에서 읽히므로.
- 재시도 횟수를 정하지 않는다. 그 숫자는 아무도 측정하지 않았고, 새로 계획하는 쪽은 그런 숫자가 필요 없다:
  같은 차례가 실제 경계에서 fresh walk를 세우고, 이 런타임의 나머지와 같은 방식으로 DB에서 전부
  재도출한다.
- **진행 중인 일은 건드리지 않는다.** 자식이 QUEUED이기만 해도 walk는 일하는 중이고, CLAIMED면 지금
  판매자의 화면을 읽고 있다. 어느 쪽도 취소하지 않는다.

### D3 — 「이 책상이 바쁜가」의 값이 설치의 나이와 함께 자랐다

`ScheduledAsideJobService.hasLiveWork`가 `jobs.findAll()`로 **이 배포가 여태 넘겨준 모든 작업을
엔티티로** 읽고 있었다. 이 질문은 모든 dispatch와 모든 「지금 수집」 화면 poll에서 물어지고,
`scheduled_aside_job`은 **줄어들지 않는다** — standing lane만으로도 계정당 시간당 한 행이다.

고친 모양: `countClaimedLive(deviceId, now)` 하나. `uq_scheduled_aside_job_active`의 술어가
`status in (QUEUED, CLAIMED)`이므로 그 부분 인덱스에는 **살아 있는 작업만** 들어 있고, 끝난 행이 몇 개든
읽는 양이 같다. lease 경계를 쿼리 안에 둔 이유: 만료된 lease는 살아 있는 일이 아니고, 답이
`expireStale`을 먼저 돌렸는지에 의존해서는 안 된다.

## §3 증명 계약 — 다섯 가지 주장, 각각 어디서 읽히는가

| 주장 | 무엇이 그것을 말하는가 |
|---|---|
| 자동 관찰 → Case | `responsibility cases run=… 새Case=… 갱신=… 변화없음=…` 로그 + 스냅샷의 `case`·`case_event` 증가량. Case가 standing 읽기에서 나왔다는 것은 `run_source_method` 와 `job/NAVER_REVIEW_OBSERVE_V1/SETTLED/OBSERVED` 가 같이 자라는 것으로 읽힌다 |
| AUTH / device 복구 | 스냅샷의 `standing` 행이 스냅샷 사이에 `PAUSED_AUTH → OK`로 바뀌는 것, 그리고 `standing_overdue.enabled_rows_past_due = 0` (probe를 멈춘 줄이 없다) |
| 중복 / 홍수 없음 | 스냅샷 `invariant` 6개가 모든 스냅샷에서 0 — subject당 열린 Case, 기기당 live 작업, 줄당 live walk, **묶인 walk**, 책임당 동시 run, 중복 window |
| scheduler / job / case 증가량 | `soak-snapshot.sh diff` 가 섹션별 `전 → 후 +델타 (N/h)` 를 낸다. 한 번의 수치는 비율이 아니므로 증명은 **두 스냅샷의 차이** 다 |
| 마켓플레이스 WRITE 0 | (a) 구조: `ResponsibilityMarketplaceWriteFenceTest`, (b) 데이터: write 9개 표의 행 수가 **한 줄도** 움직이지 않음 |

**(a)의 구멍을 이번에 메웠다.** 그 fence는 `responsibility/` 와 `operationscase/investigation/` 만 덮고
있었는데, **평범한 설치에서 무인으로 도는 코드는 그 둘이 아니다**: responsibility의 마켓플레이스 lane은
배포가 조직과 계정을 둘 다 이름으로 적어야 열리고, standing lane은 **둘 다 필요 없다** — 승인이 판매자의
설정 자체이기 때문이다. 그래서 매시간 아무도 보지 않는 채로 판매자의 화면을 읽던 코드
(`autocheck/`, `coverage/catchup/`)가 이 파일의 모든 fence **밖에** 있었다. 이제 같은 `SEND_PATH` 목록으로
덮인다 — 두 번째 목록은 두 fence가 어긋나게 되는 방식이므로 만들지 않았다.

함께 추가한 두 가지:

- `theUnattendedPathHoldsNoWholeTableRead` — 며칠씩 도는 loop는 **아무것도 삭제하지 않는 표의 크기**가
  비용인 질문을 해서는 안 된다. 허용되는 예외는 `channels.findAll()` 하나이고, 그 예외가 규칙을 설명한다:
  `channels`는 제품이 함께 배포하는 고정 참조 목록(3행, 보이는 채널 집합은 frozen)이고 ledger가 아니다.
  D3를 재도입하면 이 테스트가 그 파일을 지목하며 깨진다(반례로 확인).
- `SELLEROPS_REVIEW_AUTO_CHECK_ENABLED:false` 를 fail-closed 기본값 목록에 추가. 이 lane의 유일한 배포
  결정이고, 기본값이 true인 빌드는 첫 부팅에 판매자의 가게를 스스로 읽는다.

### 판정 기준 — 반복된 `AUTH_REQUIRED`는 PASS가 아니다

**벽에 주차된 채로 72시간을 버틴 것을 PASS로 적지 않는다.** `PAUSED_AUTH`인 줄을 6시간마다 다시 보기만 한
72시간은 — invariant 6개가 전부 0이고, write 9개 표가 한 줄도 움직이지 않고, 예외가 하나도 없더라도 — 위
다섯 주장 중 **하나도 끝까지 증명하지 못한다**. 그 창에서 관찰은 성립하지 않았고, Case도 증가량도 중복
없음도 **측정되지 않았다**: 0은 「중복이 없었다」가 아니라 「읽은 것이 없었다」다. 그 결과의 정직한 이름은
PASS가 아니라 **`BLOCKED·AUTH`** 이고, 그것은 제품의 결함이 아니라 **재실행 사유**다 — 그 창이 증명하는
것은 D1의 수정 하나(「lane이 스스로 멈추지 않는다」)뿐이다.

| 판정 | 조건 |
|---|---|
| **PASS** | 아래 최소 증거가 **전부** 있다 |
| **`BLOCKED·AUTH`** | lane은 살아 있고(probe가 간격대로 돌고 `standing_overdue.enabled_rows_past_due = 0`) WRITE·invariant도 깨끗하지만, **관찰이 성립한 창이 없다** ⇒ NAVER 로그인이 가능한 네트워크에서 다시 돈다 |
| **FAIL** | write 표가 움직였거나, invariant가 깨졌거나, 아무 probe도 돌지 않은 채 멈춘 줄이 남았다 |

PASS의 최소 증거:

- **관찰 → Case**: `OBSERVED`로 settle한 standing job이 ≥ 1, 그리고 그 run의 Case 결과가 새Case / 갱신 /
  변화없음 중 하나로 **설명된다**. 「변화없음」도 증거이지만 **관찰이 성립한 창에서만** 그렇다.
- **AUTH 복구**: 스냅샷 사이에 `PAUSED_AUTH → OK` 전이가 **최소 한 번**. probe가 돌았다는 것은 복구가
  아니다 — 복구는 상태가 바뀌는 것이다.
- **device 복구**: `PAUSED_DEVICE`가 생겼다면 다음 tick에 **스스로** 풀렸다. 만들지는 않는다; 일어나면 읽는다.
- **증가량**: 한 번의 수치가 아니라 두 스냅샷의 차이. `job`이 standing 읽기 횟수 근방으로 자라고 `walk`에
  묶인 줄이 없다.
- **WRITE 0 / invariant 0**: 모든 스냅샷에서. 이 둘만이 벽에 주차된 창에서도 참으로 읽히는 주장이고,
  그래서 이 둘만으로는 PASS가 되지 않는다.

### 계측 — `tools/soak/`

제품 코드에 계측을 넣지 않았다. 다섯 주장 전부가 **제품이 이미 쓰고 있는 표의 count**이므로, 도구는
count다: 새 컬럼도, 새 엔드포인트도, 화면도 없다.

```
tools/soak/soak-snapshot.sh take|check|diff|list
```

- 엄격히 **읽기 전용**: 모든 문장이 SELECT이고, 세션 전체가 `begin read only` 안에서 돈다.
  연결 문자열은 출력되지 않는다.
- SQL은 `snapshot.sql`에 따로 있다 — 스크립트는 SQL을 들지 않고, SQL은 셸 quoting을 들지 않는다.
  측정을 추가하는 일은 `section|key|value`를 내는 SELECT 하나를 더 쓰는 일이다.
- `check` 가 soak 중 운영자의 한 동작이다: 스냅샷을 찍고 **직전 것과의 차이**를 바로 낸다.
- 스냅샷은 `tools/soak/.run/`(gitignore). 한 기계의 한 순간 행 수는 한 실행의 **증거**이고 source가
  아니다 — 커밋되면 다른 설치의 숫자가 된다.
- WRITE가 움직이거나 invariant가 깨지면 exit code가 1이다. 무시하는 preflight는 무시하기를 **선택**한
  것이다(`schedule-guard.sh`와 같은 태도).

## §4 soak 절차 — 라이브 시작 전에 멈춰 있는 지점

**아직 시작하지 않았다.** 이 soak은 공식 API 수집과 판매자센터 화면 읽기를 하므로
`docs/standing/sellerops_live_approval_contract.md`의 **신선한, 단회, 같은 턴의 승인**이 필요하다. mode는 READ,
WRITE는 없다.

준비 (코드 변경 없이, 순서대로):

1. **고객 운영 관리를 한 번 쉼 → 다시 시작.** 이 조직의 `next_run_at`은 2026-09-27이고, 지금 scheduler를
   켜면 첫 두 tick이 **14일치 168개 창을 `CANCELLED·MISSED`로** 적는다. 전부 올바른 기록이고 마켓플레이스를
   열지 않지만, soak의 증가량 기준선을 오염시킨다. 화면에서 쉼→다시 시작하면 `next_run_at`이 지금 창으로
   맞춰진다(`ResponsibilityService.startWorking`) — SQL도, 도구도 필요 없다. 제품이 가진 「지금부터」다.
2. **판매자가 NAVER 판매자센터에 로그인.** 지금 `review_auto_check`의 REVIEW 행은 `PAUSED_AUTH`다(참인
   값이다). 벽에 주차된 채로 시작하면 soak은 D1의 수정만 증명하고 나머지는 증명하지 못한다 ⇒ 그 결과는 PASS가
   아니라 `BLOCKED·AUTH`다(§3 판정 기준).
3. **도우미가 떠 있고 짝지어져 있는지.** foreground여야 한다(`helper pairing needs a TTY`).
4. `tools/soak/soak-snapshot.sh take baseline` — **기준선**. invariant 6개가 0이고 `stranded_walks`가 0인
   것을 여기서 확인한다.
5. 켤 것은 둘뿐: `SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=true`, `RESPONSIBILITY_RUNTIME_ORG_IDS=<org>`,
   `SELLEROPS_REVIEW_AUTO_CHECK_ENABLED=true`. **`…ASIDE_MARKETPLACE_ENABLED`는 켜지 않는다** — standing
   lane은 그것을 쓰지 않고, 켜면 responsibility run도 같은 화면을 읽기 시작해 두 lane이 한 가게를 두 번
   읽는다(§1의 중복 판정은 그 상태에서 다시 측정해야 한다).

진행 중: 몇 시간마다 `soak-snapshot.sh check`. 72시간이면 window 36개, standing 읽기 72회,
Case 재확인 36회가 **예상되는** 모양이고, 실제 수치는 이 배포가 어떤 connector 플래그를 켰는지에 달려
있으므로 기준선이 그것을 적는다.

끝나면: `diff baseline <마지막>` 한 장 + `docs/evidence/INDEX.md` 한 행. 그 행의 outcome은 §3의 세 값 중
하나이고, `BLOCKED·AUTH`도 적는다 — 적지 않으면 다음 사람이 같은 창을 PASS로 읽는다.

## §5 바꾸지 않은 것

- **마켓플레이스 WRITE 경로 전부.** 게이트, 멱등성 키, 이중 게시 fence, 승인 계약, 가이드 mint 수명 —
  한 줄도. 이 문서가 WRITE에 대해 한 일은 **증명 범위를 넓힌 것**뿐이다.
- **cadence·상한·TTL·lease 숫자 전부.** 2시간 창, 60분 주기, 6시간 probe, 10분 TTL, 5분 lease, 3 attempt,
  200 후보, `Limits.PRESS` — 어느 것도 soak을 막지 않으므로 어느 것도 조정하지 않았다. soak은 지금 숫자를
  측정하는 것이고, 측정하려는 숫자를 먼저 바꾸면 측정이 아니다.
- **FE·IA·화면 0.** A7 freeze 그대로. 이 arc는 판매자가 보는 것을 바꾸지 않는다.
- **마이그레이션 0.** 추가한 쿼리 둘은 모두 기존 인덱스로 답해진다.
- **제품 코드 안의 계측 0.** §3 참조.
- **`expireStale` 자체.** 표 전체를 대상으로 하는 UPDATE처럼 보이지만 술어가 부분 인덱스의 술어와
  같아서 **살아 있는 작업만** 훑는다. 도우미가 30초마다 묻는 경로이므로 확인했고, 바꿀 것이 없었다.

## §6 보고하되 고치지 않은 것

1. **API source가 하나도 없는 조직은 Case를 하나도 만들지 못한다.** `execute()`가
   `required.isEmpty()` 에서 `NO_REQUIRED_SOURCE`로 **먼저 돌아나가므로**, `followUp.afterObservation`
   (즉 Case 처리 전부)이 호출되지 않는다. NAVER만 연결했고 문의 lane이 꺼진 판매자 — 평범한 모양이다 —
   는 standing lane이 읽어 온 리뷰가 영원히 Case가 되지 않는다. **이 soak 조직은 해당하지 않는다**
   (CAFE24·COUPANG·NAVER가 모두 CONNECTED). 고치지 않은 이유: 「공식 source가 없는 run도 후속 처리를
   해야 하는가」는 run의 의미에 대한 제품 결정이고, soak을 막지 않는다.
2. **`PAUSED_DEVICE`는 `OBSERVATION_GAP`이 되지 않는다** — 다음 tick이 스스로 푸는 멈춤이고, 아무것도
   요구하지 않으면서 스스로 사라지는 카드는 판매자에게 「요구하는 카드도 지나쳐 읽는 법」을 가르친다
   (`docs/standing/operations_daily_loop_v1.md` §3). 72시간 내내 도우미가 없으면 Home은 조용하고 설정 화면만 안다.
   그 균형은 그대로 두었다.
3. **standing lane의 멈춤은 run이 그 계정의 source 행을 가질 때만 Home에 보인다.**
   `observeGaps`가 run 자신의 source 행에서 계정 목록을 만든다. 이미
   `docs/standing/operations_daily_loop_v1.md` §7에 적힌 한계이고, 넓히는 일은 IA 결정이다.
4. **`AsideSourceObserver`의 120초 대기는 여전히 run 스레드를 잡는다** — 그 파일이 스스로
   `PILOT_ACCEPTED_LIMITATION`으로 적어 둔 비용이고, 조직 하나·기기 하나의 soak에서는 느껴지지 않는다.
   측정이 요구하기 전에는 비동기 hand-off로 다시 만들지 말라는 지시가 그 자리에 있다.
5. **retention 없음.** 어떤 표도 줄지 않는다. 72시간에 수천 행이므로 soak을 막지 않지만, 증가량 수치가
   보존 정책을 언제 논의해야 하는지에 대한 첫 측정이 된다.

## §7 검증

| 항목 | 결과 |
|---|---|
| backend 전체 | **5,338 tests · 0 failures** (669 suites, 60 skipped) |
| frontend 전체 | **3,921 tests · 0 failures** (287 files), `tsc --noEmit` clean |
| D1 반례 | 수정 되돌림 → 「벽에 주차된 walk도 드물게 다시 본다」 실패 |
| D2 반례 | 수정 되돌림 → 「보고하지 않고 죽은 자식은 walk를 영원히 묶는다」 실패 |
| D3 반례 | `jobs.findAll()` 재도입 → `theUnattendedPathHoldsNoWholeTableRead`가 그 파일을 지목하며 실패 |
| 새 테스트 | `ReviewAutoCheckTest` +4 (probe · 묶인 walk · 멀쩡한 walk 보호 · 끝난 일은 책상을 막지 않는다), fence +2 |
| `tools/soak/` | 로컬 DB에 실행 — 스냅샷·diff·WRITE 감지·invariant 감지 전부 확인(위조한 스냅샷으로 반례) |
| 라이브 | **없음.** 마켓플레이스 호출 0, 승인 0 ⇒ `docs/evidence/INDEX.md` 행 없음 |
