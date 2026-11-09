import { describe, expect, it } from "vitest";
import { BATCH_MS, markTold, planNotices, type NoticeState } from "./workNotifications";
import type { HomeAttentionReview, InquiryQueueItem, ReplyHandoffView } from "./types";

const handoff = (reviewId: string, reason: string, points: string[] = []): ReplyHandoffView => ({
  reviewId, category: null, reason, at: null, rating: 1, preview: null, points,
});
const attention = (reviewId: string, safety = false): HomeAttentionReview =>
  ({ reviewId, accountId: null, channelCode: null, rating: 2, occurredOn: null, productName: "합성 몰딩", quote: null,
    safety }) as HomeAttentionReview;
const inquiry = (workItemId: string, phase: string): InquiryQueueItem =>
  ({ workItemId, inquiryId: workItemId, phase, status: "UNANSWERED", productName: "합성 몰딩" }) as InquiryQueueItem;
const empty: NoticeState = { told: [], lastBatchAt: null };

describe("PC 알림 planning", () => {
  it("a harm is told alone; the rest — reviews and inquiries — wait for one batch", () => {
    const { notices } = planNotices({
      handoffs: [handoff("r1", "CATEGORY_S", ["손을 베임"]), handoff("r2", "KNOWLEDGE_MISSING")],
      attention: [attention("r3"), attention("r4", true)],
      inquiries: [inquiry("q1", "OPEN"), inquiry("q2", "PROPOSED")],
      state: empty, now: 1_000, hidePreview: false,
    });
    expect(notices.filter((n) => n.urgent).map((n) => n.href)).toEqual(["/reviews/reply/r1", "/reviews/reply/r4"]);
    expect(notices[0].body).toContain("손을 베임");
    expect(notices.find((n) => !n.urgent)).toMatchObject({ title: "확인할 일 4건", href: "/" });
  });

  it("one review in two lists is one notice, by its most important reason (third review, 2026-10-28)", () => {
    const { notices } = planNotices({
      handoffs: [handoff("r1", "SAFETY_SIGNAL", ["손을 베임"])], attention: [attention("r1")],
      state: empty, now: 1, hidePreview: false,
    });
    expect(notices).toHaveLength(1);
    expect(notices[0]).toMatchObject({ urgent: true, href: "/reviews/reply/r1" });
  });

  it("only what was shown is told: a notice that failed is planned again", () => {
    const first = planNotices({ handoffs: [handoff("r1", "CATEGORY_S")], attention: [attention("r3")], state: empty,
      now: 1_000, hidePreview: false });
    const afterFailure = markTold(empty, [], 1_000);
    expect(planNotices({ handoffs: [handoff("r1", "CATEGORY_S")], attention: [attention("r3")], state: afterFailure,
      now: 1_100, hidePreview: false }).notices).toHaveLength(first.notices.length);

    const afterShown = markTold(empty, first.notices, 1_000);
    expect(planNotices({ handoffs: [handoff("r1", "CATEGORY_S")], attention: [attention("r3"), attention("r5")],
      state: afterShown, now: 2_000, hidePreview: false }).notices).toEqual([]);
    const later = planNotices({ handoffs: [], attention: [attention("r3"), attention("r5")], state: afterShown,
      now: 1_000 + BATCH_MS, hidePreview: false });
    expect(later.notices).toHaveLength(1);
    expect(later.notices[0].title).toBe("확인할 일 1건");
  });

  it("an ordinary review that later turns urgent is told again, at the urgent level", () => {
    const ordinary = planNotices({ handoffs: [], attention: [attention("r1")], state: empty, now: 1, hidePreview: false });
    const told = markTold(empty, ordinary.notices, 1);
    const urgent = planNotices({ handoffs: [handoff("r1", "CATEGORY_S")], attention: [], state: told, now: 2,
      hidePreview: false });
    expect(urgent.notices).toHaveLength(1);
    expect(urgent.notices[0].urgent).toBe(true);
  });

  it("a hidden preview says only that there is work", () => {
    const { notices } = planNotices({ handoffs: [handoff("r1", "SAFETY_SIGNAL", ["손을 베임"])], attention: [],
      state: empty, now: 1, hidePreview: true });
    expect(notices[0].body).not.toContain("손을 베임");
    expect(notices[0].body).toContain("확인할 일이 있어요");
  });
});
