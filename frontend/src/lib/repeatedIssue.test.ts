import { describe, expect, it } from "vitest";
import {
  evidenceBeforeTrend,
  evidenceMonths,
  hasRatingEvidence,
  knowledgeGapAction,
  knowledgeLine,
  knowledgeScopeLine,
  productSpanLine,
  ratingBands,
  repeatLine,
  trendPeakLine,
  unattributedLine,
} from "./repeatedIssue";
import type {
  IssueEvidenceView,
  IssueKnowledgeOnHand,
  IssueProductEvidenceView,
  IssueRatingDistributionView,
} from "./types";

function row(over: Partial<IssueProductEvidenceView> = {}): IssueProductEvidenceView {
  return {
    productId: "prod-1",
    productName: "전선몰딩",
    evidenceCount: 16,
    productReviews: 1761,
    firstOccurredOn: "2026-06-18",
    lastOccurredOn: "2026-08-02",
    ...over,
  };
}

function knowledge(over: Partial<IssueKnowledgeOnHand> = {}): IssueKnowledgeOnHand {
  return {
    productId: "prod-1",
    productName: "전선몰딩",
    productSources: 0,
    productMentions: 0,
    orgSources: 0,
    orgMentions: 0,
    excerpts: [],
    ...over,
  };
}

describe("어디서 얼마나 반복되나", () => {
  it("states the pair and names what each number counts", () => {
    expect(repeatLine(row())).toBe("리뷰 1,761건 중 16건이 이 문제를 말했습니다");
  });

  /**
   * The single most important thing this module does not do.
   *
   * A rate needs an examined population. The extractor only reads a review that has a body, so a
   * review it never read sits in the denominator and cannot reach the numerator — 16/1761 as a
   * percentage would assert 「0.9% of this product's buyers hit this」, which nobody measured, and it
   * is exactly the number a person would act on.
   */
  it("never turns the pair into a percentage", () => {
    const line = repeatLine(row());
    expect(line).not.toMatch(/%|퍼센트|비율/);
    expect(line).not.toContain("0.9");
  });

  it("reads the product's own span, and collapses a single day", () => {
    expect(productSpanLine(row())).toBe("2026-06-18 ~ 2026-08-02");
    expect(productSpanLine(row({ firstOccurredOn: "2026-07-01", lastOccurredOn: "2026-07-01" })))
      .toBe("2026-07-01");
    expect(productSpanLine(row({ firstOccurredOn: null }))).toBeNull();
  });

  /**
   * Unattributed evidence is stated rather than dropped: it is in the issue's total and in no
   * product's row, so a seller adding the rows up would otherwise find a shortfall with no
   * explanation available anywhere on the screen.
   */
  it("says how much evidence belongs to no product, and that it is outside the rows", () => {
    expect(unattributedLine(2)).toContain("2건");
    expect(unattributedLine(2)).toContain("들어 있지 않습니다");
    expect(unattributedLine(0)).toBeNull();
  });
});

describe("우리가 써 둔 것", () => {
  /**
   * Three states, three sentences. Collapsing them is how a seller who already wrote the answer gets
   * told to go write it.
   */
  it("tells an empty library apart from one that simply does not cover this problem", () => {
    expect(knowledgeLine(knowledge())).toBe("이 문제에 대해 등록된 안내가 아직 없습니다.");
    expect(knowledgeLine(knowledge({ productSources: 3, orgSources: 2 })))
      .toBe("등록된 안내 5건 가운데 이 문제를 다루는 내용은 찾지 못했습니다.");
    expect(knowledgeLine(knowledge({ productSources: 3, productMentions: 1, orgSources: 2 })))
      .toBe("등록된 안내 5건 가운데 1건이 이 문제를 다룹니다.");
  });

  it("names which library it read, and says nothing when there was no product library", () => {
    expect(knowledgeScopeLine(knowledge())).toContain("전선몰딩");
    expect(knowledgeScopeLine(knowledge({ productId: null, productName: null }))).toBeNull();
  });

  /**
   * The invitation is offered only where something is missing. Printing it over a library that
   * already answers the problem is the screen asking for work that is already done.
   */
  it("offers the gap action only when nothing names this problem", () => {
    expect(knowledgeGapAction(knowledge({ productSources: 3 }))).toBe("답변 기준 채우기");
    expect(knowledgeGapAction(knowledge({ productSources: 3, productMentions: 1 }))).toBeNull();
    expect(knowledgeGapAction(knowledge({ orgSources: 1, orgMentions: 1 }))).toBeNull();
  });
});

function spread(over: Partial<IssueRatingDistributionView> = {}): IssueRatingDistributionView {
  return { rating1: 0, rating2: 0, rating3: 3, rating4: 5, rating5: 10, unrated: 0, ...over };
}

describe("어떤 별점에서 나왔나", () => {
  it("reports counts of evidence, worst-rating last, and keeps empty bands", () => {
    const bands = ratingBands(spread());
    expect(bands.map((b) => b.labelKo)).toEqual(["5점", "4점", "3점", "2점", "1점", "별점 없음"]);
    expect(bands.map((b) => b.count)).toEqual([10, 5, 3, 0, 0, 0]);
  });

  /**
   * The whole reason empty bands survive: 접착 부족 on this org is raised entirely inside good
   * ratings. A table that dropped its zero rows would hide exactly that.
   */
  it("keeps a zero band so 'nobody is angry and it keeps happening' is visible", () => {
    const bands = ratingBands(spread());
    const angry = bands.filter((b) => b.key === "1" || b.key === "2");
    expect(angry).toHaveLength(2);
    expect(angry.every((b) => b.count === 0)).toBe(true);
  });

  /**
   * No share, no average, no importance. A mean star over the units that happened to match this
   * problem describes the extractor's matching, not the product — and severity comes from the
   * problem vocabulary, deliberately never from a rating.
   */
  it("derives no rate, mean or ranking from the counts", () => {
    const bands = ratingBands(spread());
    for (const band of bands) {
      expect(Number.isInteger(band.count)).toBe(true);
      expect(band).not.toHaveProperty("share");
      expect(band).not.toHaveProperty("average");
      expect(band).not.toHaveProperty("weight");
    }
  });

  it("says there is nothing to spread rather than drawing six zeroes", () => {
    expect(hasRatingEvidence(spread())).toBe(true);
    expect(hasRatingEvidence(spread({ rating3: 0, rating4: 0, rating5: 0 }))).toBe(false);
  });
});

describe("월별 근거 건수 — the shape of the repetition", () => {
  const ev = (occurredOn: string): IssueEvidenceView => ({
    reviewId: `r-${occurredOn}`,
    unitOrdinal: 0,
    occurredOn,
    productId: null,
    productName: null,
    rating: null,
    quote: "x",
  });

  it("counts the dates the read returned, and fills the months between them", () => {
    const months = evidenceMonths([ev("2026-01-04"), ev("2026-01-20"), ev("2026-03-02")], "2026-03");
    expect(months).toEqual([
      { key: "2026-01", count: 2 },
      { key: "2026-02", count: 0 },
      { key: "2026-03", count: 1 },
    ]);
  });

  /**
   * The axis is the seller's calendar, not the problem's. Stopping at the last piece of evidence draws a
   * chart that ends where the problem stopped being mentioned — which reads as 「still happening」 for a
   * problem nobody has raised in half a year.
   */
  it("runs to today even when the last evidence is months old", () => {
    const months = evidenceMonths([ev("2026-01-04")], "2026-04");
    expect(months.map((m) => m.key)).toEqual(["2026-01", "2026-02", "2026-03", "2026-04"]);
    expect(months.map((m) => m.count)).toEqual([1, 0, 0, 0]);
  });

  /**
   * This org holds a problem whose first evidence is from 2015. A bar per month since would be 137 bars
   * three pixels wide — and the window is only honest if what falls outside it is stated.
   */
  it("bounds the window at two years and reports what fell outside it", () => {
    const old = [ev("2015-05-26"), ev("2018-11-13"), ev("2026-02-08")];
    const months = evidenceMonths(old, "2026-10");
    expect(months).toHaveLength(24);
    expect(months[0].key).toBe("2024-11");
    expect(evidenceBeforeTrend(old, months)).toBe(2);
  });

  it("says nothing at all when there is no evidence", () => {
    expect(evidenceMonths([], "2026-10")).toEqual([]);
    expect(evidenceBeforeTrend([], [])).toBe(0);
  });

  /**
   * The whole rule this chart lives under. Whether a problem is 급증 or 집중 is the server's judgement
   * (`IssueChangeView`); a chart that derived a second opinion would be an unmeasured verdict drawn in
   * the same pane as the measured one.
   */
  it("states the peak as a count and derives no rate, average or direction", () => {
    const months = evidenceMonths([ev("2026-01-04"), ev("2026-01-20"), ev("2026-02-02")], "2026-02");
    expect(trendPeakLine(months)).toBe("가장 많았던 달 2건");
    expect(trendPeakLine(months) ?? "").not.toMatch(/%|배|증가|감소|평균/);
    expect(trendPeakLine([{ key: "2026-01", count: 0 }])).toBeNull();
  });
});
