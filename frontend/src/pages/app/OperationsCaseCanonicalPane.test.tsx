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
/** `yyyy-MM-dd`, n days ago, read in KST — the day boundary the elapsed-time contract uses. */
const kstDaysAgo = (n: number) =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date(Date.now() - n * 86_400_000));

function detail(over: Partial<OperationsCaseDetail> = {}): OperationsCaseDetail {
  return {
    caseId: "case-1",
    open: true,
    subjectKind: "INQUIRY",
    channelNameKo: "카페24 자사몰",
    productName: "선바로 일체형 전선몰딩",
    productScopeAvailable: true,
    // Eight days ago, in the timezone the elapsed contract counts in. It was a literal date, and a literal
    // date makes 「8일 동안」 true on the day the test was written and false on every day after it.
    receivedOn: kstDaysAgo(8),
    openedAt: null,
    answerStateNote: null,
    rating: null,
    title: "문의 드립니다",
    body: "포장을 뜯지 않은 경우도 교환이 가능한가요?",
    reasonNote: "고객이 답변을 기다리고 있습니다.",
    disposition: "NEEDS_DECISION",
    // 기본은 「판매자 기준이 적용되지 않은 건」 — 정책이 테스트 주제인 케이스만 override한다.
    appliedPolicy: null,
    policyProblemKo: null,
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
    /* The basis is still said once, beside the heading, off `draft.evidence` — it is now the metadata
       line 「미발송 · 운영 정책 2개 근거」 rather than the sentence 「…를 근거로 준비한 답변입니다」
       (visual target, 2026-10-02: the document reading states what sent-state and basis ARE, and does
       not narrate them). What is fenced here is unchanged: the count, its authority, and that the seller
       is told what the answer was written out of before they send it. */
    expect(within(answer).getByText(/운영 정책 2개 근거/)).toBeTruthy();
    expect(within(answer).getByText(/미발송/)).toBeTruthy();

    /* <b>The control is on the pane's floor now</b> (product-owner decision, 2026-10-03), and the
       editor it opens is still inside this section — which is the half the move had to preserve. */
    const dock = screen.getByTestId("pane-footer");
    await userEvent.click(within(dock).getByRole("button", { name: "수정하기" }));
    expect(within(answer).getByRole("textbox")).toBeTruthy();
  });

  /**
   * <b>고객의 말 → 왜 지금 볼 일인가 → 확인한 내용 → 준비된 답변 → action</b> (product-owner decision,
   * 2026-10-02).
   *
   * <p>The pane opened on 왜 지금 볼 일인가 and put the customer's request under it, which is a document
   * explaining itself before it says what it is about. Asserted as an ORDER over the rendered regions
   * rather than as five separate presence checks: every one of those five passed under the old order
   * too, and the order is the whole of what changed.
   */
  it("the pane reads as a document: the customer's words first, the justification after", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    const { container } = drawPane();

    const article = await screen.findByRole("article", { name: "선택한 항목" });
    /* <b>문의 내용 is no longer a section</b> (확인할 일 canonical, 2026-10-03): the customer's words are
       the pane's HEADING now, so a block repeating them under it would be the same sentence twice. The
       order this test exists to fence is unchanged — the request, then what justifies it, then the
       answer — and the first element of it is asserted below as the heading. */
    const named = ["왜 지금 볼 일인가", "확인한 내용", "준비된 답변"];
    const order = [...article.querySelectorAll("section[aria-label]")]
      .map((el) => el.getAttribute("aria-label"))
      .filter((label): label is string => named.includes(label!));
    expect(order).toEqual(named);
    expect(within(article).getAllByRole("heading", { level: 2 })[0].textContent).toBe("포장을 뜯지 않은 경우도 교환이 가능한가요?");
    // The action is the end of the document, after everything that justifies it.
    const send = within(article).getByRole("link", { name: /발송 화면으로/ });
    const said = within(article).getByText("포장을 뜯지 않은 경우도 교환이 가능한가요?");
    expect(said.compareDocumentPosition(send) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(container.querySelector("textarea")).toBeNull();
  });

  /**
   * <b>왜 지금 볼 일인가 carries only what the seller can check</b> (product-owner decision,
   * 2026-10-02). The stored `summary` ends with reviewnary's own ranking of the parts of one question
   * — 「(확인한 요청 2건 중 가장 먼저 해결해야 하는 항목 기준입니다.)」 — which is bookkeeping, not a
   * fact about this customer. The head sentence stays whole.
   */
  it("왜 지금 볼 일인가 drops our own ranking clause and keeps the sentence it qualified", async () => {
    api.getOperationsCase.mockResolvedValue(
      detail({ summary: "등록된 지식으로 답변할 수 있는 문의입니다. (확인한 요청 2건 중 가장 먼저 해결해야 하는 항목 기준입니다.)" }),
    );
    drawPane();

    const why = await screen.findByRole("region", { name: "왜 지금 볼 일인가" });
    expect(within(why).getByText("등록된 지식으로 답변할 수 있는 문의입니다.")).toBeTruthy();
    expect(why.textContent).not.toMatch(/확인한 요청|가장 먼저/);
  });

  /**
   * <b>수정하기 → 발송 화면으로</b> (product-owner decision, 2026-10-02): the detour is considered
   * first and the ending last, and the primary is marked by colour rather than by position.
   *
   * <p><b>Both of them on the pane's docked floor</b> (product-owner decision, 2026-10-03). Measured at
   * 1600×1000 against the two mockups: the flow ends around y=660 in a 1,000px pane, so an action that
   * follows the answer sits two-thirds up the column with nothing under it. The order, the colour and
   * the sizing are unchanged — this fence now reads them where they stand.
   */
  it("the detour comes before the ending, and the primary is still the only solid control", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    drawPane();

    await screen.findByRole("region", { name: "준비된 답변" });
    const answer = screen.getByTestId("pane-footer");
    const edit = within(answer).getByRole("button", { name: "수정하기" });
    const send = within(answer).getByRole("link", { name: /발송 화면으로/ });
    expect(edit.compareDocumentPosition(send) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(send.className).toMatch(/bg-brand-700/);
    expect(edit.className).not.toMatch(/bg-brand-700/);
    // Content-sized: neither control is told to fill or divide the row.
    expect(send.className).not.toMatch(/flex-1|w-full/);
    expect(edit.className).not.toMatch(/flex-1|w-full/);
  });

  /**
   * <b>No card around the prepared answer</b> (visual target, 2026-10-02). 확인할 일's pane is a document:
   * the answer is its body, not an object set into it, and the brand-outlined box around it was the
   * loudest surface on a screen whose subject is one customer's sentence. Fenced because the ring is one
   * class on a shared component — {@code ActionCard} still draws it for every other reading — so losing
   * the flag would restore the box with nothing else failing.
   */
  it("the prepared answer is set in the page, not boxed in a brand outline", async () => {
    api.getOperationsCase.mockResolvedValue(detail());
    drawPane();

    const answer = await screen.findByRole("region", { name: "준비된 답변" });
    const box = answer.className;
    expect(box).not.toMatch(/ring|rounded-\[16px\]|shadow-\[/);
    expect(box).toMatch(/border-t/);
    // And the answer itself is read, not a filled field waiting to be typed in.
    const body = within(answer).getByText("수령 후 7일 이내, 미개봉 상태라면 교환이 가능합니다.");
    expect(body.className).toMatch(/text-prose/);
    expect(body.className).not.toMatch(/bg-/);
  });

  /**
   * <b>The warning stands immediately before the press</b> (product-owner decision, 2026-10-03).
   *
   * <p>확인할 일 opens an inquiry-origin case in a pane whose floor carries the way to the send screen,
   * and the 문의 screen has warned beside that control since the publish package. The case read now
   * carries the same sentence off the same check, so the same seller is told on both screens.
   *
   * <p>Fenced on placement as well as presence: a warning that is true of the send and is drawn
   * anywhere but beside the control that opens it is a warning the seller reads after deciding.
   */
  it("draws the answer-state warning in the dock, above the control it qualifies", async () => {
    const note = "이 채널의 문의 수집이 최신이 아니라, 이 문의에 이미 답변이 달렸는지 지금은 확인할 수 없습니다.";
    api.getOperationsCase.mockResolvedValue(detail({ answerStateNote: note }));
    drawPane();

    const dock = await screen.findByTestId("pane-footer");
    const warning = within(dock).getByText(note);
    expect(warning.className).toMatch(/text-warn/);
    const send = within(dock).getByRole("link", { name: /발송 화면으로/ });
    expect(warning.compareDocumentPosition(send) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("says nothing when the channel's state was proven — a reassurance nobody asked for", async () => {
    api.getOperationsCase.mockResolvedValue(detail({ answerStateNote: null }));
    drawPane();

    const dock = await screen.findByTestId("pane-footer");
    expect(dock.textContent).not.toMatch(/확인할 수 없습니다/);
  });
  /**
   * <b>Core Operations polish, 2026-10-06 — 내부 조각이 회사의 기준 이름으로 나가지 않는다.</b>
   *
   * <p>데모 org의 한 건이 그대로 보여 주었다: 제목이 「문의 드립니다」이고, 서버의 주제 추출이 그 마지막
   * 토막을 집어 {@code missingSubject: "드립니다"}를 보냈다. 화면은 그것을 따옴표에 넣어 「「드립니다」에
   * 대해 고객에게 안내할 기준이 없습니다」로, 칩에서는 「드립니다 기준 없음」으로 내보냈다 — 회사가
   * 기준을 적어 둘 수 있는 주제가 아니라 인사말의 서술어다. 추출은 서버의 일이고(보고함), 화면이 할 일은
   * <b>못 믿을 이름을 인용하지 않는 것</b>이다. 사실(기준이 없다)은 그대로 남는다.
   */
  it("서술어로 끝나는 주제는 기준 이름으로 인용하지 않는다", async () => {
    api.getOperationsCase.mockResolvedValue(
      detail({
        draft: null,
        knowledgeUsed: [],
        gap: {
          missingSubject: "드립니다",
          sentence: "「드립니다」에 대해 고객에게 안내할 기준이 없습니다.",
          suggestedScope: "ORG",
        },
      }),
    );
    drawPane();

    const need = await screen.findByRole("region", { name: "필요한 정보" });
    expect(within(need).getByText("이 질문에 대해 고객에게 안내할 기준이 없습니다.")).toBeTruthy();
    expect(screen.queryByText(/드립니다/)).toBeNull();
    // 확인한 내용의 칩도 같은 규칙을 쓴다 — 주제가 없으면 사실만 말한다.
    expect(screen.getByText("안내 기준 없음")).toBeTruthy();
  });

  it("쓸 수 있는 주제는 그대로 인용한다", async () => {
    api.getOperationsCase.mockResolvedValue(
      detail({
        draft: null,
        knowledgeUsed: [],
        gap: {
          missingSubject: "교환 가능 기간",
          sentence: "「교환 가능 기간」에 대해 고객에게 안내할 기준이 없습니다.",
          suggestedScope: "ORG",
        },
      }),
    );
    drawPane();

    const need = await screen.findByRole("region", { name: "필요한 정보" });
    expect(within(need).getByText("「교환 가능 기간」에 대해 고객에게 안내할 기준이 없습니다.")).toBeTruthy();
    expect(screen.getByText("교환 가능 기간 기준 없음")).toBeTruthy();
  });

  /**
   * <b>Core Operations polish, 2026-10-06</b> — 이 pane에서 면을 가진 것은 없다. 초안 카드와 처리 변경은
   * 이미 그랬고, 「필요한 정보」만 브랜드 테두리와 그림자를 단 상자로 남아 1600에서 화면의 가장 큰 면이었다.
   */
  it("필요한 정보도 문서의 한 구역이지 상자가 아니다", async () => {
    api.getOperationsCase.mockResolvedValue(
      detail({
        draft: null,
        knowledgeUsed: [],
        gap: { missingSubject: "교환 가능 기간", sentence: "「교환 가능 기간」에 대해 고객에게 안내할 기준이 없습니다.", suggestedScope: "ORG" },
      }),
    );
    drawPane();

    const need = await screen.findByRole("region", { name: "필요한 정보" });
    expect(need.className).not.toMatch(/ring|rounded-\[16px\]|shadow-\[/);
    expect(need.className).toMatch(/border-t/);
    // 저장은 제 말의 폭만 쓴다 — 바닥 dock의 1차 행동과 같은 크기로 겨루지 않는다.
    const save = within(need).getByRole("button", { name: /저장 후 초안 재작성/ });
    expect(save.className).not.toMatch(/w-full/);
  });

  /**
   * <b>Core Operations polish, 2026-10-06</b> — 「왜 지금 볼 일인가」는 바로 아래 블록이 입력란과 버튼으로
   * 들고 있는 말을 한 번 더 하지 않는다. 측정된 세 줄 중 둘이 같은 사실이었다.
   */
  it("필요한 정보가 서 있으면 같은 말을 왜 지금 볼 일인가에서 반복하지 않는다", async () => {
    const recommendation = "답변에 필요한 회사 정보가 없어 답을 만들 수 없습니다. 정보를 알려 주시면 다시 준비합니다.";
    api.getOperationsCase.mockResolvedValue(
      detail({
        draft: null,
        knowledgeUsed: [],
        whyDecisionNeeded: recommendation,
        gap: { missingSubject: "교환 가능 기간", sentence: "「교환 가능 기간」에 대해 고객에게 안내할 기준이 없습니다.", suggestedScope: "ORG" },
      }),
    );
    drawPane();

    const why = await screen.findByRole("region", { name: "왜 지금 볼 일인가" });
    // 기다린 날수와 서버가 쓴 사실 한 줄은 그대로 선다.
    expect(within(why).getByText(/8일 동안 답변이 등록되지 않았습니다/)).toBeTruthy();
    expect(why.textContent).not.toContain(recommendation);
  });
});
