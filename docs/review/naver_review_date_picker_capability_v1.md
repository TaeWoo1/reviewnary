# NAVER 리뷰 기간 선택 capability v1 — 측정, 규칙, 그리고 탐색 방식의 결정

**날짜:** 2026-10-08 · **채널:** NAVER 스마트스토어센터 `#/review/search` · **성격:** READ-ONLY 측정 +
그 측정에서 뽑은 deterministic 규칙의 구현 · **마켓플레이스 변경 0**

---

## 0. 이 문서가 고정하는 것

1. 리뷰 화면의 기간 선택 component가 실제로 어떤 구조인지 — 측정값.
2. 그 구조에서 **무엇을 deterministic하게 고정했고 무엇을 agent에 맡기지 않기로 했는지**.
3. 「기간을 전부 읽었다」는 판정이 무엇에 근거하는지 — 그리고 이전 규칙이 왜 틀렸는지.
4. 미지 surface를 탐색하는 **개발 방식**의 결정.

소유: 이 lane의 코드는 `collector/src/aside/naver-review-window-runtime.ts`,
`collector/src/naver/review-list-observe-inpage.ts`, 그리고 backend
`NaverReviewObservationService.incompleteReason` / `coverage/ReviewCoverageCursor`.

---

## 1. 탐색 방식의 결정 — bounded agent mission → 성공 trace → 최소 규칙

이 arc 직전까지 우리는 DOM을 한 동작씩 역공학하고 있었고, 그 과정에서 **세 가지를 모두 틀렸다**:

- 「날짜 field가 readonly니까 기간을 설정할 수 없다」 → 달력으로 가능하다.
- 「날짜 cell에 날짜를 특정할 속성이 없다」 → `td`의 class가 전부 말해 준다.
- 「월 이동 control이 없다」 → header에 네 개 있다.

셋 다 **probe가 picker root를 잘못 잡아서** 나온 결론이었다. readonly input에서 위로 올라가 input 자신의
190x34 wrapper를 달력이라 불렀고, 그 안에서 센 42개 cell은 다른 view의 template이었다. root 하나를 잘못
잡아 결론 셋이 틀렸고, 그 셋 위에 「구현하지 말 것」이라는 판정이 서 있었다.

그래서 방식을 바꿨다. **Aside MCP의 고수준 browser agent에게 selector를 하나도 주지 않고** 목표만 줬다:
「2026-09-03 ~ 2026-09-09을 설정하고 조회한 뒤, 화면이 그 기간을 보여주는지 확인해라」. agent는 사람 개입 0으로
해냈고(총 46개 → 42개, 7일), 그 성공 trace가 아래 §2의 측정을 어디서 봐야 하는지 가리켰다.

### 결정

- **production catch-up은 deterministic runtime으로 간다.** Aside exec agent는 production executor에
  넣지 않는다. 이유는 §5.
- **미지 UI 영역 탐색에는** `bounded Aside mission → 성공 trace 관찰 → 최소 deterministic rule 추출`을
  쓴다. 발견은 agent, 실행 허가는 deterministic guard.
- agent가 만드는 screenshot/temp artifact는 production collector에서 생성하지 않는다.

---

## 2. 측정 — 2026-09-03~09-09가 조회된 상태의 live DOM (READ-ONLY)

### 2.1 기간 component

```
form
  ncp-datetime-range-picker2
    div.seller-calendar
      div.input-daterange.date
        div.form-group._startDate_dropdown          ← side root (accepted date control 정확히 1개)
          div.input-group.dropdown-toggle           ← binding unit (field 1개 + pressable 1개)
            input[type=text] readOnly=true  value "2026.09.03."
            a 「달력보기」  (18x15)  + i.fn-calendar1
          div.datetimepicker                        ← 열 때 생성됨. 닫혀 있으면 DOM에 없다
        div.form-group._endDate_dropdown
          … 같은 모양, value "2026.09.09."
  button 「조회」 (120x40)
```

- `_startDate_dropdown` / `_endDate_dropdown` — **start/end가 class token으로 구조적으로 구별된다.**
  위치나 순서가 아니다.
- 닫힌 상태에서 `td.day` 개수 = **0**. 달력은 열 때 만들어진다.

### 2.2 열린 달력

```
div.datetimepicker  (300x337)
  div.datetimepicker-header
    button.left  > i.fn-booking.fn-booking-last-backward1   data-ng-click changeViewPrevYear(…)
    button.left  > i.fn-booking.fn-booking-backward1        data-ng-click changeView(data.currentView, data.leftDate, …)
    button.title 「2026.09」                                  data-ng-click changeView(data.previousView, …)
    button.right > i.fn-booking.fn-booking-forward1          data-ng-click changeView(data.currentView, data.rightDate, …)
    button.right > i.fn-booking.fn-booking-first-forward1    data-ng-click changeViewNextYear(…)
  div.datetimepicker-body.day-view
    table > thead th.dow ×7 (일 월 화 수 목 금 토)
            tbody  td.day… > div > span 「3」   ×42
```

**네 화살표에는 text도 `title`도 `aria-label`도 없다.** 유일한 자식 `<i>`는 `aria-hidden="true"`다.
즉 「`이전 달`이라는 label을 가진 control」이라는 규칙은 **이 화면에서 구현 불가능하다.**

### 2.3 cell의 의미 — component가 스스로 선언한다

`td`의 `data-ng-class`:

```
{current: dateObject.current, active: dateObject.active, past: dateObject.past,
 future: dateObject.future, disabled: !dateObject.selectable,
 includeDay: dateObject.includeDay, startDay: dateObject.startDay, endDay: dateObject.endDay}
```

2026-09 페이지의 42개 cell 분해 (실측):

| class | 개수 | 뜻 |
|---|---|---|
| `day past` | 2 | 8/30, 8/31 |
| `day` | 23 | 9월, 범위 밖 |
| `day active includeDay startDay` | 1 | 9/3 (선택된 시작일) |
| `day includeDay` | 5 | 9/4–9/8 |
| `day includeDay endDay` | 1 | 9/9 (선택된 종료일) |
| `day future` | 7 | 10/1–10/7 |
| `day current future` | 1 | 10/8 (오늘) |
| `day future disabled` | 2 | 10/9, 10/10 |

2 + 30 + 10 = 42. 9월분 23+1+5+1 = **30 = 9월의 일수**.

`past`/`future`가 **이웃 달 filler의 표시**이고, 이것이 「숫자만 보고 cell을 고르지 않는다」가 성립하는
근거다. 9월 페이지에는 「3」이 **두 번** 나오고 두 번째는 10월 3일이다.

### 2.4 목록 grid

```
ag-grid (ag-theme-fresh), pagination panel 없음
columns: productNo / productName / reviewType / reviewScore / reviewAttach /
         reviewContent / helpCount / writerId / createDate(리뷰등록일) / modifyDate
.ag-body-viewport      scrollHeight 3990  clientHeight 394   ← 유일한 scroller
.ag-center-cols-container  height 3990px   rendered .ag-row 15
row height 95px → 3990 / 95 = 42 rows
row-id = "0","1","2"  ← grid의 index. 리뷰 identity가 아니다
h3 「리뷰목록 (총 42개)」
page size widget: div.item 「500개씩」 / div.option.selected 「500개씩」
```

**DOM에는 15행만 있고 돌려쓴다.** 42건 중 15행.

---

## 3. 고정한 규칙 (deterministic) — 측정에서 확인된 것만

판정은 전부 **page 안의 술어 한 곳**에서 한다. selector는 pure CSS이고 아무것도 판단하지 않는다
(2026-10-08 `SyntaxError`의 교훈: 한 사실에 판단자가 둘이면 두 가지로 판단될 수 있다).

| 단계 | 규칙 | 0개/≥2개 |
|---|---|---|
| 달력 열기 | field를 담은 **가장 작은 조상** 중 pressable을 가진 첫 조상에 pressable이 **정확히 1개** | `CALENDAR_OPENER_NOT_FOUND` / `_AMBIGUOUS` |
| 달력 열림 판정 | `YYYY.MM` title 1개 **그리고** 요일 heading 7개 **그리고** day cell ≥28 — 셋이 모두 | `PICKER_VIEW_UNREADABLE` |
| 월 이동 식별 | icon class가 한 걸음(`backward`/`forward`, `last-`/`first-` 제외) **그리고** handler가 현재 view 내 이동(`currentView`) — 둘이 일치 | `MONTH_NAV_NOT_FOUND` / `_AMBIGUOUS` |
| 월 이동 검증 | click 후 title이 **정확히 한 달**, 요청한 방향으로 | `MONTH_NAV_UNVERIFIED` |
| 월 이동 상한 | **field마다** 13걸음 | `MONTH_NAV_EXHAUSTED` |
| 날짜 cell | `day` 있고 `past`·`future`·`disabled` 없고 visible/enabled, text == 목표 일, **정확히 1개** | `DAY_CELL_NOT_FOUND` / `_AMBIGUOUS` |
| field 반영 | cell click 후 input value == 요청 날짜 | `RANGE_NOT_SETTABLE` |
| 조회 | 두 date control과 **같은 form** 안의 visible·enabled·허용된 조회/검색 의미, **정확히 1개** | `QUERY_CONTROL_NOT_FOUND` / `_AMBIGUOUS` |
| 조회 후 | input 2개 == 요청값 **그리고** 화면 census의 기간 == 요청 기간 | `RANGE_MISMATCH` (ingest 0) |

### 세 가지를 특별히 적어 둔다

**(a) label 규칙은 쓸 수 없었다.** 「`이전 달` accessibility label로 식별」은 §2.2대로 불가능하다. 대신
서로 독립인 marker 둘의 일치로 좁히고, **title 재확인이 실제 보증**이다. marker 하나가 사라지면 후보가
0개나 2개가 되어 fail closed; marker가 틀려도 title이 한 걸음을 부정해 그 자리에서 멈춘다.

**(b) `fill`이 사라졌다.** field가 readonly이므로 타이핑은 길이 아니었다. 이 lane의 어휘는 이제
**click 4개**(opener / month step / day cell / 조회)이고 `.fill(`은 0개다 — 판매자 화면에 일어날 수 있는
일의 집합이 이전보다 **더 작아졌다**. `aside-guard.test.ts`가 이것을 센다.

**(c) census는 press마다 다시 읽는다.** index는 그것을 만든 document에서만 참이고, **달력을 열면 document에
control이 5개 늘어난다.** 시작 시점에 읽은 end opener index를 나중에 쓰면 첫 달력의 화살표를 누른다.

---

## 4. 「기간을 전부 읽었다」 — 판정이 바뀌었다

### 이전 규칙과 그것이 틀린 방식

```java
deliveryCompleteness = (rows < rowCapacity) ? COMPLETE : PARTIAL;   // ← 2026-10-08까지
```

`rowCapacity`는 **우리 천장**(500)이다. 이것 하나를 coverage로 읽은 것이 「45행 / 천장 500」을
「그 7일은 메워졌다」로 만든 경로다. 천장에 닿지 않았다는 사실은 **행을 잃는 한 가지 방식**을 배제할 뿐,
화면이 그 기간을 보여주고 있었다는 것을 세우지 않는다. 목록이 「50개씩」으로 맞춰져 있고 300건이면 이 규칙은
통과하고, 판매자는 그 기간의 6분의 1만 본 화면을 「완전함」으로 기록한다.

### 지금 규칙 — 서로 다른 출처의 네 사실 (`NaverReviewObservationService.incompleteReason`)

1. **우리 천장에 닿지 않았다** — `rows < rowCapacity` (그대로 유지, 단 이것만으로는 부족)
2. **행이 grid의 row model에서 왔다** — `gridReadMode == "MODEL"`
3. **화면이 인쇄한 총계가 받은 행과 같다** — `labelledTotal == rows` ← 독립 증인
4. **그 총계가 선택된 page size 안에 있다** — `labelledTotal <= selectedPageSize`

하나라도 **읽히지 않았다는 것 자체**가 경계를 두고 갈 이유다(`TOTAL_UNREADABLE`,
`PAGE_SIZE_UNREADABLE`, `CAPACITY_UNKNOWN`, `READ_MODE_UNPROVEN`). 판정 사유는
`scheduled_aside_job.completeness_reason`에 남는다.

실패한 창은 **행을 보관하고 경계를 밀지 않는다.** `ReviewCoverageCursor.provesItsWindow`와 catch-up walk가
같은 하나의 verdict를 읽으므로, 「행은 있는데 coverage는 안 움직인다」가 두 곳에서 같은 뜻이다.

### virtualized grid — 왜 scroll이 단계가 아닌가

§2.4대로 DOM에는 15행만 있다. 그래서 「더 이상 새 행이 안 보인다」는 끝의 증거가 아니다 —
viewport의 끝일 뿐이다.

이 lane의 reader는 **처음부터 DOM을 훑지 않는다.** grid의 row model(`api.forEachNode`)을 읽고
`rows.length + badShape != rowCount`면 `ROWS_NOT_LOADED`로 거부한다. 즉 **「모든 node가 적재됐다」가
scroll end보다 강한 증거**이고, scroll로 바꾸는 것은 느려지고 약해지는 변경이다. 그 사실을 기록에
남기기 위해 `grid_read_mode`가 있고, `MODEL`이 아니면 완전함을 주장하지 못한다.

> 이 지점은 지시(「virtualized viewport를 deterministic하게 끝까지 읽는다 / scroll로」)와 다르게 구현했다.
> 측정 결과 production reader에는 **DOM snapshot 한 번으로 전체를 읽었다고 판단하는 경로가 없었다**.
> 탐색 중 「42건인데 DOM에 15행」을 본 것은 probe가 DOM을 읽은 것이고, production reader가 아니다.
> 목표(기간 전체를 deterministic하게 읽었다는 증거)는 model + `loaded == rowCount` + 화면 총계 일치로
> 달성되고, 이쪽이 더 강하다.

---

## 5. agent를 production에 넣지 않는 이유

- **비결정성** — 같은 prompt가 같은 click sequence를 보장하지 않는다. 「무엇을 눌렀는지」가 run마다 달라지고
  job 기록에 audit trail이 남지 않는다.
- **지연** — 한 window에 120초 이상. catch-up은 window를 여러 번 돈다.
- **오발** — 탐색 중 휠 첫 시도가 목록 대신 페이지를 스크롤했다. 경계가 좁은 화면에서는 위험하다.
- **보증의 질** — agent가 금지 목록을 지킨 것은 **prompt 준수**이고 구조적 차단이 아니다.
  `aside-guard.test.ts`(click 4개, fill 0개, mutation token 부재)가 주는 보증과 종류가 다르다.
- **흔적** — agent가 세션 temp에 화면 캡처를 남긴다. production 경로에서는 만들지 않는다.

---

## 6. audit — child job에 남는 것

`scheduled_aside_job` (V122): `requested_window_start/end`, `window_start/end`, `observed_count`,
`observed_capacity`, `labelled_total`, `selected_page_size`, `grid_read_mode`, `month_moves`,
`delivery_completeness`, `completeness_reason`, `failure_code`.

판매자 개인정보와 리뷰 본문은 들어가지 않는다 — 전부 수, 날짜, 닫힌 단어다.

---

## 7. 남은 한계

- **page size는 읽기만 한다.** 바꾸지 않는다. `labelledTotal > selectedPageSize`이면 지금 한 페이지로
  그 기간을 증명할 수 없으므로 PARTIAL이고, 더 작게 쪼개거나 별도 pagination capability가 필요하다.
- **이 규칙들은 아직 live로 다시 실행되지 않았다.** §2는 실측이고 §3은 그 실측에서 뽑은 규칙이지만,
  구현된 lane이 end-to-end로 과거 창을 읽은 live proof는 아직 없다. 그 run이 성립하면 이 문서 §2에
  결과를 붙이고 `docs/evidence/INDEX.md`의 행을 갱신한다.
