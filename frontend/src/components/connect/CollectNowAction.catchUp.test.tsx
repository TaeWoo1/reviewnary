// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { catchUpMessage, objectParticle, screenReadMessage, stopSentence } from "./CollectNowAction";
import type { ScreenReadView } from "../../lib/types";

/**
 * <b>판매자는 구현의 기간을 볼 필요가 없다.</b>
 *
 * <p>쓸 수 있는 사실은 둘이다: 지금 밀린 것을 따라잡는 중이라는 것과, 얼마나 왔는지. 「09-03~09-09를 읽고
 * 있습니다」를 읽은 판매자가 할 수 있는 일은 없고, 그 날짜는 실행 기록의 것이다.
 *
 * <p>그리고 멈춤 넷은 각자 다른 다음 행동이다 — 로그인하기, 그 하루는 더 있을 수 있다고 알기, 다시 누르기,
 * 기다리기. 하나의 「수집하지 못했습니다」로 뭉치면 다섯 주를 제대로 읽은 실행이 실패로 보인다.
 */
function read(over: Partial<ScreenReadView> = {}): ScreenReadView {
  return {
    jobId: "job-1",
    state: "RUNNING",
    observed: null,
    inserted: null,
    changed: null,
    complete: false,
    startedAt: null,
    finishedAt: null,
    catchUp: null,
    failureCode: null,
    ...over,
  };
}

function walk(runState: NonNullable<ScreenReadView["catchUp"]>["runState"], windowsDone: number,
              over: Partial<ScreenReadView> & { stopReason?: string | null } = {}): ScreenReadView {
  const { stopReason = null, ...rest } = over;
  return read({ catchUp: { windowsDone, rowsObserved: windowsDone * 45, runState, stopReason }, ...rest });
}

describe("밀린 리뷰를 따라잡는 중", () => {
  it("진행 중에는 진행 중이라고만 말하고, 몇 개까지 왔는지 더한다", () => {
    expect(catchUpMessage(walk("RUNNING", 0))).toEqual({
      text: "밀린 리뷰를 확인하고 있습니다.", isError: false,
    });
    expect(catchUpMessage(walk("RUNNING", 3))).toEqual({
      text: "밀린 리뷰를 확인하고 있습니다. 3개 기간 확인됨", isError: false,
    });
  });

  it("끝나면 끝났다고 말한다", () => {
    expect(catchUpMessage(walk("COMPLETE", 6, { inserted: 312 }))).toEqual({
      text: "밀린 리뷰를 모두 확인했습니다. 새로 저장 312건", isError: false,
    });
  });

  it("멈춤 넷이 각자 다른 말을 한다", () => {
    expect(catchUpMessage(walk("PAUSED_AUTH", 2))!.text)
      .toBe("2개 기간을 확인했고, 그다음 기간에서 판매자 센터 로그인이 필요했습니다.");
    expect(catchUpMessage(walk("STOPPED_SATURATED", 4))!.text)
      .toContain("한 번에 읽을 수 있는 양보다 많은 리뷰가 있어");
    expect(catchUpMessage(walk("STOPPED_LIMIT", 8))!.text).toContain("다시 누르면 이어서 확인합니다");
    expect(catchUpMessage(walk("FAILED", 1))!.text).toContain("멈췄습니다");
    // 상한에 도달한 것은 실패가 아니다 — 다시 누르면 이어진다.
    expect(catchUpMessage(walk("STOPPED_LIMIT", 8))!.isError).toBe(false);
    // 앞에서 확인한 기간의 수는 어느 멈춤에서도 사라지지 않는다.
    for (const state of ["PAUSED_AUTH", "STOPPED_SATURATED", "STOPPED_LIMIT", "FAILED"] as const) {
      expect(catchUpMessage(walk(state, 2))!.text).toContain("2개 기간");
    }
  });

  it("기간의 날짜도 구현 용어도 한 글자 나오지 않는다", () => {
    const said = (["RUNNING", "COMPLETE", "PAUSED_AUTH", "STOPPED_SATURATED", "STOPPED_LIMIT", "FAILED"] as const)
      .map((s) => catchUpMessage(walk(s, 3))!.text)
      .join(" ");
    expect(said).not.toMatch(/\d{4}-\d{2}-\d{2}/);
    for (const term of ["window", "job", "recipe", "cursor", "saturat", "coverage", "run", "capacity",
      "MAX_", "SCREEN_READ"]) {
      expect(said, term).not.toContain(term);
    }
  });

  it("걸음이 있으면 한 건짜리 문장은 쓰이지 않는다 — 그 반대도 그대로다", () => {
    // 자식 하나가 끝났어도 걸음이 돌고 있으면 「수집 완료」라고 말하지 않는다. 그게 바로 이 패키지가 한
    // 단계 위에서 고친 결함과 같은 모양이다.
    expect(screenReadMessage("리뷰", walk("RUNNING", 1, { state: "SUCCESS", inserted: 45 })).text)
      .toBe("밀린 리뷰를 확인하고 있습니다. 1개 기간 확인됨");
    // 걸음이 없으면 지금까지의 문장 그대로.
    expect(screenReadMessage("리뷰", read({ state: "SUCCESS", inserted: 45, changed: 0 })).text)
      .toBe("리뷰 수집 완료: 새로 저장 45 · 갱신 0");
    expect(catchUpMessage(read())).toBeNull();
  });
});

describe("막힌 자리를 말한다 — 2026-10-08 라이브가 가르친 것", () => {
  it("기간 선택 영역을 확인하지 못한 아홉 가지가 한 문장으로 모인다", () => {
    // 판매자가 할 수 있는 일은 아홉 경우 모두 같다: 그 화면을 한 번 열어 기간 영역이 있는지 보는 것.
    for (const code of ["DATE_CONTROL_CANDIDATES_UNREADABLE", "RANGE_CONTROLS_NOT_FOUND",
      "RANGE_CONTROLS_AMBIGUOUS", "QUERY_CONTROL_NOT_FOUND", "QUERY_CONTROL_AMBIGUOUS",
      "RANGE_ORDER_UNKNOWN", "RANGE_NOT_SETTABLE", "CALENDAR_OPENER_NOT_FOUND",
      "CALENDAR_OPENER_AMBIGUOUS"]) {
      expect(stopSentence(code), code).toBe("판매자센터 화면에서 기간 선택 영역을 확인하지 못했습니다.");
    }
  });

  it("달력을 열고 그 안에서 막힌 일곱 가지는 달력을 가리킨다 — 다음에 볼 자리가 다르다", () => {
    // 「기간 영역을 못 찾았다」와 「달력에서 날짜를 못 골랐다」는 판매자가 지금 할 일은 비슷하지만, 고치러
    // 갈 자리가 전혀 다르다. 화면 문장은 그 차이를 말하고, 어느 시험에서 멈췄는지는 기록이 들고 있는다.
    for (const code of ["PICKER_VIEW_UNREADABLE", "MONTH_NAV_NOT_FOUND", "MONTH_NAV_AMBIGUOUS",
      "MONTH_NAV_UNVERIFIED", "MONTH_NAV_EXHAUSTED", "DAY_CELL_NOT_FOUND", "DAY_CELL_AMBIGUOUS"]) {
      expect(stopSentence(code), code).toBe("판매자센터 달력에서 날짜를 고르지 못했습니다.");
    }
  });

  it("모르는 단어는 지어내지 않고 일반 문장으로 돌아간다", () => {
    for (const code of [null, undefined, "SOMETHING_NEW", "RUNTIME_FAULT", "READING_REFUSED", "AUTH_REQUIRED"]) {
      expect(stopSentence(code), String(code)).toBeNull();
    }
  });

  it("로그인 redirect와 「아직 안 열렸다」와 「찾지 못했다」는 서로 다른 문장이다", () => {
    // 2026-10-09: 세션이 끊긴 상태로 리뷰 주소를 열면 NAVER가 로그인 화면으로 보낸다. 그때 「리뷰 화면을
    // 찾지 못했습니다」가 뜨면 판매자는 할 수 있는 일이 없다고 읽는다 — 실제로는 로그인하면 되는 상황이고,
    // 그 복구 경로는 이미 있었다. 세 경우는 다음에 할 일이 다르므로 문장도 달라야 한다.
    expect(stopSentence("AUTH_REQUIRED")).toBeNull();          // 로그인 안내는 별도 경로가 띄운다
    expect(stopSentence("ROUTE_NOT_READY")).toBe("판매자센터 리뷰 화면이 아직 열리지 않았습니다.");
    expect(stopSentence("SURFACE_UNEXPECTED")).toBe("판매자센터 리뷰 화면을 찾지 못했습니다.");
    expect(stopSentence("ROUTE_NOT_READY")).not.toBe(stopSentence("SURFACE_UNEXPECTED"));
  });

  it("도우미가 프로그램을 못 돌린 네 경우는 화면에 기술 용어를 내보내지 않는다", () => {
    // 이 넷은 판매자가 판매자센터에서 할 수 있는 일이 없는 멈춤이다 — 기다렸다 다시 누르는 것뿐. 그래서
    // 화면 문장은 기존 일반 복구 문구 그대로이고, 원인 구분은 기록(failureCode)이 들고 있는다.
    for (const code of ["EXECUTOR_UNAVAILABLE", "EXECUTOR_TIMEOUT", "EXECUTOR_REFUSED", "EXECUTOR_FAULT"]) {
      expect(stopSentence(code), code).toBeNull();
    }
    // 그리고 그 일반 문구에 코드가 섞여 나오지 않는다.
    const said = catchUpMessage(walk("FAILED", 0, { stopReason: "EXECUTOR_TIMEOUT" }))!.text;
    expect(said).toBe("0개 기간을 확인한 뒤 멈췄습니다. 잠시 후 다시 시도해 주세요.");
    for (const term of ["EXECUTOR", "TIMEOUT", "UNAVAILABLE", "FAULT", "REFUSED"]) {
      expect(said, term).not.toContain(term);
    }
  });

  it("어느 문장에도 내부 용어가 없다", () => {
    const said = ["DATE_CONTROL_CANDIDATES_UNREADABLE", "RANGE_MISMATCH", "SURFACE_UNEXPECTED",
      "DAY_CELL_AMBIGUOUS", "MONTH_NAV_UNVERIFIED", "CALENDAR_OPENER_AMBIGUOUS", "ROUTE_NOT_READY"]
      .map((c) => stopSentence(c)!)
      .join(" ");
    for (const term of ["selector", "locator", "candidate", "control", "CSS", "index", "DOM", "range",
      "window", "SURFACE", "query", "cell", "picker", "nav", "opener", "route", "hash", "host"]) {
      expect(said, term).not.toContain(term);
    }
  });

  it("한 건 실패에도, 걸음 실패에도 같은 문장이 쓰인다", () => {
    expect(screenReadMessage("리뷰", read({ state: "FAILED", failureCode: "RANGE_CONTROLS_NOT_FOUND" })).text)
      .toBe("판매자센터 화면에서 기간 선택 영역을 확인하지 못했습니다.");
    expect(catchUpMessage(walk("FAILED", 2, { stopReason: "RANGE_CONTROLS_NOT_FOUND" }))!.text)
      .toBe("2개 기간을 확인했습니다. 판매자센터 화면에서 기간 선택 영역을 확인하지 못했습니다.");
    // 아직 아무 기간도 확인하지 못했으면 0을 세어 보여 주지 않는다.
    expect(catchUpMessage(walk("FAILED", 0, { stopReason: "RANGE_CONTROLS_NOT_FOUND" }))!.text)
      .toBe("판매자센터 화면에서 기간 선택 영역을 확인하지 못했습니다.");
  });

  it("조사를 고른다 — 「리뷰을(를)」이 아니라", () => {
    // 둘 다 적어 두는 것은 둘 중 어느 것도 고르지 않은 것이고, 판매자가 읽는 것은 고르지 않은 그 모양이다.
    expect(screenReadMessage("리뷰", read({ state: "FAILED" })).text)
      .toBe("리뷰를 수집하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    expect(screenReadMessage("문의", read({ state: "FAILED" })).text)
      .toBe("문의를 수집하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    expect(screenReadMessage("주문", read({ state: "FAILED" })).text).toContain("주문을 수집하지 못했습니다");
    expect(objectParticle("리뷰")).toBe("를");
    expect(objectParticle("문의")).toBe("를");
    expect(objectParticle("주문")).toBe("을");
    expect(objectParticle("상품")).toBe("을");
    // 한글이 아니면 「을」 — 「CSV을」이 「CSV를」보다 덜 어색하다.
    expect(objectParticle("CSV")).toBe("을");
  });
});
