import { describe, expect, it } from "vitest";
import { HELD_DRAFT_LINE, boardRow, checkHref, partialNotice, tallyText, tallyWithUnit, todoBreakdown, type HomeBoardView, type ServerWorkRow } from "./homeBoard";
import { inquiryWorkRow } from "./homeWork";
import type { InquiryQueueItem } from "./types";

describe("tallyText — the one way a count is written", () => {
  it("an exact count is the number, a vouched floor is 「N+」, and nothing vouched is 「—」 — never 0", () => {
    expect(tallyText({ count: 12, atLeast: 12 })).toBe("12");
    expect(tallyText({ count: 0, atLeast: 0 })).toBe("0");
    expect(tallyText({ count: null, atLeast: 3 })).toBe("3+");
    expect(tallyText({ count: null, atLeast: 0 })).toBe("—");
    expect(tallyText(null)).toBe("—");
    expect(tallyWithUnit({ count: null, atLeast: 0 })).toBe("—");
    expect(tallyWithUnit({ count: 4, atLeast: 4 })).toBe("4건");
  });
});

describe("todoBreakdown — the band's one line of what 할 일 is made of", () => {
  const t = (n: number | null, floor = n ?? 0) => ({ count: n, atLeast: floor });
  const todo = (over: Partial<HomeBoardView["todo"]>): HomeBoardView["todo"] => ({
    total: t(3), safety: t(0), approvable: t(0), praiseApprovable: t(0), needsInfo: t(0), needsReply: t(2), needsLook: t(0),
    checkAgain: t(1), alsoDueToday: t(1), todayChecks: t(2), ...over,
  });

  it("names the buckets that hold something, then the view across them", () => {
    expect(todoBreakdown(todo({}))).toBe("답변 필요 2 · 다시 확인 1 — 오늘 확인 모두 2건");
    expect(todoBreakdown(todo({ alsoDueToday: t(0), todayChecks: t(1) }))).toBe("답변 필요 2 · 다시 확인 1");
  });

  it("an unknown bucket is named only when the total is unknown too", () => {
    expect(todoBreakdown(todo({ safety: t(null) }))).toBe("답변 필요 2 · 다시 확인 1 — 오늘 확인 모두 2건");
    expect(todoBreakdown(todo({ total: t(null, 3), safety: t(null) }))).toContain("안전 확인 —");
  });
});

describe("partialNotice — what a partial read does to the seller's list", () => {
  const all = (over: Record<string, { read: boolean; complete: boolean }>) =>
    (["CASES", "REVIEWS", "INQUIRIES", "FOLLOW_UPS"] as const).map((name) => ({ name, ...(over[name] ?? { read: true, complete: true }) }));

  it("says nothing when everything was read whole", () => {
    expect(partialNotice(all({}))).toEqual([]);
  });

  it("names the effect, never the machinery — no source names, limits or system words", () => {
    const lines = partialNotice(all({ CASES: { read: true, complete: false }, INQUIRIES: { read: false, complete: false }, REVIEWS: { read: true, complete: false } }));
    expect(lines).toEqual([
      "이미 처리된 일인지 다 확인하지 못했어요. 목록에 처리된 일이 섞여 보일 수 있어 할 일 수는 비워 두었어요.",
      "문의를 불러오지 못해서 그 일은 지금 목록에 없어요. 관련된 수는 비워 두었어요.",
      "리뷰가 많아 일부만 불러왔어요. 목록 뒤쪽에 더 있을 수 있어요.",
    ]);
    for (const line of lines) expect(line).not.toMatch(/CASES|INQUIRIES|REVIEWS|FOLLOW_UPS|출처|상한|소스|API|200/);
  });
});

describe("boardRow — a server row drawn by the function that drew its record before", () => {
  const item = {
    workItemId: "w-9", inquiryId: "i-9", sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24", channelNameKo: "카페24",
    productId: null, productName: null, phase: "OPEN", status: "UNANSWERED", title: "합성 문의", snippet: "합성-본문 앞부분",
    receivedAt: "2026-10-21T01:00:00Z", hasDraft: false,
  } as InquiryQueueItem;
  const server: ServerWorkRow = {
    key: "inquiry:i-9", owner: "/inquiries/i-9", to: "/inquiries/i-9", kind: "INQUIRY", subject: "INQUIRY", subjectId: "i-9",
    caseId: null, workItemId: "w-9", state: "REPLY_NEEDED", delegatedState: null, bucket: "needsReply", past: false,
    praise: false, safety: false, safetyReading: null, safetyKind: null, channelCode: "CAFE24", channelNameKo: "카페24",
    rating: null, title: "합성 문의", context: null, since: "2026-10-21T01:00:00Z", dueToday: true, nextCheckOn: null,
    also: [
      { action: "CHECK_AGAIN", workItemId: "w-9", checkId: "chk-1", waitingOn: "ACTION", nextCheckOn: "2026-11-10", dueToday: true, since: "2026-11-07T01:00:00Z", note: "합성-진행 기록", to: "/inquiries/i-9" },
      { action: "CHECK_AGAIN", workItemId: "w-9", checkId: "chk-0", waitingOn: "ACTION", nextCheckOn: "2026-11-20", dueToday: false, since: null, note: null, to: "/inquiries/i-9" },
    ],
    origin: { kind: "INQUIRY", caseRow: null, attention: null, committed: null, inquiry: item, followUp: null },
  };

  it("reads the same words as the screen's own builder, with the server's identity and today's checks laid over", () => {
    const row = boardRow(server);
    const own = inquiryWorkRow(item);
    expect(row.title).toBe(own.title);
    expect(row.line).toBe(own.line);
    expect(row.state).toBe(own.state);
    expect(row.source).toBe(own.source);
    expect(row.dueToday).toBe(true);
    expect(row.also?.map((w) => w.checkId)).toEqual(["chk-1"]);
  });

  it("the check's way in opens the inquiry on that record", () => {
    expect(checkHref(server.also[0])).toBe("/inquiries/i-9?focus=seller-check&checkId=chk-1");
  });
});

/**
 * 2026-11-14: an approved answer whose registration the last check stopped comes back to 할 일 under 판단 필요. The row
 * says it stopped and that the seller moves it; it opens the inquiry, where the reasons and the decision are.
 */
describe("boardRow — a registration a hold stopped", () => {
  const followUp = {
    workItemId: "w-7", inquiryId: "i-7", subject: "합성 문의", channelCode: "NAVER", waitingOn: "REGISTRATION", due: false,
    outcome: null, outcomeKo: null, note: null, nextCheckOn: null, phase: "ACTION_PENDING", since: "2026-11-14T01:00:00Z",
    to: "/inquiries/i-7",
  } as const;
  const server: ServerWorkRow = {
    key: "held:i-7", owner: "/inquiries/i-7", to: "/inquiries/i-7", kind: "INQUIRY", subject: "INQUIRY", subjectId: "i-7",
    caseId: null, workItemId: "w-7", state: "REGISTRATION_HELD", delegatedState: null, bucket: "needsLook", past: false,
    praise: false, safety: false, safetyReading: null, safetyKind: null, channelCode: "NAVER", channelNameKo: null,
    rating: null, title: "합성 문의", context: null, since: "2026-11-14T01:00:00Z", dueToday: false, nextCheckOn: null,
    also: [], origin: { kind: "HELD", caseRow: null, attention: null, committed: null, inquiry: null, followUp: { ...followUp } },
  };

  it("leads with 등록 멈춤 and says the seller moves it — not a check, not a wait", () => {
    const row = boardRow(server);
    expect(row.state).toBe("REGISTRATION_HELD");
    expect(row.line).toContain("등록 직전 검사에서 멈췄어요");
    expect(row.line).toContain("고치거나 결정해 주세요");
    expect(row.verb).toBe("확인");
    expect(row.dueToday).toBeFalsy();
    expect(row.to).toBe("/inquiries/i-7");
    expect(row.workItemId).toBe("w-7");
  });
});

/** 2026-11-14 review: a draft the check before approval held is 판단 필요 on the server, and the row says why. */
describe("boardRow — a held draft is not offered as approvable", () => {
  const item = {
    workItemId: "w-5", inquiryId: "i-5", sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24", channelNameKo: "카페24",
    productId: null, productName: null, phase: "PROPOSED", status: "UNANSWERED", title: "합성 문의", snippet: "합성-본문",
    receivedAt: "2026-11-14T01:00:00Z", hasDraft: true,
  } as InquiryQueueItem;
  const server = (state: string, bucket: ServerWorkRow["bucket"]): ServerWorkRow => ({
    key: "inquiry:i-5", owner: "/inquiries/i-5", to: "/inquiries/i-5", kind: "INQUIRY", subject: "INQUIRY", subjectId: "i-5",
    caseId: null, workItemId: "w-5", state, delegatedState: null, bucket, past: false, praise: false, safety: false,
    safetyReading: null, safetyKind: null, channelCode: "CAFE24", channelNameKo: "카페24", rating: null, title: "합성 문의",
    context: null, since: "2026-11-14T01:00:00Z", dueToday: false, nextCheckOn: null, also: [],
    origin: { kind: "INQUIRY", caseRow: null, attention: null, committed: null, inquiry: item, followUp: null },
  });

  it("leads with 확정 보류 and the reason to open it, not 초안 준비됨", () => {
    const row = boardRow(server("DRAFT_HELD", "needsLook"));
    expect(row.state).toBe("DRAFT_HELD");
    expect(row.line).toBe(HELD_DRAFT_LINE);
    expect(row.to).toBe("/inquiries/i-5");
  });

  it("returns to the draft's own words once the hold is fixed or decided", () => {
    const row = boardRow(server("DRAFT_READY", "approvable"));
    expect(row.state).toBe("DRAFT_READY");
    expect(row.line).toBe(inquiryWorkRow(item).line);
  });
});
