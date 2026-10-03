// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { CustomerInbox } from "./CustomerInbox";
import { expectNoAxeViolations } from "../../test/axe";
import type { InquiryQueueItem, InquiryRowItem } from "../../lib/types";

/**
 * 문의 — 지금 처리할 일, then 전체 문의 (Inquiry Operations Workspace v1).
 *
 * <b>CONTRACT CHANGED, and this file was rewritten for it.</b> The screen used to make ONE read — the
 * inbox feed at `limit=500` — and render every row it got in a single column, filtering and ordering
 * on the client. On the demo org that was 94 rows and 7,100px with no way to search; a seller with
 * three thousand inquiries would have been handed three thousand rows. The old assertions were about
 * that shape: a client-side 인박스 필터 rail, a single 문의 목록, and a mixed 문의+리뷰 mode no route
 * had passed since product assembly A2.
 *
 * Everything those tests protected that is still true is asserted below — the list is the screen
 * until a row is chosen, a deep link opens its row, the response workflow appears only when a work
 * item resolves, nothing offers a send on the default posture, empty and failed states are told
 * apart. What is new is the split itself, and the two properties it rests on: the queue's membership
 * comes from the WORK QUEUE read, and the record's filters are the server's.
 */

const getItemAnalysisStrict = vi.fn();
const getInquiryQueueStrict = vi.fn();
const getInquiryRowsStrict = vi.fn();
const getInquiryDetailStrict = vi.fn();
const generateInquiryProposal = vi.fn();
const getInquiryPublishCapability = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getItemAnalysisStrict: () => getItemAnalysisStrict(),
    getInquiryQueueStrict: (params: unknown) => getInquiryQueueStrict(params),
    getInquiryRowsStrict: (params: unknown) => getInquiryRowsStrict(params),
    getInquiryDetailStrict: (id: string) => getInquiryDetailStrict(id),
    generateInquiryProposal: (id: string) => generateInquiryProposal(id),
    getInquiryPublishCapability: () => getInquiryPublishCapability(),
  },
  getToken: () => null,
}));

function row(over: Partial<InquiryRowItem> & Pick<InquiryRowItem, "inquiryId">): InquiryRowItem {
  return {
    workItemId: null,
    sellerAccountId: "s1",
    channelId: "c1",
    channelCode: "CAFE24",
    channelNameKo: "카페24 자사몰",
    productId: null,
    productName: null,
    phase: null,
    status: "ANSWERED",
    title: "제목",
    snippet: "고객이 쓴 문장",
    receivedAt: "2026-08-03T10:00:00Z",
    answeredAt: null,
    sourceSubtype: null,
    executableIdentity: "NONE",
    ...over,
  };
}

function queued(over: Partial<InquiryQueueItem> & Pick<InquiryQueueItem, "workItemId" | "inquiryId">): InquiryQueueItem {
  return {
    sellerAccountId: "s1",
    channelId: "c1",
    channelCode: "CAFE24",
    channelNameKo: "카페24 자사몰",
    productId: null,
    productName: null,
    phase: "OPEN",
    status: "UNANSWERED",
    title: "제목",
    snippet: "답을 기다리는 문장",
    receivedAt: "2026-08-01T10:00:00Z",
    hasDraft: false,
    ...over,
  };
}

/**
 * ONE read for the whole of `AWAITING_SELLER` — the endpoint's own default (Secondary Workspaces UX
 * Closure v1 §1). The fixture used to answer per phase because this screen asked twice and joined the
 * pages itself, which is what let 홈 and 문의 print different numbers under the same noun. A caller that
 * names a phase here is now the exception, so the fixture refuses one: an unexpected `phase` would mean
 * the screen went back to deciding membership on its own.
 */
function queueOf(rows: InquiryQueueItem[]) {
  return (params: { phase?: string } = {}) => {
    if (params.phase) throw new Error(`the queue is read as one set, not per phase (got ${params.phase})`);
    return Promise.resolve({ content: rows, totalElements: rows.length });
  };
}

function renderInbox(path = "/inquiries") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/inquiries" element={<CustomerInbox />} />
        <Route path="/inquiries/:itemRef" element={<CustomerInbox />} />
      </Routes>
    </MemoryRouter>,
  );
}

const RECORD = [
  row({ inquiryId: "i1", status: "UNANSWERED", productName: "케이블 몰딩", snippet: "폭이 몇 mm인가요" }),
  row({ inquiryId: "i2", snippet: "잘 받았습니다", channelCode: "NAVER", channelNameKo: "네이버 스마트스토어" }),
];

beforeEach(() => {
  getItemAnalysisStrict.mockResolvedValue([]);
  getInquiryQueueStrict.mockResolvedValue({ content: [], totalElements: 0 });
  getInquiryRowsStrict.mockResolvedValue({ items: RECORD, totalCount: RECORD.length, limit: 50, productId: null });
  getInquiryDetailStrict.mockResolvedValue({
    workItemId: "w1",
    inquiryId: "i1",
    sellerAccountId: "s1",
    channelId: "c1",
    channelCode: "CAFE24",
    channelNameKo: "카페24",
    isSecret: false,
    phase: "OPEN",
    status: "UNANSWERED",
    informStatus: null,
    title: "폭이 몇 mm인가요",
    details: "굵은 전선도 들어가나요?",
    receivedAt: "2026-08-03T10:00:00Z",
    proposal: null,
    draft: null,
  });
  // The DEFAULT deployment posture: the send path is off and no channel has a reply adapter. Every
  // assertion below about "never offers to send" is therefore about the real default.
  getInquiryPublishCapability.mockResolvedValue({ executionEnabled: false, replyAdapterChannelCodes: [] });
});

afterEach(() => {
  vi.clearAllMocks();
});

/*
 * CONTRACT CHANGE (UI/UX v2 Phase 3, product-owner decision): the work is 확인할 일's, and 문의 is where inquiries
 * are looked up. This screen used to open with its own 「지금 처리할 일」 list — the same inquiries 확인할 일 lists,
 * drawn a second time — and its ordering/backlog/초안 준비됨 behaviour is now asserted where the list lives
 * (`OperationsCaseQueue.test.tsx`, `homeWork.test.ts`). What stays true here is asserted: the queue read is ONE read
 * of the declared set, its count is the link, and a failed read says so instead of showing none.
 */
describe("확인할 일 — linked from the record, not drawn twice", () => {
  it("draws no second work list, and links to 확인할 일 with the queue's own count", async () => {
    getInquiryQueueStrict.mockImplementation(
      queueOf([queued({ workItemId: "w1", inquiryId: "i1" }), queued({ workItemId: "w2", inquiryId: "i2" })]),
    );
    renderInbox();
    const link = await screen.findByRole("link", { name: /확인할 일에 문의 2건/ });
    expect(link).toHaveAttribute("href", "/customer-operations/cases");
    expect(screen.queryByLabelText("지금 처리할 일")).toBeNull();
    // Membership is still the server's: one read, no phase named.
    expect(getInquiryQueueStrict).toHaveBeenCalledTimes(1);
    expect(getInquiryQueueStrict.mock.calls[0][0]).not.toHaveProperty("phase");
  });

  it("says nothing about 확인할 일 when nothing is waiting — never 「0건」", async () => {
    renderInbox();
    await screen.findByLabelText("문의 목록");
    expect(screen.queryByRole("link", { name: /확인할 일에 문의/ })).toBeNull();
  });

  it("a queue read that FAILED says so — an unread queue is not an empty one", async () => {
    getInquiryQueueStrict.mockRejectedValue(new Error("down"));
    renderInbox();
    expect(await screen.findByText(/처리할 문의 수를 불러오지 못했습니다/)).toBeInTheDocument();
    // The record is a separate read and is unaffected.
    expect(screen.getByLabelText("문의 목록")).toBeInTheDocument();
  });
});

describe("전체 문의 — the record, filtered by the server", () => {
  // CONTRACT CHANGE (Product Operations Continuity v1 §6). This used to assert the sentence
  // 「최근 2건을 보여 드립니다. 나머지는 위에서 찾아 주세요」 — true, and a dead end: the read was capped
  // at 50 rows and always asked for page 0, so search was the only way out of a record whose own total
  // said there were 3,120. What is asserted now is what was asserted then plus the way through: the
  // page is bounded, the whole set's count is shown, a record that holds everything says nothing, and
  // 더 보기 asks the server for the next page rather than telling the seller to search.
  it("is one bounded page with the whole set's count, and offers the way to the rest", async () => {
    getInquiryRowsStrict.mockResolvedValue({ items: RECORD, totalCount: 3120, limit: 50, productId: null });
    renderInbox();
    await screen.findByLabelText("문의 목록");
    expect(screen.getByText(/2 \/ 3120건/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "더 보기" })).toBeInTheDocument();
    expect(getInquiryRowsStrict).toHaveBeenCalledWith(expect.objectContaining({ limit: 50, page: 0 }));
  });

  it("더 보기 asks for the next page of the same question", async () => {
    getInquiryRowsStrict.mockResolvedValue({ items: RECORD, totalCount: 3120, limit: 50, productId: null });
    renderInbox();
    await screen.findByLabelText("문의 목록");
    fireEvent.click(screen.getByRole("button", { name: "더 보기" }));
    await waitFor(() =>
      expect(getInquiryRowsStrict).toHaveBeenCalledWith(expect.objectContaining({ limit: 50, page: 1 })),
    );
  });

  it("a page that holds everything offers no way to more", async () => {
    renderInbox();
    await screen.findByLabelText("문의 목록");
    expect(screen.queryByRole("button", { name: "더 보기" })).toBeNull();
  });

  it("the search box narrows the SERVER read, not the loaded rows", async () => {
    const user = userEvent.setup();
    renderInbox();
    await screen.findByLabelText("문의 목록");
    await user.type(screen.getByLabelText("문의 내용 검색"), "세금계산서{Enter}");
    expect(getInquiryRowsStrict).toHaveBeenLastCalledWith(expect.objectContaining({ q: "세금계산서" }));
  });

  /**
   * 답변 상태는 설정이 아니라 목록의 축이므로 탭이다(문의 canonical, 2026-10-03) — 그리고 탭은 자기
   * 숫자를 들고 있어야 한다. 채널은 축이 아니라 축 위의 설정이라 조용한 select로 남는다.
   */
  it("답변 상태 is a tab that carries its own count, and 채널 is still the server's", async () => {
    const user = userEvent.setup();
    renderInbox();
    await screen.findByLabelText("문의 목록");
    await user.click(screen.getByRole("button", { name: /답변 필요/ }));
    await waitFor(() =>
      expect(getInquiryRowsStrict).toHaveBeenLastCalledWith(expect.objectContaining({ status: "UNANSWERED" })),
    );
    await user.selectOptions(screen.getByLabelText("채널"), "NAVER");
    await waitFor(() =>
      expect(getInquiryRowsStrict).toHaveBeenLastCalledWith(expect.objectContaining({ channel: "NAVER" })),
    );
  });

  it("an answered row is quieter than the work above it, and still readable", async () => {
    renderInbox();
    const record = await screen.findByLabelText("문의 목록");
    expect(within(record).getByText("잘 받았습니다")).toBeInTheDocument();
  });

  it("tells 「찾는 게 없다」 and 「아무것도 없다」 apart", async () => {
    getInquiryRowsStrict.mockResolvedValue({ items: [], totalCount: 0, limit: 50, productId: null });
    const { unmount } = renderInbox();
    expect(await screen.findByText("아직 들어온 문의가 없습니다")).toBeInTheDocument();
    unmount();

    renderInbox("/inquiries?q=없는말");
    expect(await screen.findByText("찾는 문의가 없습니다")).toBeInTheDocument();
  });

  it("says the read failed rather than showing an empty record", async () => {
    getInquiryRowsStrict.mockRejectedValue(new Error("down"));
    renderInbox();
    expect(await screen.findByText("문의를 불러오지 못했습니다")).toBeInTheDocument();
  });
});

describe("상품 → 문의 doorway", () => {
  it("?productId narrows the SAME read the product's number was counted with, and says which product", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i1", status: "UNANSWERED", productId: "p1", productName: "케이블 몰딩" })],
      totalCount: 1,
      limit: 50,
      productId: "p1",
    });
    renderInbox("/inquiries?productId=p1&status=UNANSWERED");
    await screen.findByLabelText("문의 목록");

    expect(getInquiryRowsStrict).toHaveBeenCalledWith(
      expect.objectContaining({ productId: "p1", status: "UNANSWERED" }),
    );
    const scope = screen.getByTestId("record-product-scope");
    expect(scope).toHaveTextContent("케이블 몰딩");
  });

  it("the WORK count is scoped too — a doorway does not count 21 other products' items", async () => {
    getInquiryQueueStrict.mockImplementation(
      queueOf([
        queued({ workItemId: "w-mine", inquiryId: "mine", productId: "p1", productName: "케이블 몰딩" }),
        queued({ workItemId: "w-other", inquiryId: "other", productId: "p2", productName: "다른 상품" }),
        queued({ workItemId: "w-none", inquiryId: "none" }),
      ]),
    );
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "mine", status: "UNANSWERED", productId: "p1", productName: "케이블 몰딩" })],
      totalCount: 1,
      limit: 50,
      productId: "p1",
    });
    renderInbox("/inquiries?productId=p1");

    // The link counts this product's waiting inquiries, not the other products' — scoped from the rows already
    // read, so the queue is not asked a second time for the product.
    expect(await screen.findByRole("link", { name: /확인할 일에 문의 1건/ })).toBeInTheDocument();
    expect(getInquiryQueueStrict).toHaveBeenCalledTimes(1);
  });

  it("the scope can be cleared, and clearing it re-reads without the product", async () => {
    const user = userEvent.setup();
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i1", productId: "p1", productName: "케이블 몰딩" })],
      totalCount: 1,
      limit: 50,
      productId: "p1",
    });
    renderInbox("/inquiries?productId=p1");
    await screen.findByTestId("record-product-scope");
    await user.click(screen.getByRole("button", { name: "전체 문의 보기" }));
    expect(getInquiryRowsStrict).toHaveBeenLastCalledWith(expect.not.objectContaining({ productId: "p1" }));
  });
});

describe("deep link and the exact inquiry", () => {
  it("opens the requested row from the page it is already on", async () => {
    renderInbox("/inquiries/i1");
    // 질문은 pane의 제목이다(문의 canonical, 2026-10-03) — 「선택한 항목」 아래가 아니라 그 위.
    const pane = await screen.findByLabelText("선택한 문의");
    expect(within(pane).getAllByText("폭이 몇 mm인가요").length).toBeGreaterThan(0);
  });

  it("a link naming a row the page does not hold is fetched by id — one exact read", async () => {
    getInquiryRowsStrict.mockImplementation((params: { inquiryId?: string }) =>
      Promise.resolve(
        params?.inquiryId === "elsewhere"
          ? { items: [row({ inquiryId: "elsewhere", snippet: "다른 페이지의 문의" })], totalCount: 1, limit: 1, productId: null }
          : { items: RECORD, totalCount: RECORD.length, limit: 50, productId: null },
      ),
    );
    renderInbox("/inquiries/elsewhere");
    const pane = await screen.findByLabelText("선택한 문의");
    expect(within(pane).getAllByText("다른 페이지의 문의").length).toBeGreaterThan(0);
    expect(getInquiryRowsStrict).toHaveBeenCalledWith(expect.objectContaining({ inquiryId: "elsewhere" }));
  });

  it("says so honestly when the row cannot be found at all", async () => {
    getInquiryRowsStrict.mockImplementation((params: { inquiryId?: string }) =>
      Promise.resolve(
        params?.inquiryId
          ? { items: [], totalCount: 0, limit: 1, productId: null }
          : { items: RECORD, totalCount: RECORD.length, limit: 50, productId: null },
      ),
    );
    renderInbox("/inquiries/does-not-exist");
    expect(await screen.findByText("문의를 찾을 수 없습니다")).toBeInTheDocument();
  });
});

/**
 * <b>고객이 쓴 말이 먼저 오고, 제목은 뭔가를 더해 줄 때만 옆에 선다</b> (문의 canonical, 2026-10-03,
 * product-owner decision). 이전 계약은 「제목이 제목 자리에 온다」였고 그 이유도 분명했다 — 제목이 목록
 * 어디에도 안 나왔으니까. 실제 데이터가 그 대가를 보여 줬다: 데모 org 최신 11건 중 5건은 제목이 비었거나
 * 「문의 드립니다」다. 두 칸 다 화면에 남고, 역할만 바뀐다.
 */
describe("문의의 첫 시선 — 고객이 쓴 말", () => {
  it("목록은 본문을 첫 줄에, 제목은 사실 줄에 그린다", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i9", status: "UNANSWERED", title: "세금계산서 발행 문의", snippet: "사업자등록증 첨부했습니다" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries");
    const link = await screen.findByRole("link", { name: /사업자등록증 첨부했습니다/ });
    // 두 칸 모두 행에 있다 — 제목은 사라지지 않고 자리를 옮겼다.
    expect(within(link).getByText("사업자등록증 첨부했습니다")).toBeInTheDocument();
    expect(within(link).getByText("제목 「세금계산서 발행 문의」")).toBeInTheDocument();
    expect(within(link).getByText(/카페24 자사몰/)).toBeInTheDocument();
    expect(within(link).getByText("답변 필요")).toBeInTheDocument();
  });

  it("아무것도 더해 주지 않는 제목은 그리지 않는다 — 「문의 드립니다」", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i9", status: "UNANSWERED", title: "문의 드립니다", snippet: "교환은 언제까지 되나요" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries");
    const link = await screen.findByRole("link", { name: /교환은 언제까지 되나요/ });
    expect(within(link).queryByText(/문의 드립니다/)).toBeNull();
  });

  it("제목이 없는 문의는 본문이 제목 자리를 대신하고, 본문을 두 번 그리지 않는다", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i9", status: "UNANSWERED", title: null, snippet: "폭이 몇 mm인가요" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries");
    const link = await screen.findByRole("link", { name: /폭이 몇 mm인가요/ });
    expect(within(link).getAllByText("폭이 몇 mm인가요")).toHaveLength(1);
  });

  it("상세는 고객이 쓴 문장을 한 번만 그린다", async () => {
    // 실제 live NAVER 문의(답변 대기 목록에 없는 문의)에서 같은 문장이 한 화면에 세 번 나왔다: pane의
    // 제목, InboxDetail의 제목, 그리고 「문의 발췌」의 본문.
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i9", status: "UNANSWERED", title: null, snippet: "폭이 몇 mm인가요" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i9");
    await screen.findByLabelText("선택한 항목");
    expect(screen.getAllByText("폭이 몇 mm인가요")).toHaveLength(1);
    // 채널 이름도 한 번뿐이다 — layout의 meta 줄이 유일한 주인이다.
    expect(screen.getAllByText("카페24 자사몰")).toHaveLength(1);
  });

  it("제목과 본문이 둘 다 있으면 pane은 본문을 제목으로 세우고 제목은 그 아래 한 번", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i9", status: "UNANSWERED", title: "세금계산서 발행 문의", snippet: "사업자등록증 첨부했습니다" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i9");
    await screen.findByLabelText("선택한 항목");
    const pane = screen.getByLabelText("선택한 문의");
    expect(within(pane).getByRole("heading", { level: 2 })).toHaveTextContent("사업자등록증 첨부했습니다");
    expect(within(pane).getAllByText(/제목 「세금계산서 발행 문의」/)).toHaveLength(1);
  });

  it("이미 답변된 문의에는 초안이 없는 이유가 「이미 답변됨」이다", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i9", status: "ANSWERED", channelNameKo: "네이버 스마트스토어" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i9");
    await screen.findByLabelText("선택한 항목");
    expect(screen.getByText(/이미 답변이 등록되어 있어/)).toBeInTheDocument();
    expect(screen.getByText(/하실 일은 없습니다/)).toBeInTheDocument();
    // 「답변을 기다리는 문의에만」은 이 경우의 이유가 아니다.
    expect(screen.queryByText(/답변을 기다리는 문의에만/)).toBeNull();
  });
});

describe("response workflow", () => {
  it("shows no response panel when no work item resolves", async () => {
    renderInbox("/inquiries/i1");
    await screen.findByLabelText("선택한 항목");
    expect(screen.queryByText("응답 제안")).toBeNull();
    // 예전 문장은 「reviewnary가 답변 방향을 제안할 수 없습니다」였다 — 맞지만 이유가 없어서, 제품이
    // 이 문의를 다루지 못한다는 뜻으로 읽힌다. 이유는 규칙이다: 초안은 답변을 기다리는 문의에만 쓴다.
    expect(screen.getByText(/답변을 기다리는 문의에만 초안을 씁니다/)).toBeInTheDocument();
    // 그리고 판매자가 실제로 어디서 답하면 되는지까지.
    expect(screen.getByText(/판매자센터에서 직접 작성/)).toBeInTheDocument();
  });

  it("shows the customer's question and offers to draft an answer", async () => {
    getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
    renderInbox("/inquiries/i1");
    expect(await screen.findByText("고객 문의")).toBeInTheDocument();
    expect(screen.getByText("굵은 전선도 들어가나요?")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /초안 만들기/ })).toBeInTheDocument();
  });

  it("offers no send on the default posture, and never implies one happens by itself", async () => {
    getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
    renderInbox("/inquiries/i1");
    await screen.findByText("고객 문의");
    const text = document.body.textContent ?? "";
    for (const banned of ["자동 발송", "대신 답변", "즉시 전송", "바로 보내기"]) {
      expect(text).not.toContain(banned);
    }
    expect(screen.queryByRole("button", { name: /답변 보내기/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /확인, 등록합니다/ })).toBeNull();
  });

  it("says what a draft is for before it is written — reviewed, then sent on purpose", async () => {
    getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
    renderInbox("/inquiries/i1");
    await screen.findByText("고객 문의");
    expect(screen.getByText(/보내는 것은 확인 후 따로 누릅니다/)).toBeInTheDocument();
  });
});

describe("accessibility", () => {
  it("has no axe violations with a row open", async () => {
    getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
    const { container } = renderInbox("/inquiries/i1");
    await screen.findByLabelText("선택한 항목");
    await expectNoAxeViolations(container);
  });
});

describe("master-detail (UI/UX v2 Phase 2) — the list and the chosen inquiry, side by side", () => {
  it("on a wide screen opens the record's first row beside the list, without a click", async () => {
    const restore = stubWide(true);
    try {
      // i1 is the record's first row; its work item comes from the queue read, so the pane can answer it.
      getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
      renderInbox();
      const pane = await screen.findByLabelText("선택한 문의");
      expect(pane).toBeInTheDocument();
      await waitFor(() => expect(getInquiryDetailStrict).toHaveBeenCalledWith("w1"));
      const record = screen.getByLabelText("문의 목록");
      expect(within(record).getAllByRole("link")[0]).toHaveAttribute("aria-current", "true");
    } finally {
      restore();
    }
  });

  it("on a narrow screen the chosen inquiry takes the column, with the way back to the list", async () => {
    renderInbox("/inquiries/i1");
    expect(await screen.findByRole("link", { name: "← 문의 목록" })).toHaveAttribute("href", "/inquiries");
    expect(screen.queryByLabelText("문의 목록")).toBeNull();
  });
});

/** Stands the list and the detail side by side, as the layout does at 1200px and up. Returns the restore. */
function stubWide(matches: boolean): () => void {
  const original = window.matchMedia;
  window.matchMedia = ((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  return () => {
    window.matchMedia = original;
  };
}

/**
 * <b>문의 canonical — the decision workspace</b> (product-owner decision, 2026-10-03).
 *
 * <p>Four properties this redesign rests on, each of which was broken on purpose and observed failing
 * before it was fenced:
 *
 * <ol>
 *   <li>the one thing to press is at the pane floor, not inside the reading — a long draft used to
 *       carry it below the fold;</li>
 *   <li>what the seller must know before pressing is said right above it, and is said whether or not
 *       SellerOps can register the answer itself: 중복 답변의 위험은 복사해서 손으로 등록할 때도 같다;</li>
 *   <li>the screen does not advertise the machinery — 「AI가 준비한 답변」·「AI 작성」·「AI가 확인한 내용」은
 *       기본 화면에서 사라지고, 작성자는 기록에 남는다;</li>
 *   <li>and the irreversible press is still inside the block that restates what will be sent.</li>
 * </ol>
 */
describe("문의 — the canonical decision workspace (2026-10-03)", () => {
  const DRAFTED = {
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
    title: "문의 드립니다",
    details: "교환은 언제까지 신청해야 하나요?",
    receivedAt: "2026-08-03T10:00:00Z",
    proposal: null,
    productId: null,
    productName: null,
    productBinding: null,
    sourceSubtype: null,
    answerStateProven: false,
    answerStateNote: "이 채널의 문의 수집이 최신이 아니라, 이 문의에 이미 답변이 달렸는지 지금은 확인할 수 없습니다.",
    draftEvidence: [
      { kind: "ORG_POLICY", scopeLabel: "운영 정책", title: "교환·반품 기준", locator: null, sourceId: "s", chunkId: "c", snippet: "수령 후 7일 이내." },
    ],
    replyCapability: null,
    orderContext: null,
    delivery: null,
    draft: {
      version: 1,
      answerStatus: 2,
      title: "교환 신청 기한 안내",
      comments: "수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환이 가능합니다.",
      contentFingerprint: "a".repeat(64),
      fingerprintAlgorithm: "SHA-256",
      createdAt: "2026-08-03T11:00:00Z",
      authorKind: "MODEL",
      modelVersion: "m/v1",
      knowledgeState: "GROUNDED",
      knowledgeNote: null,
      answerBasis: null,
      answerBasisNote: null,
      answerBasisAction: null,
    },
  };

  let restoreWide: () => void;
  beforeEach(() => {
    restoreWide = stubWide(true);
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i1", workItemId: "w1", status: "UNANSWERED", phase: "PROPOSED", title: "문의 드립니다", snippet: "교환은 언제까지 신청해야 하나요?" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
    getInquiryDetailStrict.mockResolvedValue(DRAFTED);
  });
  afterEach(() => restoreWide());

  it("docks the one thing to press at the pane floor, and the body does not draw a second one", async () => {
    renderInbox("/inquiries/i1");
    const dock = await screen.findByTestId("pane-footer");
    expect(within(dock).getByRole("button", { name: "초안 복사" })).toBeInTheDocument();
    // Exactly one in the whole pane — the draft's own header control belongs to the callers that have
    // no dock (확인할 일's pane), and two copies of one action is how a screen grows two behaviours.
    expect(screen.getAllByRole("button", { name: /초안 복사/ })).toHaveLength(1);
  });

  it("says what is unproven immediately above the press — even where the send path is off", async () => {
    // The capability read says executionEnabled:false, so copying is the action. The staleness note
    // used to be drawn only when a SEND was offered, which left the seller pasting a reply by hand
    // with no idea the channel might already carry one.
    renderInbox("/inquiries/i1");
    const dock = await screen.findByTestId("pane-footer");
    expect(within(dock).getByText(/이미 답변이 달렸는지 지금은 확인할 수 없습니다/)).toBeInTheDocument();
    expect(within(dock).getByText("고객에게 나가지 않습니다")).toBeInTheDocument();
  });

  it("does not advertise the machinery: 「AI」 is nowhere on the pane, and the author is in 기록", async () => {
    const user = userEvent.setup();
    renderInbox("/inquiries/i1");
    const pane = await screen.findByLabelText("선택한 문의");
    await within(pane).findByText("준비된 답변");
    expect(within(pane).getByText("답변에 사용한 근거")).toBeInTheDocument();
    // 기록은 `<details>`라 접혀 있어도 DOM에는 있다 — 그래서 묻는 것은 「있느냐」가 아니라 「보이느냐」다.
    for (const node of within(pane).queryAllByText(/AI/)) expect(node).not.toBeVisible();
    // 사라진 것이 아니라 자리를 옮겼다.
    await user.click(within(pane).getByText(/^기록/));
    expect(within(pane).getByText("AI 작성")).toBeVisible();
  });

  it("the question is the pane's own heading, and the boilerplate title is not", async () => {
    renderInbox("/inquiries/i1");
    const pane = await screen.findByLabelText("선택한 문의");
    expect(within(pane).getByRole("heading", { level: 2 })).toHaveTextContent("교환은 언제까지 신청해야 하나요?");
    // 「문의 드립니다」 adds nothing the body does not say, so it is not printed at all.
    expect(within(pane).queryByText(/문의 드립니다/)).toBeNull();
  });
});

/**
 * <b>문의 redesign — the 1600 mockup as the implementation target</b> (2026-10-04, product-owner
 * decision).
 *
 * <p>Front's Inbox was the composition reference and Reviewnary's own visual language was kept, so what
 * changed is the shape of the reading, not the palette. Four of the seven targets are asserted here
 * because each of them is a claim a later edit could quietly undo:
 *
 * <ol>
 *   <li>the selected row is NEUTRAL — the blue fill and the accent bar said 「this row is special」 about
 *       a row whose only job is to say which record the page beside it is showing;</li>
 *   <li>the row says how long the customer has waited, and past a year it says the day it arrived
 *       instead of a count nobody acts on;</li>
 *   <li>준비된 답변 is ONE object — the draft and the evidence it stands on inside a single edge, rather
 *       than a canvas surface and a separate block under it;</li>
 *   <li>the registration condition is a quiet note on the dock's edge, not a paragraph in the middle of
 *       the reading.</li>
 * </ol>
 *
 * <p>The other three — the 240/316/remainder geometry, the body-first title rule and the single bottom
 * primary — are fenced where they are owned: `decisionPane.test.tsx`, `inquiryReading`, and the dock
 * test above.
 */
describe("문의 redesign — the 1600 mockup (2026-10-04)", () => {
  const DRAFTED_WITH_CAPABILITY = {
    workItemId: "w1",
    inquiryId: "i1",
    sellerAccountId: "s1",
    channelId: "c1",
    channelCode: "CAFE24",
    channelNameKo: "카페24 자사몰",
    isSecret: true,
    phase: "PROPOSED",
    status: "UNANSWERED",
    informStatus: null,
    title: "문의 드립니다",
    details: "교환은 언제까지 신청해야 하나요?",
    receivedAt: "2026-08-03T10:00:00Z",
    proposal: null,
    productId: null,
    productName: null,
    productBinding: null,
    sourceSubtype: null,
    answerStateProven: false,
    answerStateNote: "이 문의에 이미 답변이 달렸는지 지금은 확인할 수 없습니다.",
    draftEvidence: [
      { kind: "ORG_POLICY", scopeLabel: "운영 정책", title: "교환·반품 기준", locator: null, sourceId: "s", chunkId: "c", snippet: "수령 후 7일 이내." },
    ],
    replyCapability: null,
    orderContext: null,
    delivery: null,
    draft: {
      version: 1,
      answerStatus: 2,
      title: "교환 신청 기한 안내",
      comments: "수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환이 가능합니다.",
      contentFingerprint: "a".repeat(64),
      fingerprintAlgorithm: "SHA-256",
      createdAt: "2026-08-03T11:00:00Z",
      authorKind: "MODEL",
      modelVersion: "m/v1",
      knowledgeState: "GROUNDED",
      knowledgeNote: null,
      answerBasis: null,
      answerBasisNote: null,
      answerBasisAction: null,
    },
  };

  let restoreWide: () => void;
  beforeEach(() => {
    restoreWide = stubWide(true);
    getInquiryQueueStrict.mockImplementation(queueOf([queued({ workItemId: "w1", inquiryId: "i1" })]));
    getInquiryDetailStrict.mockResolvedValue(DRAFTED_WITH_CAPABILITY);
  });
  afterEach(() => restoreWide());

  it("the selected row is neutral — no accent fill and no accent bar", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i1", workItemId: "w1", status: "UNANSWERED", snippet: "교환은 언제까지 신청해야 하나요?" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i1");
    const chosen = await screen.findByRole("link", { current: true });
    expect(chosen.className).toContain("bg-canvas");
    expect(chosen.className).not.toContain("bg-brand-50");
    expect(chosen.className).not.toContain("shadow-selected");
  });

  it("the row says how long the customer waited, and the day it arrived once that is a year", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [
        row({ inquiryId: "i1", workItemId: "w1", status: "UNANSWERED", snippet: "교환은 언제까지 신청해야 하나요?", receivedAt: "2026-08-03T10:00:00Z" }),
        row({ inquiryId: "old", snippet: "세금계산서 발행 부탁드립니다", receivedAt: "2016-09-09T10:00:00Z" }),
      ],
      totalCount: 2,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i1");
    const list = await screen.findByLabelText("문의 목록");
    // The recent one is a count — the exact number moves with the clock, the shape does not.
    expect(within(list).getByText(/^\d+일 대기$/)).toBeInTheDocument();
    // 2016 is a year past under any clock this product runs on.
    expect(within(list).getByText("2016-09-09 접수")).toBeInTheDocument();
    expect(within(list).queryByText(/^\d{3,}일 대기$/)).toBeNull();
  });

  it("준비된 답변 is one object — the evidence is inside its edge, not a block beside it", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i1", workItemId: "w1", status: "UNANSWERED", snippet: "교환은 언제까지 신청해야 하나요?" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i1");
    const object = await screen.findByTestId("prepared-answer");
    // Head, body and foot of one thing.
    expect(within(object).getByText("준비된 답변")).toBeInTheDocument();
    expect(within(object).getByText("교환 신청 기한 안내")).toBeInTheDocument();
    expect(within(object).getByText(/개봉하지 않은 상품에 한해 교환이 가능합니다/)).toBeInTheDocument();
    expect(within(object).getByText("답변에 사용한 근거")).toBeInTheDocument();
    expect(within(object).getByText("교환·반품 기준")).toBeInTheDocument();
    // And only one of it: a second copy outside the object is the shape this replaced.
    const pane = screen.getByLabelText("선택한 문의");
    expect(within(pane).getAllByText("답변에 사용한 근거")).toHaveLength(1);
  });

  it("the registration condition is the quiet note on the dock's edge, and the last thing in the reading", async () => {
    getInquiryRowsStrict.mockResolvedValue({
      items: [row({ inquiryId: "i1", workItemId: "w1", status: "UNANSWERED", snippet: "교환은 언제까지 신청해야 하나요?" })],
      totalCount: 1,
      limit: 50,
      productId: null,
    });
    renderInbox("/inquiries/i1");
    const note = await screen.findByTestId("registration-note");
    expect(note).toHaveTextContent(/답변을 대신 등록하지 않습니다/);
    // Last in the reading, so it sits against the dock rather than inside the flow.
    const article = screen.getByLabelText("선택한 항목");
    expect(article.lastElementChild).toBe(note);
    // Said once: the sentence left the answer block when it came here.
    expect(screen.getAllByText(/답변을 대신 등록하지 않습니다/)).toHaveLength(1);
  });
});
