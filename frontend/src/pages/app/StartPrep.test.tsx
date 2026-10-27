// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expectNoAxeViolations } from "../../test/axe";
import { StartPrep } from "./StartPrep";
import type { KnowledgeCandidateView, LearnedKnowledgeResponse } from "../../lib/types";

const getSellerAccountsStrict = vi.fn();
const getKnowledgeCandidates = vi.fn();
const getLearnedKnowledge = vi.fn();
vi.mock("../../lib/apiClient", () => ({
  api: {
    getSellerAccountsStrict: (...a: unknown[]) => getSellerAccountsStrict(...a),
    getKnowledgeCandidates: (...a: unknown[]) => getKnowledgeCandidates(...a),
    getLearnedKnowledge: (...a: unknown[]) => getLearnedKnowledge(...a),
    acceptKnowledgeCandidate: vi.fn(),
    dismissKnowledgeCandidate: vi.fn(),
    importKnowledgeDocument: vi.fn(),
    setKnowledgeDocumentActive: vi.fn(),
  },
  getToken: () => null,
}));

const item = (id: string, reach: number): KnowledgeCandidateView => ({
  id,
  scope: "PRODUCT",
  productId: "p-1",
  productName: "일체형 전선몰딩",
  subject: `제안 ${id}`,
  content: `제안 ${id} 내용`,
  origin: "ISSUE_GUIDANCE",
  evidenceCount: reach,
  state: "OPEN",
  sourceId: null,
  createdAt: "2026-10-27T00:00:00Z",
});

const LEARNED: LearnedKnowledgeResponse = {
  learned: {
    sources: [
      { key: "PAST_INQUIRY_ANSWER", labelKo: "과거 문의 답변", count: 3, latestOn: "2026-10-26", examples: [] },
      { key: "PAST_REVIEW_REPLY", labelKo: "과거 리뷰 답글", count: 0, latestOn: null, examples: [] },
      { key: "PRODUCT_DETAIL", labelKo: "상품 상세 정보", count: 0, latestOn: null, examples: [] },
      { key: "SELLER_MATERIAL", labelKo: "판매자가 알려준 자료", count: 0, latestOn: null, examples: [] },
    ],
    channels: [
      {
        channelNameKo: "쿠팡",
        source: "PAST_REVIEW_REPLY",
        sourceLabelKo: "과거 리뷰 답글",
        availability: "NOT_AVAILABLE",
        sentenceKo: "쿠팡은 판매자 리뷰 답글 기능이 없어요.",
      },
      {
        channelNameKo: "네이버",
        source: "PAST_REVIEW_REPLY",
        sourceLabelKo: "과거 리뷰 답글",
        availability: "SCREEN_READ",
        sentenceKo: "도우미로 판매자센터의 리뷰 상세를 열어 읽어 와요.",
      },
    ],
    historyReads: [],
    canLearnHistory: false,
  },
  lastRun: null,
};

function draw() {
  return render(
    <MemoryRouter>
      <StartPrep />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  getSellerAccountsStrict.mockResolvedValue([{ id: "a-1" }]);
  getKnowledgeCandidates.mockResolvedValue([item("a", 2), item("b", 79), item("c", 15), item("d", 4), item("e", 12), item("f", 5)]);
  getLearnedKnowledge.mockResolvedValue(LEARNED);
});

describe("답변 준비", () => {
  it("asks only for what a reply needs, most reviews first, five at a time", async () => {
    draw();
    const asked = await screen.findByRole("region", { name: "답에 필요한데 아직 없는 것" });
    const rows = within(await within(asked).findByTestId("knowledge-inbox")).getAllByRole("listitem");
    expect(rows.map((row) => /제안 (\w) 내용/.exec(row.textContent ?? "")?.[1])).toEqual(["b", "c", "e", "f", "d"]);
    expect(within(asked).getByRole("link", { name: "6개 모두 보기" })).toHaveAttribute("href", "/knowledge");
  });

  it("says what was read from each source, and the channel's own reason where nothing could be", async () => {
    draw();
    await screen.findByRole("region", { name: "가져온 자료" });
    expect(screen.getByTestId("start-source-PAST_INQUIRY_ANSWER")).toHaveTextContent("지난 문의 답변3건");
    // The channel with a way to get it speaks before the one that has none.
    expect(screen.getByTestId("start-source-PAST_REVIEW_REPLY")).toHaveTextContent("네이버: 도우미로 판매자센터의 리뷰 상세를 열어 읽어 와요.");
    // No count the server did not give: an unread source says so, never 「0건」.
    expect(screen.getByTestId("start-source-PRODUCT_DETAIL")).toHaveTextContent("아직 없어요");
    expect(screen.getByTestId("start-source-PRODUCT_DETAIL")).not.toHaveTextContent("0건");
    // A watched folder is not wired, so the page does not offer one.
    expect(screen.queryByText(/폴더/)).toBeNull();
  });

  it("marks where the seller is and never blocks the work", async () => {
    draw();
    const steps = await screen.findByRole("list", { name: "준비 단계" });
    await within(steps).findByText("1곳 연동됨");
    expect(within(steps).getByText("가게 자료 준비").closest("li")).toHaveAttribute("aria-current", "step");
    expect(screen.getByRole("link", { name: "나중에 하기" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "준비된 답변 보기" })).toHaveAttribute("href", "/reviews");
  });

  it("before any channel, asks for nothing but the connection", async () => {
    getSellerAccountsStrict.mockResolvedValue([]);
    draw();
    expect(await screen.findByText("채널을 연동하면 리뷰를 읽고 여기에 물어볼 것을 모아요.")).toBeTruthy();
    expect(screen.queryByTestId("knowledge-inbox")).toBeNull();
  });

  it("has no accessibility violations", async () => {
    const { container } = draw();
    await screen.findByTestId("knowledge-inbox");
    await expectNoAxeViolations(container);
  });
});
