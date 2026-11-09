// @vitest-environment jsdom
/**
 * <b>판매자센터에서 확인한 결과</b> — 화면에서 (2026-11-02 판매자 확인 리뷰 · 넷째 리뷰).
 *
 * 서비스가 할 수 없는 주문 일은 판매자가 확인해 한 줄로 적고, 그 기록으로 답변을 만든다. 여기서 확인하는 것:
 * 결과를 미리 고르지 않는다 · 빈 기록은 보내지 않는다 · 「기준을 추가하라」는 안내 대신 판매자센터 확인을 묻는다 ·
 * 저장과 답변 준비를 따로 말한다 · 다시 만들기는 기록을 다시 저장하지 않는다 · 다른 기기에서 먼저 고친 기록은 덮어쓰지 않는다 ·
 * 승인 뒤에는 등록이 확인되기 전까지 「등록 중」이라고 쓴다. 모델은 돌지 않고, 문장은 가짜다.
 */
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InquiryResponsePanel } from "./InquiryResponsePanel";
import { afterApprovalLine, dayLabel, nextCheckChoices, sellerToday, wasKo } from "./SellerCheckPanel";

const getInquiryDetailStrict = vi.fn();
const getInquiryPublishCapability = vi.fn();
const generateInquiryProposal = vi.fn();
const generateInquiryDraft = vi.fn();
const getSellerCheck = vi.fn();
const recordSellerCheck = vi.fn();
const redraftFromSellerCheck = vi.fn();
const getReplyHold = vi.fn();
const saveInquiryReplyDraft = vi.fn();
const setSellerCheckNextCheck = vi.fn();
const reopenFromSellerCheck = vi.fn();
const correctInquiryKind = vi.fn();
const withdrawHeldApproval = vi.fn();
const closeToldBehind = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getInquiryDetailStrict: (id: string) => getInquiryDetailStrict(id),
    getInquiryPublishCapability: () => getInquiryPublishCapability(),
    saveInquiryReplyDraft: (id: string, body: unknown) => saveInquiryReplyDraft(id, body),
    confirmInquiryPublish: vi.fn(),
    verifyInquiryPublish: vi.fn(),
    resumeInquiryPublish: vi.fn(),
    generateInquiryProposal: (id: string) => generateInquiryProposal(id),
    generateInquiryDraft: (id: string) => generateInquiryDraft(id),
    getSellerCheck: (id: string) => getSellerCheck(id),
    recordSellerCheck: (id: string, body: unknown) => recordSellerCheck(id, body),
    redraftFromSellerCheck: (id: string, checkId: string) => redraftFromSellerCheck(id, checkId),
    getReplyHold: (lane: string, id: string) => getReplyHold(lane, id),
    setSellerCheckNextCheck: (id: string, checkId: string, day: string) => setSellerCheckNextCheck(id, checkId, day),
    reopenFromSellerCheck: (id: string, body: unknown) => reopenFromSellerCheck(id, body),
    correctInquiryKind: (id: string, body: unknown) => correctInquiryKind(id, body),
    withdrawHeldApproval: (id: string, body: unknown) => withdrawHeldApproval(id, body),
    closeToldBehind: (id: string, body: unknown) => closeToldBehind(id, body),
  },
  getToken: () => null,
}));

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
    title: "환불 요청",
    details: "사이즈를 잘못 골랐어요. 환불해 주세요.",
    receivedAt: "2026-11-02T00:00:00Z",
    proposal: null,
    draft: null,
    productId: null,
    productName: null,
    sourceSubtype: null,
    answerStateProven: true,
    answerStateNote: null,
    draftEvidence: [],
    ...over,
  };
}

const RECORD = {
  id: "c1",
  outcome: "IN_PROGRESS",
  outcomeKo: "접수·진행 중",
  note: "반품 상품 도착 확인, 환불 대기",
  confirmedBy: "SELLER",
  recordedBy: "SELLER:u1",
  recordedAt: "2026-11-02T05:32:00Z",
};

const DRAFTED = {
  draft: {
    version: 2,
    answerStatus: 2,
    title: "[답변] 환불 요청",
    comments: "요청하신 환불은 접수되었고, 현재 반품 상품 도착 확인 후 환불 대기 상태입니다.",
    contentFingerprint: "b".repeat(64),
    fingerprintAlgorithm: "SHA-256",
    createdAt: "2026-11-02T05:33:00Z",
    authorKind: "MODEL",
    modelVersion: "test",
    knowledgeState: "GROUNDED",
    knowledgeNote: null,
  },
  authorKind: "MODEL",
  knowledgeState: "GROUNDED",
  knowledgeNote: null,
  answerBasis: "GROUNDED",
  answerBasisNote: "답변에 필요한 정보를 확인했어요.",
  answerBasisAction: null,
  productId: null,
  evidence: [
    {
      kind: "SELLER_CHECK",
      scopeLabel: "판매자 확인",
      title: "판매자 확인 · 접수·진행 중",
      locator: "seller-check/IN_PROGRESS",
      sourceId: "c1",
      chunkId: null,
      snippet: "판매자가 판매자센터에서 직접 확인한 이 문의의 결과: 접수·진행 중 — 반품 상품 도착 확인, 환불 대기",
      gone: false,
    },
  ],
  companyContextUsed: false,
  unavailableMessage: null,
  knowledgeGap: null,
  unavailableKind: null,
  openQuestions: [],
  sellerCheckAsk: null,
};

function open() {
  render(
    <MemoryRouter>
      <InquiryResponsePanel workItemId="w1" />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  getInquiryDetailStrict.mockResolvedValue(detail());
  getInquiryPublishCapability.mockResolvedValue({ executionEnabled: false, replyAdapterChannelCodes: [] });
  getSellerCheck.mockResolvedValue({ check: null, ask: "ORDER_ACTION" });
  getReplyHold.mockResolvedValue(null);
});

afterEach(() => vi.clearAllMocks());

/** Registered by SellerOps itself and read back — the registered text is our approved answer. */
const SENT_BY_US = { status: "VERIFIED", category: "COMPLETED" as const, verified: true, observedSignal: "ANSWERED" };

describe("판매자센터에서 확인한 결과", () => {
  it("asks for the seller centre — not for knowledge — and preselects nothing", async () => {
    const user = userEvent.setup();
    open();
    expect(await screen.findByTestId("seller-check-ask")).toHaveTextContent("이 주문에 요청된 조치를 확인해 주세요.");
    expect(screen.getByTestId("seller-check-ask")).toHaveTextContent("서비스 판단: 조치 요청");
    expect(screen.queryByRole("button", { name: "AI 답변 초안 만들기" })).toBeNull();
    expect(screen.queryByRole("button", { name: "답변 기준 추가" })).toBeNull();

    await user.click(screen.getByRole("button", { name: "확인한 결과 적기" }));
    for (const radio of screen.getAllByRole("radio")) {
      expect(radio).toHaveAttribute("aria-checked", "false");
    }
    expect(screen.queryByRole("textbox", { name: /어디까지|무엇을|이유|무엇을 받아야/ })).toBeNull();
  });

  it("a draft attempt that finds an order action open asks for the seller centre instead of 「답변 기준 추가」", async () => {
    // The server keeps what the draft attempt asked for, and the screen reads it again after the draft.
    getSellerCheck.mockResolvedValueOnce({ check: null, ask: null }).mockResolvedValue({ check: null, ask: "ORDER_ACTION" });
    generateInquiryDraft.mockResolvedValue({
      ...DRAFTED,
      draft: null,
      evidence: [],
      knowledgeState: "NO_MATCH",
      answerBasis: "NO_ANSWER_BASIS",
      answerBasisNote: "답변 기준이 필요해요.",
      answerBasisAction: "어떤 상품에 대한 문의인지 연결해 주세요.",
      sellerCheckAsk: "ORDER_ACTION",
    });
    const user = userEvent.setup();
    open();
    await user.click(await screen.findByRole("button", { name: /초안 만들기/ }));
    expect(await screen.findByTestId("seller-check-ask")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "답변 기준 추가" })).toBeNull();
    expect(screen.queryByText("어떤 상품에 대한 문의인지 연결해 주세요.")).toBeNull();
  });

  it("a blank record is not sent", async () => {
    const user = userEvent.setup();
    open();
    await user.click(await screen.findByRole("button", { name: "확인한 결과 적기" }));
    await user.click(screen.getByRole("button", { name: "기록하고 답변 만들기" }));
    expect(screen.getByTestId("seller-check-error")).toHaveTextContent("확인한 결과를 하나 골라 주세요.");
    await user.click(screen.getByRole("radio", { name: "접수·진행 중" }));
    await user.type(screen.getByRole("textbox", { name: "지금 어디까지 됐나요?" }), "   ");
    await user.click(screen.getByRole("button", { name: "기록하고 답변 만들기" }));
    expect(screen.getByTestId("seller-check-error")).toHaveTextContent("비워 두면 답변을 만들지 않아요");
    expect(recordSellerCheck).not.toHaveBeenCalled();
  });

  it("recorded and drafted: the record stands above the draft, which cites it", async () => {
    recordSellerCheck.mockResolvedValue({ check: RECORD, draft: DRAFTED, draftState: "READY", hold: null });
    const user = userEvent.setup();
    open();
    await user.click(await screen.findByRole("button", { name: "확인한 결과 적기" }));
    await user.click(screen.getByRole("radio", { name: "접수·진행 중" }));
    await user.type(screen.getByRole("textbox", { name: "지금 어디까지 됐나요?" }), "반품 상품 도착 확인, 환불 대기");
    await user.click(screen.getByRole("button", { name: "기록하고 답변 만들기" }));

    expect(recordSellerCheck).toHaveBeenCalledWith("w1", {
      outcome: "IN_PROGRESS",
      note: "반품 상품 도착 확인, 환불 대기",
      replaces: null,
    });
    expect(await screen.findByTestId("seller-check-record")).toHaveTextContent("접수·진행 중");
    expect(screen.getByTestId("seller-check-record")).toHaveTextContent("이 문의에만 사용");
    expect(screen.getByText(DRAFTED.draft.comments)).toBeInTheDocument();
    expect(screen.queryByTestId("seller-check-not-drafted")).toBeNull();
  });

  it("saved but not drafted says so, and the retry drafts from the record shown without recording again", async () => {
    recordSellerCheck.mockResolvedValue({ check: RECORD, draft: null, draftState: "FAILED", hold: null });
    redraftFromSellerCheck.mockResolvedValue({ check: RECORD, draft: DRAFTED, draftState: "READY", hold: null });
    const user = userEvent.setup();
    open();
    await user.click(await screen.findByRole("button", { name: "확인한 결과 적기" }));
    await user.click(screen.getByRole("radio", { name: "접수·진행 중" }));
    await user.type(screen.getByRole("textbox", { name: "지금 어디까지 됐나요?" }), "반품 상품 도착 확인, 환불 대기");
    await user.click(screen.getByRole("button", { name: "기록하고 답변 만들기" }));

    expect(await screen.findByTestId("seller-check-not-drafted")).toHaveTextContent(
      "확인한 내용은 저장했어요. 답변은 아직 준비하지 못했어요.",
    );
    await user.click(screen.getByRole("button", { name: "이 기록으로 답변 다시 만들기" }));
    await waitFor(() => expect(redraftFromSellerCheck).toHaveBeenCalledWith("w1", "c1"));
    expect(recordSellerCheck).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(DRAFTED.draft.comments)).toBeInTheDocument();
    expect(screen.queryByTestId("seller-check-not-drafted")).toBeNull();
  });

  it("a record corrected on another device is not overwritten: the refusal is shown and the record read again", async () => {
    getSellerCheck
      .mockResolvedValueOnce({ check: RECORD, ask: null })
      .mockResolvedValueOnce({ check: { ...RECORD, id: "c2", outcome: "DONE", outcomeKo: "처리 완료", note: "환불 처리" }, ask: null });
    recordSellerCheck.mockRejectedValue(
      Object.assign(new Error("conflict"), {
        isAxiosError: true,
        response: { status: 409, data: { message: "다른 곳에서 확인한 결과가 먼저 저장됐어요." } },
      }),
    );
    const user = userEvent.setup();
    open();
    await user.click(await screen.findByRole("button", { name: "결과 고치기" }));
    await user.click(screen.getByRole("radio", { name: "처리 불가" }));
    await user.type(screen.getByRole("textbox", { name: "이유를 적어 주세요" }), "사용 흔적");
    await user.click(screen.getByRole("button", { name: "기록하고 답변 만들기" }));

    expect(recordSellerCheck).toHaveBeenCalledWith("w1", { outcome: "CANNOT", note: "사용 흔적", replaces: "c1" });
    expect(await screen.findByTestId("seller-check-error")).toHaveTextContent("먼저 저장됐어요");
    await waitFor(() => expect(getSellerCheck).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("환불 처리")).toBeInTheDocument();
  });

  it("after a reload, a record with no answer written from it still offers the way back", async () => {
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: false });
    redraftFromSellerCheck.mockResolvedValue({ check: RECORD, draft: DRAFTED, draftState: "READY", hold: null });
    const user = userEvent.setup();
    open();
    expect(await screen.findByTestId("seller-check-not-drafted")).toHaveTextContent("이 기록으로 만든 답변이 아직 없어요.");
    // One way forward, not two: the generic draft button would do the same thing.
    expect(screen.queryByRole("button", { name: "AI 답변 초안 만들기" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "이 기록으로 답변 만들기" }));
    await waitFor(() => expect(redraftFromSellerCheck).toHaveBeenCalledWith("w1", "c1"));
    expect(recordSellerCheck).not.toHaveBeenCalled();
    expect(await screen.findByText(DRAFTED.draft.comments)).toBeInTheDocument();
    expect(screen.queryByTestId("seller-check-not-drafted")).toBeNull();
  });

  it("a record that could not be read is not drawn as 「no record」: it says so and reads again", async () => {
    getSellerCheck.mockRejectedValueOnce(new Error("network")).mockResolvedValueOnce({ check: RECORD, ask: null, draftMatches: true });
    const user = userEvent.setup();
    open();
    expect(await screen.findByTestId("seller-check-load-failed")).toHaveTextContent("불러오지 못했어요");
    expect(screen.queryByRole("button", { name: "확인한 결과 적기" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByTestId("seller-check-record")).toHaveTextContent("접수·진행 중");
    expect(screen.queryByTestId("seller-check-not-drafted")).toBeNull();
  });

  it("a seller's own edit is saved naming the record and the kind correction on screen", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({
      check: RECORD,
      ask: null,
      draftMatches: true,
      kind: { judged: "ORDER_ACTION", judgedKo: "조치 요청", corrected: "ORDER_STATE", correctedKo: "상태 확인", correctionId: "k7", draftMatches: true },
    });
    saveInquiryReplyDraft.mockResolvedValue({ ...DRAFTED.draft, version: 3, authorKind: "SELLER", comments: "고친 답변" });
    const user = userEvent.setup();
    open();
    await screen.findByTestId("seller-check-record");
    await user.click(await screen.findByRole("button", { name: /^고치기$|직접 고치기|수정/ }));
    const box = screen.getByRole("textbox", { name: /내용|답변/ });
    await user.clear(box);
    await user.type(box, "고친 답변");
    await user.click(screen.getByRole("button", { name: /저장/ }));
    await waitFor(() => expect(saveInquiryReplyDraft).toHaveBeenCalled());
    expect(saveInquiryReplyDraft.mock.calls[0][1]).toMatchObject({ sellerCheckId: "c1", kindCorrectionId: "k7" });
  });

  it("a held answer cannot be copied out to the seller centre either", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: false });
    getReplyHold.mockResolvedValue({
      lane: "INQUIRY", subjectId: "w1", draftVersion: 2, approved: false, codes: ["SELLER_CHECK_CHANGED"], needs: "FIX",
      confirmed: false, headline: "판매자 확인 기록이 바뀌어 답변을 다시 확인해야 해요.", reasons: [], changed: [], sentences: [],
      figures: [], checkedAt: "2026-11-03T00:00:00Z", checkId: "k", unbacked: [], conflicts: [],
    });
    open();
    expect(await screen.findByText("판매자 확인 기록이 바뀌어 답변을 다시 확인해야 해요.")).toBeInTheDocument();
    for (const b of screen.getAllByRole("button", { name: "초안 복사" })) {
      expect(b).toBeDisabled();
    }
  });

  it("after approval: 「등록 중」 until the channel shows it, 「안내했어요」 only after", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING" }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null });
    open();
    expect(await screen.findByTestId("seller-check-after")).toHaveTextContent(
      "진행 상황을 안내하는 답변을 등록 중이에요",
    );
    expect(afterApprovalLine("IN_PROGRESS", true)).toContain("진행 상황을 안내했어요");
    expect(afterApprovalLine("IN_PROGRESS", false)).not.toContain("안내했어요");
  });

  it("after approval, an action in progress says when the seller looks again, and moving the day writes no record", async () => {
    const today = sellerToday();
    const set = nextCheckChoices(today, null)[1].day; // two days on — what the server starts with
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "COMPLETED", status: "ANSWERED" }));
    getSellerCheck.mockResolvedValue({ check: { ...RECORD, nextCheckOn: set }, ask: null, draftMatches: true });
    const week = nextCheckChoices(today, set)[3].day;
    setSellerCheckNextCheck.mockResolvedValue({ ...RECORD, nextCheckOn: week });
    open();
    const box = await screen.findByTestId("seller-check-next");
    expect(box).toHaveTextContent(`${dayLabel(set)}에 이 문의가 다시 할 일에 올라와요`);
    expect(box).toHaveTextContent("고객에게는 날짜를 약속하지 않아요");
    const pick = screen.getByRole("button", { name: "이 날로 정하기" });
    expect(pick).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText("다시 확인할 날"), week);
    await userEvent.click(pick);
    expect(setSellerCheckNextCheck).toHaveBeenCalledWith("w1", "c1", week);
    expect(recordSellerCheck).not.toHaveBeenCalled();
    expect(generateInquiryDraft).not.toHaveBeenCalled();
    expect(await screen.findByTestId("seller-check-next")).toHaveTextContent(`${dayLabel(week)}에 이 문의가`);
  });

  it("after approval the record can still be corrected, and the approved answer is kept", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING" }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: true });
    recordSellerCheck.mockResolvedValue({
      check: { ...RECORD, id: "c2", outcome: "DONE", outcomeKo: "처리 완료", note: "카드 결제 취소로 환불 처리", nextCheckOn: null },
      draft: null,
      draftState: "KEPT",
      hold: null,
    });
    open();
    await screen.findByTestId("seller-check-after");
    expect(screen.getByText(/결과가 바뀌면 그 답변은 등록하지 않아요/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "결과 고치기" }));
    await userEvent.click(screen.getByRole("radio", { name: "처리 완료" }));
    await userEvent.type(screen.getByLabelText("무엇을 했나요?"), "카드 결제 취소로 환불 처리");
    await userEvent.click(screen.getByRole("button", { name: "기록 고치기" }));
    expect(recordSellerCheck).toHaveBeenCalledWith("w1", expect.objectContaining({ outcome: "DONE", replaces: "c1" }));
    expect(await screen.findByTestId("seller-check-kept")).toHaveTextContent("이미 승인한 답변은 그대로 두었어요");
    expect(generateInquiryDraft).not.toHaveBeenCalled();
  });

  it("a registered answer reopens as it was: 「등록한 답변」, no edit, no copy, no new draft", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "COMPLETED", status: "ANSWERED", draft: DRAFTED.draft, delivery: SENT_BY_US }));
    getSellerCheck.mockResolvedValue({ check: { ...RECORD, nextCheckOn: "2026-11-05" }, ask: null, draftMatches: true });
    open();
    expect(await screen.findByText("등록한 답변")).toBeInTheDocument();
    expect(screen.queryByText("준비된 답변")).toBeNull();
    expect(screen.queryByRole("button", { name: "수정" })).toBeNull();
    expect(screen.queryByRole("button", { name: /초안 복사/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "AI 답변 초안 만들기" })).toBeNull();
  });

  /** Answered 「진행 중」 with version 2, then the record moved to 「처리 완료」 (2026-11-05 seventh review). */
  const DONE_NOW = { ...RECORD, id: "c2", outcome: "DONE", outcomeKo: "처리 완료", note: "카드 결제 취소로 환불 처리", nextCheckOn: null };
  const SENT_IN_PROGRESS = {
    version: 2,
    fingerprint: "b".repeat(64),
    outcome: "IN_PROGRESS",
    outcomeKo: "접수·진행 중",
    recordedAt: RECORD.recordedAt,
    current: false,
    reopen: "OFFERED",
    channelReadAt: "2026-11-04T01:00:00Z",
  };

  it("answered 「진행 중」 and recorded 「완료」 after: never 「처리 완료를 안내했어요」 — the action now and what the customer was told apart", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "COMPLETED", status: "ANSWERED", draft: DRAFTED.draft, delivery: SENT_BY_US }));
    getSellerCheck.mockResolvedValue({
      check: DONE_NOW,
      ask: null,
      draftMatches: false,
      answered: { ...SENT_IN_PROGRESS, reopen: "REGISTERED" },
    });
    open();
    const apart = await screen.findByTestId("seller-check-apart");
    expect(apart).toHaveTextContent("지금 처리 결과와 고객에게 보낸 답변이 달라요");
    expect(screen.getByTestId("seller-check-apart-now")).toHaveTextContent("처리 완료");
    expect(screen.getByTestId("seller-check-apart-sent")).toHaveTextContent("진행 상황 안내");
    expect(screen.getByTestId("seller-check-apart-registered")).toHaveTextContent("고객에게는 아직 처리 완료를 안내하지 않았어요");
    expect(screen.queryByText(/처리 완료를 안내했어요/)).toBeNull();
    expect(screen.queryByTestId("seller-check-after")).toBeNull();
    expect(screen.queryByRole("button", { name: /승인 거두고/ })).toBeNull();
  });

  it("an approved answer the record outran, not on the channel: taken back only after the seller says they did not post it, then a new answer waits for approval", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING", draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({ check: DONE_NOW, ask: null, draftMatches: false, answered: SENT_IN_PROGRESS });
    open();
    const box = await screen.findByTestId("seller-check-reopen");
    expect(screen.getByTestId("seller-check-apart")).toHaveTextContent("지금 처리 결과와 승인한 답변이 달라요");
    expect(box).toHaveTextContent("승인한 뒤 11월 4일");
    expect(box).toHaveTextContent("등록된 답변이 없었어요");
    const go = screen.getByRole("button", { name: "승인 거두고 이 기록으로 새 답변 만들기" });
    expect(go).toBeDisabled();
    await userEvent.click(go);
    expect(reopenFromSellerCheck).not.toHaveBeenCalled();

    const v3 = { ...DRAFTED.draft, version: 3, comments: "요청하신 환불은 카드 결제 취소로 처리되었습니다.", contentFingerprint: "c".repeat(64) };
    reopenFromSellerCheck.mockResolvedValue({ check: DONE_NOW, draft: { ...DRAFTED, draft: v3 }, draftState: "READY", hold: null });
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "PROPOSED", draft: v3 }));
    await userEvent.click(screen.getByTestId("seller-check-reopen-sure"));
    expect(go).toBeEnabled();
    await userEvent.click(go);

    expect(reopenFromSellerCheck).toHaveBeenCalledWith("w1", {
      checkId: "c2",
      approvedFingerprint: "b".repeat(64),
      notRegistered: true,
    });
    expect(await screen.findByTestId("seller-check-reopened")).toHaveTextContent("다시 승인해 주세요");
    expect(screen.queryByTestId("seller-check-apart")).toBeNull();
    expect(await screen.findByText(/카드 결제 취소로 처리되었습니다/)).toBeInTheDocument();
    expect(recordSellerCheck).not.toHaveBeenCalled();
  });

  it("a send whose result is not read yet is not taken back", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING", draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({
      check: DONE_NOW,
      ask: null,
      draftMatches: false,
      answered: { ...SENT_IN_PROGRESS, reopen: "UNSETTLED" },
    });
    open();
    expect(await screen.findByTestId("seller-check-apart-unsettled")).toHaveTextContent("등록 상태를 먼저 확인해 주세요");
    expect(screen.queryByRole("button", { name: /승인 거두고/ })).toBeNull();
  });

  it("the after-approval line follows the record the answer was written from, as the server says", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "COMPLETED", status: "ANSWERED", draft: DRAFTED.draft, delivery: SENT_BY_US }));
    getSellerCheck.mockResolvedValue({
      check: RECORD,
      ask: null,
      draftMatches: true,
      answered: { ...SENT_IN_PROGRESS, current: true, reopen: "MATCHES" },
    });
    open();
    expect(await screen.findByTestId("seller-check-after")).toHaveTextContent("진행 상황을 안내했어요");
    expect(screen.queryByTestId("seller-check-apart")).toBeNull();
  });

  const JUDGED_ACTION = { judged: "ORDER_ACTION", judgedKo: "조치 요청", corrected: null, correctedKo: null, correctionId: null };

  it("the seller corrects the service's reading: 방법 안내 drafts again from information, and both readings stay on screen", async () => {
    getSellerCheck.mockResolvedValue({ check: null, ask: "ORDER_ACTION", kind: JUDGED_ACTION });
    correctInquiryKind.mockResolvedValue({
      kind: { ...JUDGED_ACTION, corrected: "HOW_TO", correctedKo: "방법 안내", correctionId: "k1" },
      ask: null,
      draft: DRAFTED,
      draftState: "READY",
      hold: null,
    });
    open();
    expect(await screen.findByTestId("kind-line")).toHaveTextContent("서비스 판단: 조치 요청");
    await userEvent.click(screen.getByRole("button", { name: "판단 고치기" }));
    const press = screen.getByRole("button", { name: "이 판단으로 답변 다시 만들기" });
    expect(press).toBeDisabled();
    expect(screen.getByRole("radio", { name: "조치 요청" })).toHaveAttribute("aria-checked", "true");
    await userEvent.click(screen.getByRole("radio", { name: "방법 안내" }));
    expect(screen.getByTestId("kind-form")).toHaveTextContent("등록된 정책·상품 정보로만 답해요");
    await userEvent.click(press);
    expect(correctInquiryKind).toHaveBeenCalledWith("w1", { kind: "HOW_TO", replaces: null });
    const line = await screen.findByTestId("kind-line");
    expect(line).toHaveTextContent("판매자 판단: 방법 안내");
    expect(line).toHaveTextContent("서비스 판단은 조치 요청이었어요");
    expect(screen.queryByText("이 주문에 요청된 조치를 확인해 주세요.")).toBeNull();
    expect(await screen.findByText(/현재 반품 상품 도착 확인 후 환불 대기 상태입니다/)).toBeInTheDocument();
    expect(recordSellerCheck).not.toHaveBeenCalled();
  });

  it("a later correction names the standing one, and 방법 안내 can be corrected the other way with nothing asked", async () => {
    getSellerCheck.mockResolvedValue({
      check: null,
      ask: null,
      kind: { ...JUDGED_ACTION, corrected: "HOW_TO", correctedKo: "방법 안내", correctionId: "k1" },
    });
    correctInquiryKind.mockResolvedValue({
      kind: { ...JUDGED_ACTION, corrected: "ORDER_STATE", correctedKo: "상태 확인", correctionId: "k2" },
      ask: "ORDER_STATE",
      draft: { ...DRAFTED, draft: null, unavailableMessage: null, sellerCheckAsk: "ORDER_STATE" },
      draftState: "NOT_WRITTEN",
      hold: null,
    });
    open();
    expect(await screen.findByTestId("seller-check-kind-only")).toHaveTextContent("판매자 판단: 방법 안내");
    await userEvent.click(screen.getByRole("button", { name: "판단 고치기" }));
    await userEvent.click(screen.getByRole("radio", { name: "상태 확인" }));
    await userEvent.click(screen.getByRole("button", { name: "이 판단으로 답변 다시 만들기" }));
    expect(correctInquiryKind).toHaveBeenCalledWith("w1", { kind: "ORDER_STATE", replaces: "k1" });
    expect(await screen.findByText("이 주문이 지금 어떤 상태인지 확인해 주세요.")).toBeInTheDocument();
  });

  it("an approved answer's reading is not corrected from here", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING", draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: true, kind: JUDGED_ACTION });
    open();
    await screen.findByTestId("seller-check-after");
    expect(screen.queryByRole("button", { name: "판단 고치기" })).toBeNull();
  });

  it("a correction another device made first is refused on screen, and the reading line follows a plain draft", async () => {
    getSellerCheck.mockResolvedValueOnce({ check: null, ask: null }).mockResolvedValue({
      check: null,
      ask: null,
      kind: { judged: "HOW_TO", judgedKo: "방법 안내", corrected: null, correctedKo: null, correctionId: null },
    });
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "PROPOSED" }));
    generateInquiryDraft.mockResolvedValue(DRAFTED);
    correctInquiryKind.mockRejectedValue(
      Object.assign(new Error("409"), {
        isAxiosError: true,
        response: { status: 409, data: { message: "다른 곳에서 판단을 먼저 고쳤어요. 새로 불러온 판단을 확인한 뒤 다시 골라 주세요." } },
      }),
    );
    open();
    await userEvent.click(await screen.findByRole("button", { name: "AI 답변 초안 만들기" }));
    expect(await screen.findByTestId("seller-check-kind-only")).toHaveTextContent("서비스 판단: 방법 안내");
    await userEvent.click(screen.getByRole("button", { name: "판단 고치기" }));
    await userEvent.click(screen.getByRole("radio", { name: "조치 요청" }));
    await userEvent.click(screen.getByRole("button", { name: "이 판단으로 답변 다시 만들기" }));
    expect(await screen.findByTestId("kind-error")).toHaveTextContent("다른 곳에서 판단을 먼저 고쳤어요");
    expect(wasKo("방법 안내")).toBe("방법 안내였어요");
    expect(wasKo("조치 요청")).toBe("조치 요청이었어요");
  });

  it("an answer written before the reading was corrected is not copied out, and is written again under it", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "PROPOSED", draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({
      check: null,
      ask: null,
      kind: { ...JUDGED_ACTION, corrected: "HOW_TO", correctedKo: "방법 안내", correctionId: "k1", draftMatches: false },
    });
    generateInquiryDraft.mockResolvedValue(DRAFTED);
    open();
    expect(await screen.findByTestId("kind-stale")).toHaveTextContent("판단을 고치기 전에 만든 것이라");
    for (const b of screen.getAllByRole("button", { name: /초안 복사/ })) expect(b).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "이 판단으로 답변 만들기" }));
    expect(generateInquiryDraft).toHaveBeenCalledWith("w1");
  });

  it("with the seller centre asked, the record writes the new answer — no plain redraft is offered", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "PROPOSED", draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({
      check: null,
      ask: "ORDER_STATE",
      kind: { ...JUDGED_ACTION, corrected: "ORDER_STATE", correctedKo: "상태 확인", correctionId: "k1", draftMatches: false },
    });
    open();
    expect(await screen.findByTestId("kind-stale")).toHaveTextContent("확인한 결과를 적으면 이 판단으로 답변을 만들어요");
    expect(screen.queryByRole("button", { name: "이 판단으로 답변 만들기" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "판단 고치기" }));
    expect(screen.queryByRole("button", { name: "확인한 결과 적기" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByRole("button", { name: "확인한 결과 적기" })).toBeInTheDocument();
  });

  it("nothing sent but the channel not read since the approval: 「not known」, and no way to take it back yet", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING", draft: DRAFTED.draft }));
    getSellerCheck.mockResolvedValue({
      check: DONE_NOW,
      ask: null,
      draftMatches: false,
      answered: { ...SENT_IN_PROGRESS, reopen: "UNCONFIRMED", channelReadAt: null },
    });
    open();
    expect(await screen.findByTestId("seller-check-apart-unconfirmed")).toHaveTextContent("등록됐는지 아직 몰라요");
    expect(screen.getByTestId("seller-check-apart-unconfirmed")).toHaveTextContent("확인 기록 없음");
    expect(screen.queryByRole("button", { name: /승인 거두고/ })).toBeNull();
  });

  /** Tenth review (2026-11-05): an approved answer held for a fix — a policy conflict — and not on the channel. */
  const POLICY_HOLD = {
    lane: "INQUIRY", subjectId: "w1", draftVersion: 2, approved: true, codes: ["POLICY_CONFLICT"], needs: "FIX",
    confirmed: false, headline: "등록된 정책과 맞지 않는 내용이 있어요.", reasons: ["반품 기간"], changed: [], sentences: [],
    figures: [], checkedAt: "2026-11-05T00:00:00Z", checkId: "hold-1", unbacked: [], conflicts: ["반품 기간"],
  };
  const HELD = {
    ...SENT_IN_PROGRESS, outcome: null, outcomeKo: null, recordedAt: null, because: "HOLD", holdCheckId: "hold-1",
    holdCodes: ["POLICY_CONFLICT"],
  };

  it("an approved answer held for a policy conflict is taken back to be fixed only after the seller says they did not post it", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING", draft: DRAFTED.draft }));
    getReplyHold.mockResolvedValue(POLICY_HOLD);
    getSellerCheck.mockResolvedValue({ check: null, ask: null, draftMatches: null, answered: HELD });
    open();
    const box = await screen.findByTestId("held-take-back");
    expect(box).toHaveTextContent("같은 검사를 다시 받고");
    expect(box).toHaveTextContent("승인한 뒤 11월 4일");
    const go = screen.getByRole("button", { name: "승인 거두고 고치기" });
    expect(go).toBeDisabled();
    expect(screen.queryByTestId("seller-check-reopen")).toBeNull();

    withdrawHeldApproval.mockResolvedValue({ phase: "PROPOSED", hold: { ...POLICY_HOLD, approved: false } });
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "PROPOSED", draft: DRAFTED.draft }));
    await userEvent.click(screen.getByTestId("held-take-back-sure"));
    await userEvent.click(go);
    expect(withdrawHeldApproval).toHaveBeenCalledWith("w1", {
      approvedFingerprint: "b".repeat(64),
      holdCheckId: "hold-1",
      notRegistered: true,
    });
    await waitFor(() => expect(screen.queryByTestId("held-take-back")).toBeNull());
    expect(screen.getByText("등록된 정책과 맞지 않는 내용이 있어요.")).toBeInTheDocument();
    expect(recordSellerCheck).not.toHaveBeenCalled();
    expect(generateInquiryDraft).not.toHaveBeenCalled();
  });

  it("an answer held for a fix with the channel not read since: no way back yet, and the record's take-back box never shows for it", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "ACTION_PENDING", draft: DRAFTED.draft }));
    getReplyHold.mockResolvedValue(POLICY_HOLD);
    getSellerCheck.mockResolvedValue({
      check: { ...RECORD, id: "c9" }, ask: null, draftMatches: true,
      answered: { ...HELD, current: true, reopen: "UNCONFIRMED", channelReadAt: null },
    });
    open();
    expect(await screen.findByTestId("held-take-back-unconfirmed")).toHaveTextContent("확인 기록 없음");
    expect(screen.queryByRole("button", { name: /승인 거두고/ })).toBeNull();
    expect(screen.queryByTestId("seller-check-reopen")).toBeNull();
  });

  it("registered 「진행 중」, recorded 「완료」 after: closed with 「따로 안내함」 for that record, and nothing is sent", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({ phase: "COMPLETED", status: "ANSWERED", draft: DRAFTED.draft, delivery: SENT_BY_US }));
    getSellerCheck.mockResolvedValue({
      check: DONE_NOW, ask: null, draftMatches: false,
      answered: { ...SENT_IN_PROGRESS, reopen: "REGISTERED", because: "RECORD", toldBehind: true, closure: null },
    });
    closeToldBehind.mockResolvedValue({ checkId: "c2", closure: "TOLD_SEPARATELY", closedAt: "2026-11-05T03:00:00Z" });
    open();
    expect(await screen.findByTestId("told-behind-close")).toHaveTextContent("아무것도 보내지 않아요");
    await userEvent.click(screen.getByRole("button", { name: "따로 안내함" }));
    expect(closeToldBehind).toHaveBeenCalledWith("w1", { checkId: "c2", closure: "TOLD_SEPARATELY" });
    expect(await screen.findByTestId("told-behind-closed")).toHaveTextContent("따로 안내했다고 표시했어요");
    expect(screen.queryByTestId("told-behind-close")).toBeNull();
    expect(recordSellerCheck).not.toHaveBeenCalled();
  });

  /**
   * What the screen confirmed (2026-11-06 sixteenth review): the answer the channel shows leads, our draft is kept folded
   * beside it, and 「…안내했어요」 is said only when the registered text is known to be ours. Synthetic text.
   */
  const COPIED = { phase: "COMPLETED", status: "ANSWERED", draft: DRAFTED.draft };
  const CHECKED_AT = "2026-11-06T02:10:00Z";

  it("the channel's text, the same as our draft: it leads, the draft folds, and what was told can be said", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({
      ...COPIED,
      channelAnswer: { body: DRAFTED.draft.comments, answeredAt: "2026-11-06T02:00:00Z", bodyCheckedAt: CHECKED_AT, statusCheckedAt: CHECKED_AT, latest: true, sameAsDraft: true },
    }));
    getSellerCheck.mockResolvedValue({ check: { ...RECORD, nextCheckOn: "2026-11-09" }, ask: null, draftMatches: true });
    open();
    const answer = await screen.findByTestId("channel-answer");
    expect(answer).toHaveTextContent("채널에서 확인한 답변");
    expect(answer).toHaveTextContent(DRAFTED.draft.comments);
    expect(screen.getByTestId("channel-answer-checked")).toHaveTextContent("확인");
    expect(screen.getByTestId("channel-answer-same")).toHaveTextContent("준비한 초안과 같은 내용이에요");
    expect(screen.getByTestId("draft-aside")).toHaveTextContent("Reviewnary에서 준비한 초안");
    expect(screen.queryByText("등록한 답변")).toBeNull();
    expect(screen.getByTestId("seller-check-after")).toHaveTextContent("진행 상황을 안내했어요");
  });

  it("the channel's text differs from our draft: shown as it is, never replaced, nothing sent again, nothing said of what was told", async () => {
    const theirs = "환불은 반품 확인 뒤 3일 안에 처리됩니다.";
    getInquiryDetailStrict.mockResolvedValue(detail({
      ...COPIED,
      channelAnswer: { body: theirs, answeredAt: "2026-11-06T02:00:00Z", bodyCheckedAt: CHECKED_AT, statusCheckedAt: CHECKED_AT, latest: true, sameAsDraft: false },
    }));
    getSellerCheck.mockResolvedValue({ check: { ...RECORD, nextCheckOn: "2026-11-09" }, ask: null, draftMatches: true });
    open();
    const answer = await screen.findByTestId("channel-answer");
    expect(answer).toHaveTextContent(theirs);
    expect(answer).not.toHaveTextContent(DRAFTED.draft.comments);
    expect(screen.getByTestId("channel-answer-differs")).toHaveTextContent("내용이 달라요");
    expect(screen.getByTestId("draft-aside")).toHaveTextContent(DRAFTED.draft.comments);
    expect(screen.queryByText("등록한 답변")).toBeNull();
    const after = screen.getByTestId("seller-check-after");
    expect(after).toHaveTextContent("채널에 답변이 등록된 것을 확인했어요");
    expect(screen.getByTestId("seller-check-after-record")).toHaveTextContent("판매자 확인 기록: 접수·진행 중");
    expect(after).not.toHaveTextContent("안내했어요");
    expect(screen.queryByRole("button", { name: "답변 보내기" })).toBeNull();
    expect(screen.queryByRole("button", { name: /초안 복사/ })).toBeNull();
    // The next look is the record's, and stays.
    expect(screen.getByText(/다시 할 일에 올라와요/)).toBeInTheDocument();
  });

  it("only the registration is confirmed: it says so, and that the text was not read", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail(COPIED));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: true });
    open();
    expect(await screen.findByTestId("channel-answer-status-only")).toHaveTextContent(
      "답변 등록은 확인했어요. 본문은 확인하지 못했어요.",
    );
    expect(screen.queryByTestId("channel-answer")).toBeNull();
    expect(screen.getByTestId("draft-aside")).toHaveTextContent("Reviewnary에서 준비한 초안");
    expect(screen.queryByText("등록한 답변")).toBeNull();
    expect(screen.getByTestId("seller-check-after")).toHaveTextContent("채널에 답변이 등록된 것을 확인했어요");
    expect(screen.getByTestId("seller-check-after")).not.toHaveTextContent("안내했어요");
  });

  it("our own send, then the channel shows other text: the channel's text is what the customer sees", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({
      ...COPIED,
      delivery: SENT_BY_US,
      channelAnswer: { body: "판매자센터에서 고친 답변", answeredAt: null, bodyCheckedAt: CHECKED_AT, statusCheckedAt: CHECKED_AT, latest: true, sameAsDraft: false },
    }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: true });
    open();
    expect(await screen.findByTestId("channel-answer")).toHaveTextContent("판매자센터에서 고친 답변");
    expect(screen.getByTestId("seller-check-after")).not.toHaveTextContent("안내했어요");
  });

  /**
   * The text and the status are read at different times (2026-11-06 seventeenth review): a text read on Monday and a status
   * read on Friday is the last answer collected, with both dates, never 「금요일 확인」 on Monday's text.
   */
  const MONDAY = "2026-11-02T02:00:00Z";
  const FRIDAY = "2026-11-06T02:00:00Z";

  it("a text the last read did not carry is the last answer collected, with its own read time beside the status's", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({
      ...COPIED,
      channelAnswer: {
        body: DRAFTED.draft.comments, answeredAt: MONDAY, bodyCheckedAt: MONDAY, statusCheckedAt: FRIDAY, latest: false,
        sameAsDraft: true,
      },
    }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: true });
    open();
    const answer = await screen.findByTestId("channel-answer");
    expect(answer).toHaveTextContent("마지막으로 수집한 답변");
    expect(answer).not.toHaveTextContent("채널에서 확인한 답변");
    expect(screen.queryByTestId("channel-answer-checked")).toBeNull();
    expect(screen.getByTestId("channel-answer-body-read")).toHaveTextContent("답변 본문");
    expect(screen.getByTestId("channel-answer-status-read")).toHaveTextContent("등록 상태");
    expect(screen.getByTestId("channel-answer-read-times")).toHaveTextContent("그 뒤 수집에서는 등록 상태만 확인했어요");
    // Matched on Monday, and not sent by us: an edit on the seller centre since then is not something the screen would
    // know — so what the customer was told is not said.
    expect(screen.getByTestId("seller-check-after")).toHaveTextContent("채널에 답변이 등록된 것을 확인했어요");
    expect(screen.getByTestId("seller-check-after")).not.toHaveTextContent("안내했어요");
  });

  it("a text stored before its read time was kept says it does not know when, and invents no date", async () => {
    const theirs = "환불은 반품 확인 뒤 3일 안에 처리됩니다.";
    getInquiryDetailStrict.mockResolvedValue(detail({
      ...COPIED,
      channelAnswer: {
        body: theirs, answeredAt: MONDAY, bodyCheckedAt: null, statusCheckedAt: FRIDAY, latest: false, sameAsDraft: false,
      },
    }));
    getSellerCheck.mockResolvedValue({ check: RECORD, ask: null, draftMatches: true });
    open();
    expect(await screen.findByTestId("channel-answer")).toHaveTextContent("마지막으로 수집한 답변");
    expect(screen.getByTestId("channel-answer-body-read")).toHaveTextContent(
      "답변 본문을 언제 확인했는지는 기록이 없어요",
    );
    expect(screen.getByTestId("channel-answer-read-times")).not.toHaveTextContent("등록 상태만 확인했어요");
    expect(screen.getByTestId("channel-answer-differs")).toHaveTextContent("마지막으로 수집했을 때 채널에 등록돼 있던 답변");
    expect(screen.getByTestId("channel-answer-differs")).not.toHaveTextContent("고객에게는 채널에 등록된 이 답변이 보여요");
  });

  it("an earlier matching text behind our own verified send still says what was told", async () => {
    getInquiryDetailStrict.mockResolvedValue(detail({
      ...COPIED,
      delivery: SENT_BY_US,
      channelAnswer: {
        body: DRAFTED.draft.comments, answeredAt: MONDAY, bodyCheckedAt: MONDAY, statusCheckedAt: FRIDAY, latest: false,
        sameAsDraft: true,
      },
    }));
    getSellerCheck.mockResolvedValue({ check: { ...RECORD, nextCheckOn: "2026-11-09" }, ask: null, draftMatches: true });
    open();
    expect(await screen.findByTestId("channel-answer")).toHaveTextContent("마지막으로 수집한 답변");
    expect(screen.getByTestId("seller-check-after")).toHaveTextContent("진행 상황을 안내했어요");
  });
});
