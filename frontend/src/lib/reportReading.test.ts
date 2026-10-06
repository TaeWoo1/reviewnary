import { describe, expect, it } from "vitest";
import {
  changeOf,
  counterById,
  figureOf,
  hasReadRange,
  noChangeReason,
  readChannels,
  readVerdict,
  ungated,
  ungatedEdition,
} from "./reportReading";
import type { ReportCounter, ReportRead } from "./types";

/**
 * <b>리포트의 0이 무엇을 뜻하는가</b> (리포트 redesign, 2026-10-06).
 *
 * <p>판정 자체는 서버가 한다 — {@code ReportFactsBuilder}가 Overview의 {@code counted()}로 기간을 세고,
 * 자격을 갖춘 채널이 없으면 숫자 대신 {@code null}을 저장한다. 그래서 여기서 검사하는 것은 freshness 규칙이
 * 아니라 <b>저장된 사실을 화면이 옮기는 방식</b>이다. 고정값은 2026-10-06 측정에서 왔다.
 */
function counter(over: Partial<ReportCounter> = {}): ReportCounter {
  return {
    id: "c-reviews",
    labelKo: "받은 리뷰",
    periodic: true,
    current: 25,
    previous: 17,
    delta: 8,
    to: null,
    unit: "건",
    dataType: "REVIEW",
    excludedChannels: 0,
    unproven: false,
    ...over,
  };
}

describe("figureOf — 0은 두 가지다", () => {
  it("모든 채널이 말한 기간이면 0건도 사실이다", () => {
    expect(figureOf(counter({ current: 0 }))).toEqual({ kind: "MEASURED", value: 0 });
  });

  it("관측된 결함: 읽지 못한 창의 null에는 숫자를 적지 않는다", () => {
    expect(figureOf(counter({ current: null, previous: null, delta: null }))).toEqual({ kind: "UNREAD" });
  });

  it("센 채널은 있지만 전부는 아니면, 숫자는 남고 빠진 수를 함께 적는다", () => {
    expect(figureOf(counter({ current: 25, excludedChannels: 2 }))).toEqual({
      kind: "PARTIAL",
      value: 25,
      excluded: 2,
    });
  });

  it("센 채널의 수집이 최신임이 증명되지 않았으면 그것도 단서다", () => {
    expect(figureOf(counter({ current: 25, unproven: true }))).toEqual({ kind: "PARTIAL", value: 25, excluded: 0 });
  });

  it("수치가 아예 없으면 읽지 못한 것이다", () => {
    expect(figureOf(null)).toEqual({ kind: "UNREAD" });
    expect(figureOf(undefined)).toEqual({ kind: "UNREAD" });
  });

  /**
   * <b>관문 이전에 저장된 판</b>. 데모 org의 주간 판이 바로 그것이고, {@code current: 0} ·
   * {@code previous: 4} · {@code delta: -4}를 들고 있다. 숫자는 지우지 않지만 측정값인 척하지도 않는다 —
   * {@code dataType}이 통째로 비어 있다는 사실이 그 자체로 증거다.
   */
  it("관문 이전의 판은 숫자를 남기되 측정됐다고 말하지 않는다", () => {
    const legacy = { id: "c-reviews", labelKo: "받은 리뷰", periodic: true, current: 81, previous: 65, delta: 16, to: null };
    expect(figureOf(legacy as ReportCounter)).toEqual({ kind: "UNGATED", value: 81 });
    expect(ungated(legacy as ReportCounter)).toBe(true);
  });

  it("관문 이전인지는 판 전체로 판정한다 — 기간 수치 하나라도 기록돼 있으면 새 판이다", () => {
    const legacy = [{ id: "c-reviews", labelKo: "받은 리뷰", periodic: true, current: 81, previous: 65, delta: 16, to: null }];
    expect(ungatedEdition(legacy as ReportCounter[])).toBe(true);
    expect(ungatedEdition([counter()])).toBe(false);
    expect(ungatedEdition([])).toBe(false);
  });
});

describe("changeOf — 비교는 서버가 두 창을 다 측정했을 때만 저장된다", () => {
  it("저장된 delta가 있으면 그대로 쓴다 — 화면이 두 수를 빼서 다시 만들지 않는다", () => {
    expect(changeOf(counter())).toEqual({ current: 25, previous: 17, delta: 8 });
  });

  it("관문 이전의 delta는 읽지 않은 창과의 비교일 수 있으므로 세우지 않는다", () => {
    const legacy = { id: "c-inquiries", labelKo: "받은 문의", periodic: true, current: 0, previous: 4, delta: -4, to: null };
    expect(changeOf(legacy as ReportCounter)).toBeNull();
    expect(noChangeReason(legacy as ReportCounter)).toBe("이 판은 수집 범위를 확인하기 전에 만들어졌습니다");
  });

  it("관측된 결함: 읽지 않은 창에서 「4건 줄음」이 나오지 않는다", () => {
    const unread = counter({ id: "c-inquiries", current: null, previous: 4, delta: null });
    expect(changeOf(unread)).toBeNull();
    expect(noChangeReason(unread)).toBe("이 기간의 수집 결과를 확인하지 못했습니다");
  });

  it("이전 창을 측정하지 못했으면 비교가 아니라 기준이 없는 것이다", () => {
    const noBase = counter({ previous: null, delta: null });
    expect(changeOf(noBase)).toBeNull();
    expect(noChangeReason(noBase)).toBe("이전 기간의 수집 결과를 확인하지 못했습니다");
  });

  it("둘 다 측정됐고 움직이지 않았으면 그것도 사실이다", () => {
    expect(changeOf(counter({ current: 17, delta: 0 }))).toEqual({ current: 17, previous: 17, delta: 0 });
    expect(noChangeReason(counter({ current: 17, delta: 0 }))).toBe("이전 기간과 같습니다");
  });
});

describe("counterById — 어느 수치가 어느 수집에 기대는가", () => {
  const counters: ReportCounter[] = [
    counter({ id: "c-reviews", dataType: "REVIEW" }),
    counter({ id: "c-inquiries", dataType: "INQUIRY" }),
    counter({ id: "c-unanswered-now", dataType: "INQUIRY", periodic: false }),
    counter({ id: "c-revenue", dataType: "ORDER_SUMMARY", unit: "원" }),
  ];

  it("id로 찾는다 — 미답변은 기간이 없는 수치다", () => {
    expect(counterById(counters, "c-unanswered-now")?.periodic).toBe(false);
    expect(counterById(counters, "c-nope")).toBeNull();
  });
});

/* ─────────────── 얼린 읽은 범위 ─────────────── */

function read(over: Partial<ReportRead> = {}): ReportRead {
  return {
    dataType: "REVIEW",
    labelKo: "리뷰",
    lastReadAt: "2026-09-26T18:31:17Z",
    measured: true,
    included: [
      {
        channelCode: "NAVER",
        channelNameKo: "네이버 스마트스토어",
        state: "OBSERVED_FRESHNESS_UNPROVEN",
        lastReadAt: "2026-09-26T18:31:17Z",
        reasonKo: null,
      },
    ],
    excluded: [],
    ...over,
  };
}

/**
 * <b>읽은 범위는 숫자와 같은 시각에 얼어야 한다</b> (2026-10-06). 라이브로 읽던 때에는 9월 27일까지 읽고
 * 만든 판이 다음 날 10월 8일까지 읽은 것처럼 보였다 — 사실은 멈춰 있는데 그 자격만 움직였다.
 */
describe("readVerdict — 이 판이 이 종류를 어디까지 셌는가", () => {
  it("전부 포함 · 채널 N곳 빠짐 · 확인 못 함 · 기록 없음", () => {
    expect(readVerdict(read())).toEqual({ text: "전부 포함", warn: false });
    expect(
      readVerdict(read({ excluded: [{ ...read().included[0], channelCode: "CAFE24", reasonKo: "연결이 끊겨 수집이 멈췄습니다" }] })),
    ).toEqual({ text: "채널 1곳 빠짐", warn: true });
    expect(readVerdict(read({ measured: false, included: [] }))).toEqual({ text: "확인 못 함", warn: true });
    // 기록이 아예 없는 판 — 오늘의 수집 상태로 보완하지 않는다.
    expect(readVerdict(null)).toEqual({ text: "기록 없음", warn: true });
  });
});

describe("hasReadRange — 옛 판에는 읽은 범위가 없다", () => {
  it("비어 있으면 기록하지 않은 것이다", () => {
    expect(hasReadRange([])).toBe(false);
    expect(hasReadRange(null)).toBe(false);
    expect(hasReadRange(undefined)).toBe(false);
    expect(hasReadRange([read()])).toBe(true);
  });
});

describe("readChannels — 센 채널과 빠진 채널을 한 줄로", () => {
  it("센 쪽이 먼저, 빠진 쪽은 이유와 함께 뒤에", () => {
    const excluded = { ...read().included[0], channelCode: "CAFE24", channelNameKo: "카페24 자사몰", reasonKo: "연결되어 있지 않습니다" };
    expect(readChannels(read({ excluded: [excluded] })).map((c) => c.channelCode)).toEqual(["NAVER", "CAFE24"]);
    expect(readChannels(null)).toEqual([]);
  });
});
