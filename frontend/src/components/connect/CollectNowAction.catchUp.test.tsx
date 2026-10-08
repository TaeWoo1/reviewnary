// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { catchUpMessage, screenReadMessage } from "./CollectNowAction";
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
    ...over,
  };
}

function walk(runState: NonNullable<ScreenReadView["catchUp"]>["runState"], windowsDone: number,
              over: Partial<ScreenReadView> = {}): ScreenReadView {
  return read({ catchUp: { windowsDone, rowsObserved: windowsDone * 45, runState, stopReason: null }, ...over });
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
