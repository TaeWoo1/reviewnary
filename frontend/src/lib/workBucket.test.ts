import { describe, expect, it } from "vitest";
import { REASON } from "./copy/customerOps";
import { WORK_FILTERS, workBucket, type HomeWorkRow } from "./homeWork";

/**
 * <b>확인할 일's action tabs are a partition</b> (canonical mockup, 2026-10-02).
 *
 * <p>The tabs each print a count, and five counts over one list only mean anything if every row lands in
 * exactly one of them. That was true by accident before {@link workBucket} — the predicates were
 * independent and happened not to overlap — so this file asserts the property itself rather than the
 * four expressions that used to imply it.
 */
const base: HomeWorkRow = {
  key: "k",
  reason: REASON.reply,
  state: "REPLY_NEEDED",
  source: "카페24 자사몰 문의",
  title: "문의 드립니다",
  line: null,
  since: "2026-09-24",
  to: "/inquiries/1",
  owner: "/inquiries/1",
  caseId: null,
  verb: "검토",
  kind: "INQUIRY",
  subject: "INQUIRY",
  subjectId: "1",
  workItemId: "w1",
  channel: "카페24 자사몰",
  rating: null,
};

const rows: HomeWorkRow[] = [
  base,
  { ...base, key: "case", kind: "CASE", reason: REASON.exchange, subject: "INQUIRY" },
  { ...base, key: "info", reason: REASON.info },
  { ...base, key: "review", kind: "REVIEW", subject: "REVIEW", reason: REASON.review },
  { ...base, key: "withheld", kind: "REVIEW", subject: "REVIEW", reason: REASON.withheld },
  { ...base, key: "approve", kind: "REVIEW", subject: "REVIEW", reason: REASON.approve, state: "AWAITING_APPROVAL" },
  { ...base, key: "draft", kind: "REVIEW", subject: "REVIEW", reason: REASON.draft, state: "DRAFT_NEEDED" },
];

describe("확인할 일 — one row, one primary bucket", () => {
  it("every row is counted by exactly one tab", () => {
    const tabs = WORK_FILTERS.filter((f) => f.key !== "all");
    for (const row of rows) {
      expect(tabs.filter((f) => f.test(row)).map((f) => f.key)).toHaveLength(1);
    }
  });

  it("the tab that counts a row is the row's own bucket", () => {
    for (const row of rows) {
      const tab = WORK_FILTERS.filter((f) => f.key !== "all").find((f) => f.test(row));
      expect(tab?.key).toBe(workBucket(row));
    }
  });

  it("전체 is the sum of the four, never a sixth population", () => {
    const all = WORK_FILTERS.find((f) => f.key === "all")!;
    const counted = WORK_FILTERS.filter((f) => f.key !== "all").reduce((n, f) => n + rows.filter(f.test).length, 0);
    expect(rows.filter(all.test)).toHaveLength(rows.length);
    expect(counted).toBe(rows.length);
  });

  it("the seller's own reply work outranks the noun the row is about", () => {
    // An approval standing on a review is 승인할 일, not 확인할 리뷰: what the row is FOR decides.
    expect(workBucket({ ...base, kind: "REVIEW", subject: "REVIEW", reason: REASON.approve })).toBe("approve");
    expect(workBucket({ ...base, kind: "REVIEW", subject: "REVIEW", reason: REASON.draft })).toBe("draft");
    expect(workBucket({ ...base, kind: "REVIEW", subject: "REVIEW", reason: REASON.review })).toBe("review");
    expect(workBucket(base)).toBe("inquiry");
  });
});
