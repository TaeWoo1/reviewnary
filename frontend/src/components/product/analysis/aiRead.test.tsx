// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ProductAnalysisView } from "../../../lib/productAnalysis";

const api = vi.hoisted(() => ({
  getProductAnalysis: vi.fn(),
  getProductComparison: vi.fn(),
  getAspectReadAvailable: vi.fn(),
  readProductAnalysisWithAi: vi.fn(),
}));
vi.mock("../../../lib/apiClient", () => ({ api }));

import { ProductAnalysisSection, summaryLine } from "./ProductAnalysisSection";
import { methodLine } from "./AnalysisParts";

function view(method: ProductAnalysisView["method"], aiCell = false): ProductAnalysisView {
  return {
    productId: "p1",
    productName: "[합성] 종이컵 디스펜서",
    scope: {
      range: "PRODUCT", from: "2026-08-20", to: "2026-11-17", days: 90, readCap: 2000, reviews: 8, reviewsRead: 8,
      reviewsCapped: false, inquiries: 0, inquiriesRead: 0, inquiriesCapped: false, inquiriesUnclassified: 0,
      unlinkedReviews: 0, unlinkedInquiries: 0, unspecifiedReviews: 0, unspecifiedInquiries: 0, unlinkedIncluded: false,
      channels: [], gaps: [], unattributedPraiseReviews: 0, minReviews: 5, enoughReviews: true,
    },
    method,
    rating: { rated: 0, counts: [0, 0, 0, 0, 0] },
    aspects: [],
    inquiryTopics: [],
    swot: {
      strengths: [{ key: "S:배송", kind: "OBSERVED", source: "REVIEW", title: "배송 칭찬", basis: "리뷰 8건 중 6건", count: 6, denominator: 8, derivedFrom: [], check: null }],
      weaknesses: [],
      opportunities: aiCell
        ? [{ key: "O:ai:1", kind: "HYPOTHESIS", source: "AI", title: "빠른 배송을 앞세우기", basis: "배송이 빠르다는 말이 반복돼요", count: 6, denominator: 8, derivedFrom: ["S:배송"], check: "비교해 보세요" }]
        : [],
      threats: [],
      insufficient: null,
    },
  };
}

const RULE = { kind: "RULE_BASED", complaintRules: "", praiseRules: "", inquiryRules: "" };
const AI = { ...RULE, kind: "AI_READ", aiReader: "review-aspect-read/v1", aiReadReviews: 8, reviewsRead: 8 };

describe("강점·약점 AI 읽기 (2026-11-17)", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.getProductComparison.mockResolvedValue({ from: "2026-08-20", to: "2026-11-17", days: 90, reviewsRead: 0, reviewsCapped: false, unlinkedReviews: 0, unlinkedInquiries: 0, unspecifiedReviews: 0, unspecifiedInquiries: 0, minReviews: 5, method: RULE, products: [] });
  });

  it("says how the reviews were read: rules, all by AI, or part", () => {
    expect(methodLine(view(RULE))).toContain("단어 규칙");
    expect(methodLine(view(AI))).toContain("모두");
    expect(methodLine(view({ ...AI, kind: "MIXED", aiReadReviews: 3 }))).toContain("8건 중 3건은 AI가");
  });

  it("the button is offered only where the capability is on", async () => {
    api.getProductAnalysis.mockResolvedValue(view(RULE));
    api.getAspectReadAvailable.mockResolvedValue({ enabled: false });
    render(<MemoryRouter><ProductAnalysisSection productId="p1" /></MemoryRouter>);
    await screen.findByText("배송 칭찬");
    expect(screen.queryByRole("button", { name: "AI로 읽기" })).toBeNull();
  });

  it("a press reads, re-reads the analysis, and shows AI-written outlook cells as such", async () => {
    api.getProductAnalysis.mockResolvedValueOnce(view(RULE)).mockResolvedValue(view(AI, true));
    api.getAspectReadAvailable.mockResolvedValue({ enabled: true });
    api.readProductAnalysisWithAi.mockResolvedValue({ calls: 2, textsRead: 7, remaining: 0, failedBatches: 0, stopped: null, outlookWritten: true, analysis: view(AI, true) });
    render(<MemoryRouter><ProductAnalysisSection productId="p1" /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "AI로 읽기" }));
    await waitFor(() => expect(api.readProductAnalysisWithAi).toHaveBeenCalledWith("p1", 90));
    expect((await screen.findByRole("status")).textContent).toContain("기회·위협을 근거로 새로 썼어요");
    expect(await screen.findByText("AI가 근거로 쓴 가설")).toBeTruthy();
    expect(screen.getByTestId("analysis-ai-read").textContent).toContain("8건 / 8건");
  });

  it("the summary sentence names what is left and a quota stop", () => {
    const base = { calls: 8, textsRead: 320, remaining: 40, failedBatches: 1, stopped: "QUOTA" as const, outlookWritten: false, analysis: view(RULE) };
    const line = summaryLine(base);
    expect(line).toContain("320개를 새로 읽었어요");
    expect(line).toContain("아직 40개가 남았어요");
    expect(line).toContain("한도에 닿아 멈췄어요");
    expect(summaryLine({ ...base, textsRead: 0, remaining: 0, failedBatches: 0, stopped: null })).toContain("이미 모두 읽었어요");
  });
});
