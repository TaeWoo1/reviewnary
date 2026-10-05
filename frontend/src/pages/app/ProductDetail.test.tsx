// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ProductDetail } from "./ProductDetail";
import { expectNoAxeViolations } from "../../test/axe";
import type {
  InquiryRowItem,
  ProductKnowledgeView,
  ProductListingView,
  ReviewIssueView,
} from "../../lib/types";

/**
 * 상품 상세 — record page (상품 상세 canonical, 2026-10-05).
 *
 * <p>여기 고정되는 것은 픽셀이 아니라 레코드 화면의 계약이다: 숫자는 자기를 센 술어와 같은 술어로 좁혀진
 * 목록을 열고, 0은 문이 되지 않으며, 레일은 값이 하나일 때만 값을 말하고, 읽지 못한 것은 0건이라고 적지
 * 않는다. 기술 출처는 어느 자리에서도 판매자에게 보이지 않는다.
 */

const getProductKnowledgeStrict = vi.fn();
const getKnowledgeDocuments = vi.fn();
const getKnowledgeCandidates = vi.fn();
const listProductKnowledgeSources = vi.fn();
const getOpportunitiesStrict = vi.fn();
const getInquiryRowsStrict = vi.fn();
const getProductReviews = vi.fn();
const getReviewIssueDetailStrict = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getProductKnowledgeStrict: (id: string) => getProductKnowledgeStrict(id),
    getKnowledgeDocuments: (id?: string) => getKnowledgeDocuments(id),
    getKnowledgeCandidates: () => getKnowledgeCandidates(),
    listProductKnowledgeSources: (id: string) => listProductKnowledgeSources(id),
    getOpportunitiesStrict: (o: unknown) => getOpportunitiesStrict(o),
    getInquiryRowsStrict: (p: unknown) => getInquiryRowsStrict(p),
    getProductReviews: (id: string, o: unknown) => getProductReviews(id, o),
    getReviewIssueDetailStrict: (id: string) => getReviewIssueDetailStrict(id),
  },
  getToken: () => "token",
}));

function issue(over: Partial<ReviewIssueView> = {}): ReviewIssueView {
  return {
    id: "issue-1",
    title: "접착 탈락",
    aspect: "접착",
    problem: "탈락",
    severity: "MODERATE",
    lifecycleState: "OBSERVING",
    lifecycleLabelKo: "지켜보는 중",
    evidenceCount: 19,
    firstEvidenceOn: "2026-01-02",
    lastEvidenceOn: "2026-08-18",
    dominantProductId: "p-1",
    dominantProductName: "선바로 일체형 전선몰딩",
    dismissed: false,
    extractorKind: "RULE",
    change: { kinds: [], labelsKo: [], highSurge: false, surgeWindowCount: 0, surgeBaselineWeekly: 0 },
    ...over,
  } as ReviewIssueView;
}

function listing(over: Partial<ProductListingView> = {}): ProductListingView {
  return {
    channelCode: "NAVER",
    channelNameKo: "네이버 스마트스토어",
    channelProductId: "6473457702",
    listingName: "선바로 일체형 전선몰딩",
    productUrl: null,
    price: 1800,
    currency: "KRW",
    sellingStatus: "SELLING",
    source: "NAVER:PRODUCT_API:v1",
    observedAt: "2026-09-05T10:23:48.393251Z",
    sourceUpdatedAt: "2026-07-22T06:35:54.389Z",
    ...over,
  } as ProductListingView;
}

function view(
  over: Partial<ProductKnowledgeView["signals"]["volume"]> = {},
  issues: ReviewIssueView[] = [issue()],
  rest: Partial<ProductKnowledgeView> = {},
): ProductKnowledgeView {
  return {
    productId: "p-1",
    name: "선바로 일체형 전선몰딩",
    sku: "6473457702",
    status: "ACTIVE",
    listings: [listing()],
    variants: [],
    facts: [
      { factKey: "taxonomy:brand", value: "선바로", unit: null, source: "NAVER:PRODUCT_API:v1", sourceRef: "x", observedAt: "2026-09-05T10:23:48Z", confidence: "SOURCE_STATED" },
      { factKey: "taxonomy:category", value: "가구/인테리어>DIY자재/용품>몰딩", unit: null, source: "NAVER:PRODUCT_API:v1", sourceRef: "x", observedAt: "2026-09-05T10:23:48Z", confidence: "SOURCE_STATED" },
    ],
    signals: {
      productId: "p-1",
      productName: "선바로 일체형 전선몰딩",
      sku: "6473457702",
      referenceDate: "2026-09-04",
      issues,
      recommendedActions: [],
      volume: { reviews: 1761, inquiries: 8, unansweredInquiries: 1, issueEvidence: 80, ...over },
      linkedChannels: ["NAVER"],
      coverage: [{ signal: "REVIEW", coverage: "COVERED", linked: 1761, unlinked: 0, provenance: "x" }],
    },
    knowledgeCoverage: [
      { facet: "IDENTITY", coverage: "AVAILABLE", known: 2, newestObservedAt: null, provenance: "products", statable: true },
      { facet: "VARIANT", coverage: "UNAVAILABLE", known: 0, newestObservedAt: null, provenance: "", statable: false },
    ],
    ...rest,
  } as unknown as ProductKnowledgeView;
}

function inquiryRow(over: Partial<InquiryRowItem> = {}): InquiryRowItem {
  return {
    inquiryId: "inq-1",
    workItemId: "w-1",
    sellerAccountId: "a-1",
    channelId: "c-1",
    channelCode: "NAVER",
    channelNameKo: "네이버 스마트스토어",
    productId: "p-1",
    productName: "선바로 일체형 전선몰딩",
    phase: "OPEN",
    status: "UNANSWERED",
    title: "언제 발송하나요?",
    snippet: "가게 오픈준비중이라 빨리와야지 오픈 하는데",
    receivedAt: "2026-09-04T23:31:31Z",
    answeredAt: null,
    sourceSubtype: null,
    executableIdentity: "MARKETPLACE",
    ...over,
  };
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={["/products/p-1"]}>
      <Routes>
        <Route path="/products/:productId" element={<ProductDetail />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  getProductKnowledgeStrict.mockResolvedValue(view());
  getKnowledgeDocuments.mockResolvedValue([]);
  getKnowledgeCandidates.mockResolvedValue([]);
  listProductKnowledgeSources.mockResolvedValue([]);
  getOpportunitiesStrict.mockResolvedValue([]);
  getInquiryRowsStrict.mockResolvedValue({ totalCount: 1, items: [inquiryRow()] });
  getProductReviews.mockResolvedValue({
    productId: "p-1",
    productName: "선바로 일체형 전선몰딩",
    total: 1761,
    page: 0,
    size: 3,
    items: [
      { id: "r-1", sellerAccountId: "a-1", channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", writtenOn: "2026-09-02", rating: 5, negative: false, preview: "선 정리가 너무 편합니다", productId: "p-1", productName: "선바로", replyState: "PENDING", executableIdentity: "MARKETPLACE" },
    ],
  });
  getReviewIssueDetailStrict.mockResolvedValue({
    issue: issue(),
    evidence: [
      { reviewId: "rev-old", unitOrdinal: 0, occurredOn: "2026-02-01", productId: "p-1", productName: "선바로", rating: 4, quote: "오래된 문장" },
      { reviewId: "rev-new", unitOrdinal: 0, occurredOn: "2026-08-18", productId: "p-1", productName: "선바로", rating: 3, quote: "자꾸 떨어져서 실리콘 접착제 바르니" },
      { reviewId: "rev-other", unitOrdinal: 0, occurredOn: "2026-09-01", productId: "p-9", productName: "다른 상품", rating: 2, quote: "다른 상품의 문장" },
    ],
    history: [],
  });
});

afterEach(() => vi.clearAllMocks());

describe("상품 상세 — 수량 띠는 자기를 센 목록을 연다", () => {
  it("리뷰·문의·답변 대기가 이 상품으로 좁혀진 목록으로 간다", async () => {
    renderDetail();
    expect(await screen.findByRole("link", { name: "리뷰 1,761건 보기" })).toHaveAttribute(
      "href",
      "/reviews?productId=p-1",
    );
    expect(screen.getByRole("link", { name: "문의 8건 보기" })).toHaveAttribute(
      "href",
      "/inquiries?productId=p-1",
    );
    expect(screen.getByRole("link", { name: "답변 대기 1건 보기" })).toHaveAttribute(
      "href",
      "/inquiries?productId=p-1&status=UNANSWERED",
    );
  });

  it("0은 사실이지 컨트롤이 아니다", async () => {
    getProductKnowledgeStrict.mockResolvedValue(view({ reviews: 0, inquiries: 0, unansweredInquiries: 0 }));
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "선바로 일체형 전선몰딩" });
    expect(screen.queryByRole("link", { name: /리뷰 0건 보기/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /문의 0건 보기/ })).toBeNull();
  });

  it("읽지 못한 수는 0으로 적지 않는다", async () => {
    listProductKnowledgeSources.mockRejectedValue(new Error("down"));
    renderDetail();
    const strip = await screen.findByRole("navigation", { name: "이 상품이 가진 것" });
    expect(strip.textContent).not.toContain("상품 지식");
    // 자료는 읽혔으므로 0이 사실로 선다.
    expect(strip.textContent).toContain("자료");
  });
});

describe("상품 상세 — 레일은 값이 하나일 때만 값을 말한다", () => {
  it("리스팅이 하나면 채널·가격·판매 상태·최근 확인이 레일에 선다", async () => {
    renderDetail();
    const rail = await screen.findByRole("region", { name: "상품 정보" });
    expect(rail.textContent).toContain("네이버 스마트스토어");
    expect(rail.textContent).toContain("1,800원");
    expect(rail.textContent).toContain("판매중");
    expect(rail.textContent).toContain("2026-09-05");
    expect(screen.queryByRole("region", { name: "채널 리스팅" })).toBeNull();
  });

  it("리스팅이 둘이면 레일을 떠나 본문 섹션으로 올라간다", async () => {
    getProductKnowledgeStrict.mockResolvedValue(
      view({}, [issue()], {
        listings: [listing(), listing({ channelCode: "CAFE24", channelNameKo: "카페24 자사몰", price: 2000 })],
      }),
    );
    renderDetail();
    const section = await screen.findByRole("region", { name: "채널 리스팅" });
    expect(section.textContent).toContain("카페24 자사몰");
    expect(section.textContent).toContain("2,000원");
    const rail = screen.getByRole("region", { name: "상품 정보" });
    expect(rail.textContent).not.toContain("1,800원");
    expect(rail.textContent).not.toContain("판매중");
  });

  it("옵션이 하나라도 있으면 본문 섹션이 된다", async () => {
    getProductKnowledgeStrict.mockResolvedValue(
      view({}, [issue()], {
        variants: [
          { id: "v-1", channelCode: "NAVER", externalVariantId: "x", optionName: "2호 그레이", sku: "SB-2", price: 2500, sellingStatus: "SELLING", source: "x", observedAt: null },
        ],
      }),
    );
    renderDetail();
    const section = await screen.findByRole("region", { name: "옵션" });
    expect(section.textContent).toContain("2호 그레이");
  });

  it("기술 출처는 어디에도 보이지 않는다", async () => {
    renderDetail();
    await screen.findByRole("region", { name: "상품 정보" });
    expect(document.body.textContent).not.toContain("PRODUCT_API");
    expect(document.body.textContent).not.toContain("NAVER:");
  });
});

describe("상품 상세 — 지금 사람을 기다리는 것이 맨 위에 있다", () => {
  it("미답변만, 이 상품만, 세 줄만 읽는다", async () => {
    renderDetail();
    await screen.findByRole("region", { name: "답변을 기다리는 문의" });
    expect(getInquiryRowsStrict).toHaveBeenCalledWith({
      productId: "p-1",
      status: "UNANSWERED",
      limit: 3,
    });
  });

  it("행은 그 문의를 연다", async () => {
    renderDetail();
    const section = await screen.findByRole("region", { name: "답변을 기다리는 문의" });
    const row = within(section).getByRole("link", { name: /언제 발송하나요/ });
    expect(row).toHaveAttribute("href", "/inquiries/inq-1");
  });

  it("읽지 못했으면 아무 말도 하지 않는다", async () => {
    getInquiryRowsStrict.mockRejectedValue(new Error("down"));
    renderDetail();
    await screen.findByRole("region", { name: "반복되는 문제" });
    expect(screen.queryByRole("region", { name: "답변을 기다리는 문의" })).toBeNull();
  });

  it("세 줄보다 많으면 몇 건이 남았는지 말한다", async () => {
    getProductKnowledgeStrict.mockResolvedValue(view({ unansweredInquiries: 7 }));
    renderDetail();
    expect(await screen.findByText("답변 대기 6건 더 보기")).toBeInTheDocument();
  });
});

describe("상품 상세 — 반복되는 문제는 제 근거로 이어진다", () => {
  it("행은 문제 화면을 열고, 좁혀진 수가 무엇으로 좁혀졌는지 말한다", async () => {
    renderDetail();
    const row = await screen.findByRole("link", { name: /접착 탈락/ });
    expect(row).toHaveAttribute("href", "/memory/issue-1");
    expect(row).toHaveTextContent("이 상품에서 19건");
    // 상태와 기간은 행이 말한다 — 열어 봐야 아는 것이 아니다.
    expect(row).toHaveTextContent("지켜보는 중");
    expect(row).toHaveTextContent("2026.01~2026.08");
  });

  it("다섯에서 말없이 멈추지 않는다", async () => {
    const many = Array.from({ length: 8 }, (_, i) => issue({ id: `issue-${i}`, title: `문제 ${i}` }));
    getProductKnowledgeStrict.mockResolvedValue(view({}, many));
    renderDetail();
    expect(await screen.findByText("문제 3건 더 보기")).toBeInTheDocument();
  });

  it("반복된 것이 없으면 그렇게 말한다", async () => {
    getProductKnowledgeStrict.mockResolvedValue(view({ issueEvidence: 0 }, []));
    renderDetail();
    expect(await screen.findByText("이 상품에서 반복 문제로 잡힌 것이 없습니다.")).toBeInTheDocument();
  });
});

describe("상품 상세 — 근거가 된 문장", () => {
  it("이 상품의 문장 가운데 가장 최근 것 하나를 문제마다 보여 준다", async () => {
    renderDetail();
    const section = await screen.findByRole("region", { name: "문제의 근거가 된 문장" });
    expect(section.textContent).toContain("자꾸 떨어져서 실리콘 접착제 바르니");
    expect(section.textContent).not.toContain("오래된 문장");
    // 다른 상품의 근거는 이 상품의 화면에 설 수 없다.
    expect(section.textContent).not.toContain("다른 상품의 문장");
  });

  it("문장은 그 문장을 쓴 리뷰로 간다", async () => {
    renderDetail();
    const section = await screen.findByRole("region", { name: "문제의 근거가 된 문장" });
    expect(within(section).getByRole("link", { name: /이 리뷰 처리하기/ })).toHaveAttribute(
      "href",
      "/reviews/reply/rev-new",
    );
  });

  it("문제 읽기가 실패하면 섹션 자체가 서지 않는다", async () => {
    getReviewIssueDetailStrict.mockRejectedValue(new Error("down"));
    renderDetail();
    await screen.findByRole("region", { name: "반복되는 문제" });
    expect(screen.queryByRole("region", { name: "문제의 근거가 된 문장" })).toBeNull();
  });
});

describe("상품 상세 — 최근 리뷰", () => {
  it("세 줄과, 전체로 가는 문", async () => {
    renderDetail();
    const section = await screen.findByRole("region", { name: "최근 리뷰" });
    expect(section.textContent).toContain("선 정리가 너무 편합니다");
    expect(section.textContent).toContain("5점");
    expect(within(section).getByRole("link", { name: /리뷰 1,761건 모두 보기/ })).toHaveAttribute(
      "href",
      "/reviews?productId=p-1",
    );
  });

  it("읽지 못했으면 섹션이 서지 않는다", async () => {
    getProductReviews.mockRejectedValue(new Error("down"));
    renderDetail();
    await screen.findByRole("region", { name: "반복되는 문제" });
    expect(screen.queryByRole("region", { name: "최근 리뷰" })).toBeNull();
  });
});

describe("상품 상세 — 지식과 자료", () => {
  it("지식은 한 행 한 건이고, 인용할 수 없는 건 그렇게 말한다", async () => {
    listProductKnowledgeSources.mockResolvedValue([
      { id: "s-1", productId: "p-1", sourceType: "USAGE", title: "부착이 잘 떨어질 때 안내", body: "몰딩이 잘 떨어질 때는 부착면 상태를 먼저 확인해 주세요.", sourceUrl: null, authorName: "데모 운영자", chunks: 1, createdAt: "2026-09-03T06:33:53Z", updatedAt: "2026-09-03T06:33:53Z", authoredOrigin: "SELLER_ENTERED_KNOWLEDGE", variantId: null, variantName: null },
      { id: "s-2", productId: "p-1", sourceType: "FAQ", title: "인용 불가", body: "본문", sourceUrl: null, authorName: "데모 운영자", chunks: 0, createdAt: "2026-09-03T06:33:53Z", updatedAt: "2026-09-03T06:33:53Z", authoredOrigin: "SELLER_ENTERED_KNOWLEDGE", variantId: null, variantName: null },
    ]);
    renderDetail();
    const section = await screen.findByRole("region", { name: "상품 지식" });
    expect(section.textContent).toContain("부착이 잘 떨어질 때 안내");
    expect(section.textContent).toContain("AI가 인용할 수 없습니다");
  });

  it("자료는 수와 올리는 길을 한 줄로 말한다", async () => {
    renderDetail();
    expect(await screen.findByText(/자료 0건 —/)).toBeInTheDocument();
  });

  it("이 상품에 대해 확인이 필요한 것을 세고, 답하는 한 곳으로 보낸다", async () => {
    getKnowledgeCandidates.mockResolvedValue([
      { id: "c-1", scope: "PRODUCT", productId: "p-1", productName: "선바로", subject: "가닥", content: "", origin: "DRAFT_GAP", evidenceCount: 0, state: "OPEN", sourceId: null, createdAt: "2026-09-01T00:00:00Z" },
      { id: "c-2", scope: "PRODUCT", productId: "p-9", productName: "다른 상품", subject: "두께", content: "", origin: "DRAFT_GAP", evidenceCount: 0, state: "OPEN", sourceId: null, createdAt: "2026-09-01T00:00:00Z" },
    ]);
    renderDetail();
    expect(await screen.findByText(/확인이 필요한 항목이 1건 있습니다/)).toBeInTheDocument();
  });

  it("후보 읽기가 실패하면 0건이라고 말하지 않는다", async () => {
    getKnowledgeCandidates.mockRejectedValue(new Error("nope"));
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "선바로 일체형 전선몰딩" });
    expect(screen.queryByText(/확인이 필요한 항목/)).toBeNull();
  });
});

describe("상품 상세 — 개선 기회 (Opportunity Engine v1)", () => {
  it("줄로 서고, 문제의 화면을 연다", async () => {
    getOpportunitiesStrict.mockResolvedValue([
      {
        issueId: "issue-1", kind: "FAQ_SUPPLEMENT", kindLabelKo: "FAQ 보완", status: "OPEN", statusLabelKo: "검토 전",
        issueTitle: "접착 탈락", aspect: "접착", problem: "탈락", severity: "NORMAL", evidenceCount: 19,
        firstEvidenceOn: "2026-01-02", lastEvidenceOn: "2026-08-18", changeLabelsKo: [],
        productId: "p-1", productName: "선바로 일체형 전선몰딩",
        whyKo: ["「접착 탈락」 근거 리뷰 19건."], recommendationKo: "'접착' 관련 안내를 이 상품의 자주 묻는 질문에 추가하는 것을 검토하세요.",
        evidenceTo: "/memory/issue-1", knowledge: null, nextActionKo: "FAQ 초안 준비", draft: null, decidedAt: null,
      },
      {
        issueId: "issue-1", kind: "PRODUCT_IMPROVEMENT_REVIEW", kindLabelKo: "제품 개선 검토", status: "DISMISSED", statusLabelKo: "보류",
        issueTitle: "접착 탈락", aspect: "접착", problem: "탈락", severity: "NORMAL", evidenceCount: 19,
        firstEvidenceOn: null, lastEvidenceOn: null, changeLabelsKo: [], productId: "p-1", productName: null,
        whyKo: [], recommendationKo: "제품 자체를 검토하세요.", evidenceTo: "/memory/issue-1", knowledge: null,
        nextActionKo: "메모 준비", draft: null, decidedAt: "2026-09-04T00:00:00Z",
      },
    ]);
    renderDetail();
    const section = await screen.findByRole("region", { name: "개선 기회" });
    const row = section.querySelector("a") as HTMLAnchorElement;
    expect(row.getAttribute("href")).toBe("/memory/issue-1");
    expect(row.textContent).toContain("FAQ 보완");
    expect(row.textContent).toContain("자주 묻는 질문에 추가");
    // 보류한 것은 문제의 화면이 보여 줄 것이지 이 화면이 셀 것이 아니다.
    expect(section.textContent).not.toContain("제품 개선 검토");
  });

  it("없거나 읽지 못했으면 조용하다", async () => {
    getOpportunitiesStrict.mockRejectedValue(new Error("down"));
    renderDetail();
    await screen.findByRole("region", { name: "반복되는 문제" });
    expect(screen.queryByRole("region", { name: "개선 기회" })).toBeNull();
  });
});

describe("상품 상세 — accessibility", () => {
  it("has no axe violations", async () => {
    const { container } = renderDetail();
    await screen.findByRole("region", { name: "최근 리뷰" });
    await expectNoAxeViolations(container);
  });
});
