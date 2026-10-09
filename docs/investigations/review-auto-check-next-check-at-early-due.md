# 조사 메모 — `review_auto_check.next_check_at`이 park한 것보다 일찍 due가 됐다 (2026-10-09)

> **상태: 미해결.** 이번 수정 범위와 섞지 않는다. 증명 결과에는 영향이 없었고, 설명이 안 되는 채로 남겨 둔다.

## 관측

```
22:37:26  읽음   next_check_at = 2026-10-09 22:44:29.901417+09
22:38:44  내가 park: update ... set next_check_at = now() + interval '1 day'
                   RETURNING → 2026-10-10 22:38:44.577956+09   (UPDATE 1)
22:38:5x  이전 backend(pid 72758 그룹, 21:44 기동, 플래그 ON) 종료
22:39:14  새 backend 기동 (플래그 ON), tick은 :34.9초에 5분 간격
22:49:34  tick이 이 행을 claim → client_job_id `ac-e37076d7-1791553494`
                   slot(= claim 시점의 next_check_at) = 2026-10-09 22:44:54
```

park한 값은 **다음 날**인데 22:49:34 tick이 집어갔고, 그 tick이 들고 있던 slot은 `22:44:54`였다.

## 코드상 그 값을 쓸 수 있는 경로 (전수)

`next_check_at`에 쓰는 곳은 셋뿐이다(backend 전역 grep 기준, native update 없음):

| 경로 | 쓰는 값 | 22:44:54가 되려면 |
|---|---|---|
| `ReviewAutoCheckClaimer.claimDue` | `now + interval_minutes`(60분) | `now = 21:44:54` — park(22:38:44)보다 과거라 불가능 |
| `ReviewAutoCheckClaimer.settle`(PAUSED_AUTH) | `now + 6h` | `now = 16:44:54` — 같은 이유로 불가능 |
| `ReviewAutoCheckService.set` / `ensure` | `now` | `now = 22:44:54`는 구간 안이지만, `set`은 `consented_at`도 함께 쓴다 — 그 값은 17:14:13 그대로이고 `ensure`는 기존 행에 아무것도 쓰지 않는다 |

즉 **살아 있는 코드가 그 시각에 그 값을 썼다고 설명할 수 없다.**

## 현재 가장 그럴듯한 가설 (확인되지 않음)

park 6초 뒤에 종료된 이전 backend가 shutdown flush로 자기 세션의 엔티티 스냅샷을 되돌려 썼다.
`ReviewAutoCheck`에는 `@Version`이 없어 낙관적 잠금이 없고 last-write-wins다. `22:44:54`는
`21:44:54 + 60분`이고 그 시각은 이전 backend의 tick 구간(21:44 기동 + 20초 initial delay)과 겹친다.
다만 같은 구간에 `22:44:29.901`(= `21:44:29.901 + 60분`)도 관측됐으므로, 한 인스턴스가 24초 간격으로
두 번 claim한 셈이 되어 그 자체로 모순이다.

## 다음에 확인할 것

1. 이 행에 `@Version`(낙관적 잠금)을 넣고 같은 상황(플래그 ON인 backend를 종료 직전에 외부에서 update)을
   재현해 `ObjectOptimisticLockingFailureException`이 뜨는지 본다 — 가설이 맞으면 거기서 드러난다.
2. 21:44 구간에 backend 인스턴스가 정말 하나였는지. 이번 세션에서는 사후에 하나만 확인했다(`lsof` 8080,
   `pgrep`), 그 시각의 상태는 기록이 없다.
3. `review_auto_check`에 쓰기 감사(누가·언제·무엇을)가 없다 — 이 조사를 두 번 하지 않으려면 그게 먼저다.

## 영향

없음. 이 일 때문에 바뀐 판정은 하나도 없다 — 벽·주차·재시도 금지·복귀 resume·WRITE 0은 전부 그 뒤의
관측이고, 바뀐 것은 「내가 타이밍을 쥐고 있었다」는 내 주장뿐이다.
