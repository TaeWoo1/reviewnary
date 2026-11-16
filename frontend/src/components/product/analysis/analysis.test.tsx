// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AnalysisBody } from "./AnalysisParts";
import { ComparisonChart } from "./ComparisonChart";
import { expectNoAxeViolations } from "../../../test/axe";
import type { ProductAnalysisView, ProductComparison } from "../../../lib/productAnalysis";

/**
 * 상품·브랜드 분석 화면 (night unit ②, 2026-11-16): 관찰과 가설이 나뉘어 보이고, 모든 수가 자기 분모와 읽은
 * 범위를 말하며, 근거 문장으로 갈 수 있고, 리뷰와 문의를 한 그래프에 섞지 않는다.
 */

function view(over: Partial<ProductAnalysisView["scope"]> = {}): ProductAnalysisView {
  return {
    productId: "p1",
    productName: "[합성] 원목 선반",
    scope: {
      range: "PRODUCT",
      from: "2026-08-19",
      to: "2026-11-16",
      days: 90,
      readCap: 2000,
      reviews: 12,
      reviewsRead: 12,
      reviewsCapped: false,
      inquiries: 6,
      inquiriesRead: 6,
      inquiriesCapped: false,
      inquiriesUnclassified: 1,
      unlinkedReviews: 3,
      unlinkedInquiries: 0,
      unspecifiedReviews: 0,
      unspecifiedInquiries: 4,
      unlinkedIncluded: false,
      channels: [{ channelCode: "NAVER", reviews: 12, inquiries: 6, reviewsRead: 12, inquiriesRead: 6 }],
      gaps: [],
      unattributedPraiseReviews: 2,
      minReviews: 5,
      enoughReviews: true,
      ...over,
    },
    method: { kind: "RULE_BASED", complaintRules: "RULE_BASED:issue-rules-v2", praiseRules: "praise-rules-v1", inquiryRules: "x" },
    rating: { rated: 12, counts: [1, 1, 0, 2, 8] },
    aspects: [
      {
        aspect: "설치",
        praiseReviews: 5,
        complaintReviews: 1,
        problems: ["난이도"],
        issueId: "i1",
        recentComplaints: 1,
        earlierComplaints: 0,
        praiseQuotes: [{ reviewId: "r1", text: "조립이 쉬워요", rating: 5, channelCode: "NAVER", on: "2026-11-10", problem: null }],
        complaintQuotes: [{ reviewId: "r2", text: "설치가 어려워요", rating: 2, channelCode: "NAVER", on: "2026-11-11", problem: "난이도" }],
      },
    ],
    inquiryTopics: [
      { topic: "사이즈", inquiries: 3, examples: [{ inquiryId: "q1", text: "가로 길이 알려주세요", channelCode: "NAVER", on: "2026-11-12" }] },
    ],
    swot: {
      strengths: [{ key: "S:설치", kind: "OBSERVED", source: "REVIEW", title: "설치 칭찬", basis: "리뷰 12건 중 5건이 설치를 좋게 말했어요", count: 5, denominator: 12, derivedFrom: [], check: null }],
      weaknesses: [{ key: "W:inquiry:사이즈", kind: "OBSERVED", source: "INQUIRY", title: "사이즈 문의 반복", basis: "문의 6건 중 3건이 사이즈를 물었어요", count: 3, denominator: 6, derivedFrom: [], check: null }],
      opportunities: [{ key: "O:설치", kind: "HYPOTHESIS", source: "REVIEW", title: "상세페이지에서 설치 강점을 고객 표현으로 앞세우기", basis: "…", count: 5, denominator: 12, derivedFrom: ["S:설치"], check: "문구를 바꾼 뒤 비교해 보세요" }],
      threats: [],
      insufficient: null,
    },
  };
}

const at = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe("AnalysisBody", () => {
  it("separates observations from hypotheses and names the observation a hypothesis came from", async () => {
    const { container } = at(<AnalysisBody view={view()} />);
    const strengths = screen.getByRole("region", { name: "강점 (관찰)" });
    expect(within(strengths).getByText("리뷰 12건 중 5건이 설치를 좋게 말했어요")).toBeTruthy();
    const opp = screen.getByRole("region", { name: "기회 (가설)" });
    expect(within(opp).getByText(/근거 관찰:/).textContent).toBe("근거 관찰: 설치 칭찬");
    expect(within(opp).getByText(/확인 방법:/)).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "위협 (가설)" })).getByText("근거가 충분한 항목이 없어요.")).toBeTruthy();
    await expectNoAxeViolations(container);
  });

  it("states the scope: period, sample, unlinked rows left out, rule-based method, rating not used", () => {
    at(<AnalysisBody view={view()} />);
    const scope = screen.getByTestId("analysis-scope");
    expect(scope.textContent).toContain("리뷰 12건 · 문의 6건");
    expect(scope.textContent).toContain("상품이 연결되지 않은 리뷰 3건 · 문의 0건은 이 상품 분석에 들어가지 않았어요");
    // the (미지정 상품) bucket is its own sentence, not summed into the unlinked one
    expect(scope.textContent).toContain("「(미지정 상품)」으로 들어온 리뷰 0건 · 문의 4건은");
    expect(scope.textContent).toContain("모델 분석 아님");
    expect(scope.textContent).toContain("별점은 강점·약점 판단에 쓰지 않았어요");
    expect(scope.textContent).toContain("경쟁사·시장 자료는 없어");
    expect(scope.textContent).not.toContain("최근");
  });

  it("says when a read was capped, and for the store which channels brought nothing in", () => {
    at(
      <AnalysisBody
        view={view({
          range: "STORE",
          inquiriesCapped: true,
          inquiries: 5200,
          inquiriesRead: 5000,
          unlinkedIncluded: true,
          gaps: [
            { channelCode: "COUPANG", everHadRows: true },
            { channelCode: "CAFE24", everHadRows: false },
          ],
          channels: [{ channelCode: "NAVER", reviews: 12, inquiries: 5200, reviewsRead: 12, inquiriesRead: 5000 }],
        })}
      />,
    );
    const scope = screen.getByTestId("analysis-scope").textContent ?? "";
    expect(scope).toContain("문의가 많아 최근 5,000건만 읽었어요");
    expect(scope).toContain("(이번에 읽은 것 리뷰 12 · 문의 5,000)");
    expect(scope).toContain("가게 전체에 포함했어요");
    expect(scope).toContain("이 기간 쿠팡에서 들어온 리뷰·문의가 없어요");
    expect(scope).toContain("카페24은(는) 리뷰·문의가 들어온 적이 없어요");
  });

  it("an observation's title opens the row its count came from; a hypothesis's observation does too", () => {
    window.requestAnimationFrame = (cb: FrameRequestCallback) => {
      cb(0);
      return 0;
    };
    at(<AnalysisBody view={view()} />);
    fireEvent.click(screen.getByRole("button", { name: "설치 칭찬: 근거 문장 열기" }));
    expect(screen.getByRole("button", { name: /설치: 불만 1건, 칭찬 5건/ }).getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("link", { name: "「조립이 쉬워요」" })).toBeTruthy();
    expect(document.activeElement?.getAttribute("aria-label")).toMatch(/^설치: 불만/);
    fireEvent.click(screen.getByRole("button", { name: "사이즈 문의 반복: 근거 문장 열기" }));
    expect(screen.getByRole("link", { name: "「가로 길이 알려주세요」" })).toBeTruthy();
    // 근거 관찰 under a hypothesis is the same door
    fireEvent.click(screen.getByRole("button", { name: /설치: 불만 1건/ }));
    fireEvent.click(within(screen.getByRole("region", { name: "기회 (가설)" })).getByRole("button", { name: "설치 칭찬" }));
    expect(screen.getByRole("button", { name: /설치: 불만 1건, 칭찬 5건/ }).getAttribute("aria-expanded")).toBe("true");
  });

  it("scope details are folded under one control, never removed; warnings stay in view", () => {
    at(<AnalysisBody view={view({ reviewsCapped: true, reviewsRead: 2000, reviews: 2400 })} />);
    const scope = screen.getByTestId("analysis-scope");
    const folded = scope.querySelector("details");
    expect(folded?.textContent).toContain("읽는 방법");
    expect(folded?.textContent).toContain("별점(참고만)");
    expect(folded?.hasAttribute("open")).toBe(false);
    // the cap warning is outside the fold
    const warning = within(scope).getByText(/리뷰가 많아 최근 2,000건만 읽었어요/);
    expect(folded?.contains(warning)).toBe(false);
  });

  it("an aspect row opens the sentences it counted, each linking to its review", () => {
    at(<AnalysisBody view={view()} />);
    const row = screen.getByRole("button", { name: /설치: 불만 1건, 칭찬 5건/ });
    expect(row.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(row);
    expect(screen.getByRole("link", { name: "「조립이 쉬워요」" }).getAttribute("href")).toBe("/reviews/reply/r1");
    expect(screen.getByRole("link", { name: /반복 문제 화면에서/ }).getAttribute("href")).toBe("/memory/i1");
    fireEvent.click(screen.getByRole("button", { name: /사이즈 문의 3건/ }));
    expect(screen.getByRole("link", { name: "「가로 길이 알려주세요」" }).getAttribute("href")).toBe("/inquiries/q1");
  });
});

describe("ComparisonChart", () => {
  const data: ProductComparison = {
    from: "2026-08-19",
    to: "2026-11-16",
    days: 90,
    reviewsRead: 30,
    reviewsCapped: false,
    unlinkedReviews: 2,
    unlinkedInquiries: 0,
    unspecifiedReviews: 0,
    unspecifiedInquiries: 66,
    minReviews: 5,
    method: { kind: "RULE_BASED", complaintRules: "", praiseRules: "", inquiryRules: "" },
    products: [
      { productId: "p1", name: "[합성] 원목 선반", reviews: 20, praiseReviews: 10, complaintReviews: 4, inquiries: 6, enough: true },
      { productId: "p2", name: "[합성] 텀블러", reviews: 8, praiseReviews: 2, complaintReviews: 3, inquiries: 1, enough: true },
      { productId: "p3", name: "[합성] 수건", reviews: 2, praiseReviews: 1, complaintReviews: 0, inquiries: 9, enough: false },
    ],
  };

  it("plots only products with enough reviews, keeps inquiries in the table, and marks this product", async () => {
    const { container } = at(<ComparisonChart data={data} currentId="p1" />);
    // two plotted marks (the thin product is in the table only)
    expect(container.querySelectorAll("svg g.cursor-pointer")).toHaveLength(2);
    expect(screen.getByText(/그래프에서 뺀 상품 1개/)).toBeTruthy();
    expect(screen.getByText(/「\(미지정 상품\)」으로 들어온 리뷰 0건 · 문의 66건은 상품이 아니라서/)).toBeTruthy();
    expect(screen.getByRole("columnheader", { name: "문의 (별도 분모)" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "[합성] 원목 선반" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getAllByText("표본 적음")).toHaveLength(2);
    await expectNoAxeViolations(container);
  });

  it("a large catalogue is shown in steps; hidden rows are counted, not shown as zero", () => {
    const many: ProductComparison = {
      ...data,
      products: Array.from({ length: 45 }, (_, i) => ({
        productId: `x${i}`,
        name: `상품 ${i}`,
        reviews: 100 - i,
        praiseReviews: 10,
        complaintReviews: 5,
        inquiries: 1,
        enough: true,
      })),
    };
    const { container } = at(<ComparisonChart data={many} currentId="x40" />);
    expect(container.querySelectorAll("tbody tr")).toHaveLength(20);
    expect(screen.getByText("상품 45개 중 20개 표시")).toBeTruthy();
    // 20 most-reviewed plus this product, even though it ranks 41st
    expect(container.querySelectorAll("svg g.cursor-pointer")).toHaveLength(21);
    expect(screen.getByText(/나머지 24개는 표에 있어요/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "20개 더 보기 (남은 25개)" }));
    expect(container.querySelectorAll("tbody tr")).toHaveLength(40);
  });

  it("keyboard reaches each point with its exact counts", () => {
    at(<ComparisonChart data={data} />);
    const svg = screen.getByRole("img");
    fireEvent.keyDown(svg, { key: "ArrowRight" });
    expect(screen.getByRole("tooltip").textContent).toContain("리뷰 20건 · 칭찬 10건 (50%) · 불만 4건 (20%)");
    fireEvent.keyDown(svg, { key: "ArrowRight" });
    expect(screen.getByRole("tooltip").textContent).toContain("[합성] 텀블러");
  });
});
