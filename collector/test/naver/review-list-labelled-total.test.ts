import { describe, expect, it } from "vitest";
import {
  NAVER_LABELLED_TOTAL_FN,
  buildNaverLabelledTotalScript,
  buildNaverReviewListReadScript,
} from "../../src/naver/review-list-observe-inpage.js";

/**
 * <b>「리뷰목록 (총 N개)」 — the guided import's expected-row read.</b>
 *
 * <p>The backend FAILS a segment whose file disagrees with this number, so every case where the page is not
 * saying exactly one total must answer null. The script runs against a fake document here; the code under test
 * is the exact string the live driver evaluates.
 */
function run(opts: { texts: string[]; loadingOverlay?: { display?: string; visibility?: string } | false }) {
  const nodes = opts.texts.map((t) => ({ textContent: t }));
  const overlay = opts.loadingOverlay
    ? [{ __style: { display: opts.loadingOverlay.display ?? "block", visibility: opts.loadingOverlay.visibility ?? "visible" } }]
    : [];
  const document = {
    querySelectorAll: (sel: string) => {
      if (sel.indexOf("ag-overlay-loading-wrapper") >= 0) return overlay;
      return nodes;
    },
  };
  const window = { getComputedStyle: (el: { __style: unknown }) => el.__style };
  return new Function("document", "window", `return (${buildNaverLabelledTotalScript()});`)(document, window) as {
    total: number | null;
    loading: boolean;
  };
}

describe("buildNaverLabelledTotalScript", () => {
  it("reads the single labelled total (the live-measured h3 shape)", () => {
    expect(run({ texts: ["리뷰목록 (총 42개)", "500개씩"] })).toEqual({ total: 42, loading: false });
  });

  it("reads a comma-grouped total", () => {
    expect(run({ texts: ["리뷰목록 (총 1,234개)"] })).toEqual({ total: 1234, loading: false });
    expect(run({ texts: ["리뷰목록 ( 총  12,345 개 )"] }).total).toBe(12345);
  });

  it("the same total printed twice (heading + wrapper) is still one total", () => {
    expect(run({ texts: ["리뷰목록 (총 42개)", "(총 42개)"] }).total).toBe(42);
  });

  it("answers null when no label is present", () => {
    expect(run({ texts: ["리뷰목록", "500개씩", "검색"] })).toEqual({ total: null, loading: false });
    expect(run({ texts: [] }).total).toBeNull();
  });

  it("answers null when two labels disagree — never picks one", () => {
    expect(run({ texts: ["리뷰목록 (총 42개)", "(총 41개)"] }).total).toBeNull();
  });

  it("does not read a total out of long text or a different unit", () => {
    // Longer than the label budget — a sentence that merely contains the words is not the label.
    expect(run({ texts: ["이 기간의 리뷰는 모두 총 42개이며 그중 사진 리뷰는 10개입니다"] }).total).toBeNull();
    expect(run({ texts: ["리뷰목록 (총 42건)"] }).total).toBeNull();
  });

  it("refuses while ag-Grid's loading overlay is visible — the label may still describe the previous period", () => {
    expect(run({ texts: ["리뷰목록 (총 42개)"], loadingOverlay: {} })).toEqual({ total: null, loading: true });
  });

  it("a hidden loading overlay is not loading", () => {
    expect(run({ texts: ["리뷰목록 (총 42개)"], loadingOverlay: { display: "none" } }).total).toBe(42);
    expect(run({ texts: ["리뷰목록 (총 42개)"], loadingOverlay: { visibility: "hidden" } }).total).toBe(42);
  });

  it("shares the observe lane's parser verbatim — one parser, not two", () => {
    expect(NAVER_LABELLED_TOTAL_FN.length).toBeGreaterThan(0);
    expect(buildNaverReviewListReadScript()).toContain(NAVER_LABELLED_TOTAL_FN);
    expect(buildNaverLabelledTotalScript()).toContain(NAVER_LABELLED_TOTAL_FN);
  });

  it("returns primitives only — no page text crosses back", () => {
    const out = run({ texts: ["리뷰목록 (총 42개)"] });
    expect(Object.keys(out).sort()).toEqual(["loading", "total"]);
  });
});
