// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import type { OperationsCaseDetail } from "../../lib/customerOperationsTypes";

const api = vi.hoisted(() => ({
  getOperationsCase: vi.fn(),
  teachOperationsCase: vi.fn(),
  editOperationsCaseDraft: vi.fn(),
  correctOperationsCase: vi.fn(),
  getOperationsCaseMedia: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api, getToken: () => null }));

import { OperationsCaseView } from "./OperationsCase";

/**
 * <b>확인할 일's detail pane — the canonical mockup's three semantic corrections</b> (2026-10-02).
 *
 * <p>The pane is rendered the way 확인할 일 renders it: {@code variant="pane"}, {@code depth="full"}. The
 * Home draws the same component at {@code preview} depth and the case screen draws it as a page; neither
 * is this reading, and neither is asserted here.
 */
function detail(over: Partial<OperationsCaseDetail> = {}): OperationsCaseDetail {
  return {
    caseId: "case-1",
    open: true,
    subjectKind: "INQUIRY",
    channelNameKo: "카페24 자사몰",
    productName: "선바로 일체형 전선몰딩",
    productScopeAvailable: true,
    receivedOn: "2026-09-24",
    openedAt: null,
    rating: null,
    title: "문의 드립니다",
    body: "포장을 뜯지 않은 경우도 교환이 가능한가요?",
    reasonNote: "고객이 답변을 기다리고 있습니다.",
    disposition: "NEEDS_DECISION",
    decidedBy: "AGENT",
    summary: "등록된 운영 정책으로 답변할 수 있는 문의입니다.",
    recommendedActionType: "REPLY_TO_CUSTOMER",
    recommendedAction: null,
    missingInformation: [],
    whyDecisionNeeded: null,
    investigated: [],
    knowledgeUsed: [
      {
        authority: "운영 정책",
        provenance: "판매자가 등록한 운영 정책",
        title: "교환 가능 기간",
        excerpt: "수령 후 7일 이내",
        capturedOn: "2026-09-01",
        cited: true,
        scope: "ORG",
        pastAnswer: false,
        reusableText: null,
      },
      {
        authority: "운영 정책",
        provenance: "판매자가 등록한 운영 정책",
        title: "상품 상태",
        excerpt: "미개봉 상태 가능",
        capturedOn: "2026-09-01",
        cited: true,
        scope: "ORG",
        pastAnswer: false,
        reusableText: null,
      },
    ],
    gap: null,
    draft: {
      version: 1,
      title: "[답변] 교환",
      body: "수령 후 7일 이내, 미개봉 상태라면 교환이 가능합니다.",
      authorKind: "MODEL",
      answerBasis: "GROUNDED",
      evidence: [
        { kind: "ORG_KNOWLEDGE", scopeLabel: "운영 정책", title: "교환 가능 기간", snippet: "7일" },
        { kind: "ORG_KNOWLEDGE", scopeLabel: "운영 정책", title: "상품 상태", snippet: "미개봉" },
      ],
      delivery: null,
    },
    to: "/inquiries/inq-1",
    ...over,
  };
}

function drawPane() {
  return render(
    <MemoryRouter>
      <OperationsCaseView caseId="case-1" variant="pane" depth="full" />
    </MemoryRouter>,
  );
}

describe("확인할 일 pane — canonical mockup semantics", () => {
  beforeEach(() => vi.clearAllMocks());

  it("an item whose answer is already prepared is never 답변 필요", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    drawPane();

    // The leading status is the real next action, read off the same fact the list's lead column reads:
    // a draft VERSION exists, so the word is 초안 준비됨.
    expect(await screen.findByText("초안 준비됨")).toBeTruthy();
    expect(screen.queryByText("답변 필요")).toBeNull();
  });

  it("drops the prepared-answer word when there is no prepared answer", async () => {
    api.getOperationsCase.mockResolvedValue(detail({ draft: null }));
    drawPane();

    expect(await screen.findByText("확인 필요")).toBeTruthy();
    expect(screen.queryByText("초안 준비됨")).toBeNull();
  });

  it("왜 지금 볼 일인가 draws only the facts the case holds", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    drawPane();

    const why = await screen.findByRole("region", { name: "왜 지금 볼 일인가" });
    expect(within(why).getByText("8일 동안 답변이 등록되지 않았습니다.")).toBeTruthy();
    expect(within(why).getByText("등록된 운영 정책으로 답변할 수 있는 문의입니다.")).toBeTruthy();
    // Two facts, not three: no read on this screen returns a similar-inquiry count, so no line claims one.
    expect(within(why).getAllByRole("listitem")).toHaveLength(2);
  });

  it("says nothing at all rather than a filler line when the case holds no reason", async () => {
    api.getOperationsCase.mockResolvedValue(
      detail({ summary: null, recommendedAction: null, whyDecisionNeeded: null, receivedOn: null }),
    );
    drawPane();

    await screen.findByText("초안 준비됨");
    expect(screen.queryByRole("region", { name: "왜 지금 볼 일인가" })).toBeNull();
  });

  it("확인한 내용 is what was read, as 무엇을 · 어떻게 pairs", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    drawPane();

    const checked = await screen.findByRole("region", { name: "확인한 내용" });
    expect(within(checked).getByText("교환 가능 기간")).toBeTruthy();
    expect(within(checked).getByText("수령 후 7일 이내")).toBeTruthy();
    expect(within(checked).getByText("운영 정책 2개 보기 →")).toBeTruthy();
  });

  it("the prepared answer is read-only until 수정하기 is pressed", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    drawPane();

    const answer = await screen.findByRole("region", { name: "준비된 답변" });
    expect(within(answer).getByText("수령 후 7일 이내, 미개봉 상태라면 교환이 가능합니다.")).toBeTruthy();
    expect(within(answer).queryByRole("textbox")).toBeNull();
    expect(within(answer).getByText("운영 정책 2개를 근거로 준비한 답변입니다.")).toBeTruthy();

    await userEvent.click(within(answer).getByRole("button", { name: "수정하기" }));
    expect(within(answer).getByRole("textbox")).toBeTruthy();
  });
});
