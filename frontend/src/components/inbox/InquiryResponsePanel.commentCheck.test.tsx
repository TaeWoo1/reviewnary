// @vitest-environment jsdom
/**
 * <b>카페24 댓글 답변 확인 — 화면에서</b> (2026-11-08). 수집이 댓글을 확인하지 못했거나 기록이 없으면, 문의 상세와 승인 · 등록
 * 바로 앞에서 말한다. 확인 실패 · 기록 없음 · 과거 성공 뒤 최근 실패가 서로 다른 문장이고, 어떤 경우에도 버튼을 막지 않으며,
 * 실패를 「답변 없음」이나 판매자의 잘못으로 말하지 않는다. 모델은 돌지 않고, 문장은 가짜다.
 */
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InquiryResponsePanel } from "./InquiryResponsePanel";
import { InquiryReply, InquiryReplyDock } from "./InquiryReply";
import { useInquiryReply } from "./useInquiryReply";
import { checkTimeKo, commentCheckFact, commentCheckWarning } from "../../lib/commentCheck";
import type { InquiryDetail } from "../../lib/types";

const getInquiryDetailStrict = vi.fn();
const getInquiryPublishCapability = vi.fn();
const getSellerCheck = vi.fn();
const getReplyHold = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getInquiryDetailStrict: (id: string) => getInquiryDetailStrict(id),
    getInquiryPublishCapability: () => getInquiryPublishCapability(),
    saveInquiryReplyDraft: vi.fn(),
    confirmInquiryPublish: vi.fn(),
    verifyInquiryPublish: vi.fn(),
    resumeInquiryPublish: vi.fn(),
    generateInquiryProposal: vi.fn(),
    generateInquiryDraft: vi.fn(),
    getSellerCheck: (id: string) => getSellerCheck(id),
    recordSellerCheck: vi.fn(),
    redraftFromSellerCheck: vi.fn(),
    getReplyHold: (lane: string, id: string) => getReplyHold(lane, id),
    setSellerCheckNextCheck: vi.fn(),
    reopenFromSellerCheck: vi.fn(),
    correctInquiryKind: vi.fn(),
    withdrawHeldApproval: vi.fn(),
    closeToldBehind: vi.fn(),
  },
  getToken: () => null,
}));

const FRIDAY = "2026-11-06T02:10:00Z";
const MONDAY = "2026-11-02T01:05:00Z";

const DRAFT = {
  version: 1,
  answerStatus: 2,
  title: "[답변] 배송 문의",
  comments: "주문하신 상품은 내일 출고 예정입니다.",
  contentFingerprint: "a".repeat(64),
  fingerprintAlgorithm: "SHA-256",
  createdAt: "2026-11-06T03:00:00Z",
  authorKind: "MODEL",
  modelVersion: "test",
  knowledgeState: "GROUNDED",
  knowledgeNote: null,
};

function detail(over: Record<string, unknown> = {}) {
  return {
    workItemId: "w1",
    inquiryId: "i1",
    sellerAccountId: "s1",
    channelId: "c1",
    channelCode: "CAFE24",
    channelNameKo: "카페24 자사몰",
    isSecret: false,
    phase: "PROPOSED",
    status: "UNANSWERED",
    informStatus: null,
    title: "배송 문의",
    details: "언제 출고되나요?",
    receivedAt: "2026-11-05T00:00:00Z",
    proposal: null,
    draft: DRAFT,
    productId: null,
    productName: null,
    sourceSubtype: null,
    answerStateProven: true,
    answerStateNote: null,
    draftEvidence: [],
    ...over,
  };
}

const NOT_CHECKED = { state: "NOT_CHECKED", attemptedAt: FRIDAY, lastCheckedAt: null };
const AFTER_SUCCESS = { state: "NOT_CHECKED", attemptedAt: FRIDAY, lastCheckedAt: MONDAY };
const NO_RECORD = { state: "NO_RECORD", attemptedAt: null, lastCheckedAt: null };
const NO_COMMENT = { state: "NO_SHOP_COMMENT", attemptedAt: FRIDAY, lastCheckedAt: FRIDAY };

function Docked() {
  const workspace = useInquiryReply("w1");
  return (
    <>
      <InquiryReply workspace={workspace} docked />
      <div data-testid="dock">
        <InquiryReplyDock workspace={workspace} />
      </div>
    </>
  );
}

function openPanel() {
  render(
    <MemoryRouter>
      <InquiryResponsePanel workItemId="w1" />
    </MemoryRouter>,
  );
}

function openDocked() {
  render(
    <MemoryRouter>
      <Docked />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  getInquiryDetailStrict.mockResolvedValue(detail());
  getInquiryPublishCapability.mockResolvedValue({ executionEnabled: false, replyAdapterChannelCodes: [] });
  getSellerCheck.mockResolvedValue({ check: null, ask: null });
  getReplyHold.mockResolvedValue(null);
});

afterEach(() => vi.clearAllMocks());

describe("the rules (lib/commentCheck)", () => {
  const d = (commentCheck: unknown, over: Record<string, unknown> = {}) =>
    detail({ commentCheck, ...over }) as unknown as InquiryDetail;

  it("three different sentences for a failed look, no record, and a recent failure after a success", () => {
    const failed = commentCheckFact(d(NOT_CHECKED))!;
    const none = commentCheckFact(d(NO_RECORD))!;
    const recent = commentCheckFact(d(AFTER_SUCCESS))!;
    expect(new Set([failed.lead, none.lead, recent.lead]).size).toBe(3);
    expect([failed.kind, none.kind, recent.kind]).toEqual(["NOT_CHECKED", "NO_RECORD", "NOT_CHECKED_AFTER_SUCCESS"]);
    expect(failed.tone).toBe("warn");
    expect(recent.tone).toBe("warn");
    expect(none.tone).toBe("muted");
    // The failure's own time is never offered as a success time.
    expect(failed.when).toContain("확인에 성공한 기록은 아직 없어요");
    expect(failed.when).not.toContain("마지막으로 확인한 때");
    expect(recent.when).toContain(`마지막으로 확인한 때 ${checkTimeKo(MONDAY)}`);
    expect(recent.when).toContain(`확인하지 못한 수집 ${checkTimeKo(FRIDAY)}`);
  });

  it("the warning before the press: the last success time when there is one, a different sentence with no record", () => {
    expect(commentCheckWarning(d(NOT_CHECKED))).toBe(
      "댓글로 이미 답했는지 확인하지 못했어요. 판매자센터에서 등록 여부를 확인해 주세요.",
    );
    expect(commentCheckWarning(d(AFTER_SUCCESS))).toContain(`마지막으로 확인한 때는 ${checkTimeKo(MONDAY)}이에요.`);
    expect(commentCheckWarning(d(NO_RECORD))).toBe(
      "댓글로 이미 답했는지 확인한 기록이 없어요. 판매자센터에서 등록 여부를 확인해 주세요.",
    );
    expect(commentCheckWarning(d(NO_COMMENT))).toBeNull();
  });

  it("never says 「no answer」 or blames the seller for a failed look", () => {
    for (const v of [NOT_CHECKED, AFTER_SUCCESS, NO_RECORD]) {
      const words = `${commentCheckFact(d(v))!.lead} ${commentCheckFact(d(v))!.when ?? ""} ${commentCheckWarning(d(v))}`;
      expect(words).not.toMatch(/답변(이)? 없|답하지 않|미답변|잘못|실수/);
    }
  });

  it("nothing to warn about once the answer is known to be there, or where no collection looks", () => {
    expect(commentCheckWarning(d(NOT_CHECKED, { status: "ANSWERED" }))).toBeNull();
    expect(commentCheckFact(d(NOT_CHECKED, { status: "ANSWERED" }))).toBeNull();
    expect(commentCheckWarning(d(NO_RECORD, { phase: "COMPLETED" }))).toBeNull();
    expect(commentCheckFact(d(null))).toBeNull();
    expect(commentCheckWarning(d(undefined))).toBeNull();
    expect(commentCheckFact(d({ state: "SOMETHING_NEW", attemptedAt: FRIDAY, lastCheckedAt: null }))).toBeNull();
  });

  it("a proven shop comment says where the answer came from", () => {
    const fact = commentCheckFact(d({ state: "SHOP_ANSWERED", attemptedAt: FRIDAY, lastCheckedAt: FRIDAY }, { status: "ANSWERED" }))!;
    expect(fact.kind).toBe("SHOP_ANSWERED");
    expect(fact.lead).toBe("판매자 댓글로 답변한 것을 확인했어요.");
  });
});

describe("on the inquiry screen", () => {
  it("a failed look: the fact in the detail, the warning before the copy, and the copy is not blocked", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ commentCheck: NOT_CHECKED }));
    openPanel();
    const fact = await screen.findByTestId("comment-check-NOT_CHECKED");
    expect(fact).toHaveTextContent("댓글로 이미 답했는지 확인하지 못했어요.");
    expect(fact).toHaveClass("text-warn");
    expect(screen.getByTestId("comment-check-warning")).toHaveTextContent("판매자센터에서 등록 여부를 확인해 주세요.");
    for (const b of screen.getAllByRole("button", { name: /초안 복사/ })) expect(b).toBeEnabled();
  });

  it("no record reads differently from a failure, and quietly", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ commentCheck: NO_RECORD }));
    openPanel();
    const fact = await screen.findByTestId("comment-check-NO_RECORD");
    expect(fact).toHaveTextContent("확인한 기록이 아직 없어요");
    expect(fact).toHaveClass("text-muted");
    expect(screen.queryByTestId("comment-check-NOT_CHECKED")).toBeNull();
    expect(screen.getByTestId("comment-check-warning")).toHaveTextContent("확인한 기록이 없어요");
  });

  it("a recent failure after a success shows the last success time, in the detail and in the dock", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ commentCheck: AFTER_SUCCESS }));
    openDocked();
    const fact = await screen.findByTestId("comment-check-NOT_CHECKED_AFTER_SUCCESS");
    expect(fact).toHaveTextContent(`마지막으로 확인한 때 ${checkTimeKo(MONDAY)}`);
    const dock = screen.getByTestId("dock");
    expect(within(dock).getByTestId("comment-check-warning")).toHaveTextContent(
      `마지막으로 확인한 때는 ${checkTimeKo(MONDAY)}이에요.`,
    );
    expect(within(dock).getByRole("button", { name: /초안 복사/ })).toBeEnabled();
  });

  it("the second press of a registration carries the warning too, and stays pressable", async () => {
    getInquiryPublishCapability.mockResolvedValue({ executionEnabled: true, replyAdapterChannelCodes: ["CAFE24"] });
    getInquiryDetailStrict.mockResolvedValue(detail({ commentCheck: NOT_CHECKED }));
    openPanel();
    await userEvent.click(await screen.findByRole("button", { name: "답변 보내기" }));
    const confirm = screen.getByRole("group", { name: "답변 등록 확인" });
    expect(within(confirm).getByTestId("comment-check-warning")).toHaveTextContent("확인하지 못했어요");
    expect(within(confirm).getByRole("button", { name: "확인, 등록해요" })).toBeEnabled();
  });

  it("a successful look with no comment is a quiet fact and no warning", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ commentCheck: NO_COMMENT }));
    openPanel();
    expect(await screen.findByTestId("comment-check-NO_SHOP_COMMENT")).toHaveClass("text-muted");
    expect(screen.queryByTestId("comment-check-warning")).toBeNull();
  });

  it("another channel says nothing at all", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ channelCode: "NAVER", commentCheck: null }));
    openPanel();
    await screen.findByText(DRAFT.comments);
    expect(screen.queryByTestId(/comment-check/)).toBeNull();
  });
});
