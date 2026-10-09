import { describe, expect, it } from "vitest";
import { buildNaverReviewListReadScript } from "../../src/naver/review-list-observe-inpage.js";

/**
 * <b>「이 기간에 리뷰가 없다」는 읽기의 결과이지 읽기의 실패가 아니다.</b>
 *
 * <p>2026-10-10 01:44와 01:48, 아직 아무 리뷰도 오지 않은 「오늘」을 읽은 자동 확인이 두 번 연속
 * SURFACE_UNREADABLE로 끝났다. 화면은 멀쩡했고 ag-Grid는 자기 오버레이로 비어 있다고 말하고 있었다.
 * 하루가 시작될 때마다 lane이 고장 나 보이던 것은 「행이 0이면 그리드가 없는 것」이라는 한 줄이었다.
 */
function run(opts: { rows: number; gridRoot?: boolean; noRowsOverlay?: { display?: string; visibility?: string } | false }) {
  const rendered = Array.from({ length: opts.rows }, () => ({
    __AG_0: { renderedRow: { rowNode: { data: null, gridApi: null } } },
    querySelectorAll: () => [],
  }));
  const overlay = opts.noRowsOverlay
    ? [{ __style: { display: opts.noRowsOverlay.display ?? "block", visibility: opts.noRowsOverlay.visibility ?? "visible" } }]
    : [];
  const document = {
    querySelectorAll: (sel: string) => {
      if (sel.indexOf("ag-root-wrapper") >= 0) return opts.gridRoot === false ? [] : [{}];
      if (sel.indexOf("ag-overlay-no-rows-wrapper") >= 0) return overlay;
      if (sel.indexOf(".ag-") < 0) return [];
      if (sel.indexOf("pinned") >= 0) return [];
      return rendered;
    },
  };
  const window = { getComputedStyle: (el: { __style: unknown }) => el.__style };
  const location = { host: "sell.smartstore.naver.com", hash: "#/review/search" };
  return new Function("document", "window", "location", `return (${buildNaverReviewListReadScript()});`)(
    document, window, location,
  ) as { reason: string; rowCount: number; rows: unknown[]; gridReadMode?: string; emptyState?: boolean };
}

describe("빈 기간은 읽기의 결과다", () => {
  it("그려진 그리드 + 보이는 no-rows 오버레이는 0행짜리 읽기다", () => {
    const out = run({ rows: 0, noRowsOverlay: {} });
    expect(out.reason).toBe("OK");
    expect(out.rowCount).toBe(0);
    expect(out.rows).toEqual([]);
    expect(out.emptyState).toBe(true);
    expect(out.gridReadMode).toBe("EMPTY_STATE");
  });

  it("그리드가 그려지지도 않았으면 GRID_NOT_FOUND — 없는 것을 비었다고 하지 않는다", () => {
    expect(run({ rows: 0, gridRoot: false, noRowsOverlay: {} }).reason).toBe("GRID_NOT_FOUND");
  });

  it("오버레이가 없거나 숨어 있으면 비었다는 말을 한 적이 없는 것이다", () => {
    expect(run({ rows: 0 }).reason).toBe("GRID_NOT_FOUND");
    expect(run({ rows: 0, noRowsOverlay: { display: "none" } }).reason).toBe("GRID_NOT_FOUND");
    expect(run({ rows: 0, noRowsOverlay: { visibility: "hidden" } }).reason).toBe("GRID_NOT_FOUND");
  });

  it("행이 있으면 이 분기를 지나지 않는다 — 읽을 것이 있는 화면의 판단은 그리드 모델이 한다", () => {
    // 이 fake에는 grid API가 없으므로 MODEL_UNREADABLE이 된다. 중요한 것은 빈 기간으로 읽히지 않는다는 것.
    expect(run({ rows: 1, noRowsOverlay: {} }).reason).toBe("MODEL_UNREADABLE");
  });
});
