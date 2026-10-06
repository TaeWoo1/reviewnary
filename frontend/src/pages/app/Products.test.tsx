// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { Products } from "./Products";
import { expectNoAxeViolations } from "../../test/axe";
import type { ProductSignalsView, ProductSummaryView } from "../../lib/types";

/**
 * 상품 목록 — the record page's index (상품 목록 canonical, 2026-10-05).
 *
 * <p>여기 고정되는 것은 픽셀이 아니라 목록의 계약이다: 열 이름은 상세가 그 객체를 부르는 이름과 같고,
 * 강조는 답변 대기 하나뿐이며, 0은 빈 칸이고 읽지 못한 것은 0이 아니라 「—」이다. 리스팅이 둘 이상이면
 * 채널 하나를 골라 적지 않는다. 화면은 자기가 어떤 순서로 몇 개를 보여 주는지 스스로 말한다.
 */

const getProductCatalogStrict = vi.fn();
const searchProductsStrict = vi.fn();
const getProductSignalsStrict = vi.fn();
const listProductKnowledgeSources = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getProductCatalogStrict: (limit: number) => getProductCatalogStrict(limit),
    searchProductsStrict: (q: string, limit: number) => searchProductsStrict(q, limit),
    getProductSignalsStrict: (id: string) => getProductSignalsStrict(id),
    listProductKnowledgeSources: (id: string) => listProductKnowledgeSources(id),
  },
  getToken: () => "token",
}));

vi.mock("../../components/ui/AgentLaunch", () => ({
  AgentLaunch: ({ label }: { label: string }) => <button type="button">{label}</button>,
}));

function product(over: Partial<ProductSummaryView> = {}): ProductSummaryView {
  return { id: "p1", name: "선바로 전선몰딩", sku: "6473457702", status: "ACTIVE", matchedOn: null, matchedName: null, ...over };
}

function signals(over: Partial<ProductSignalsView["volume"]> = {}, channels: string[] = ["NAVER"]): ProductSignalsView {
  return {
    productId: "p1",
    productName: null,
    sku: null,
    referenceDate: "2026-10-05",
    issues: [],
    recommendedActions: [],
    volume: { reviews: 0, inquiries: 0, unansweredInquiries: 0, issueEvidence: 0, ...over },
    linkedChannels: channels,
    coverage: [],
  };
}

/** The catalogue read, its per-row facts, and the total the org really holds. */
function seed(
  rows: ProductSummaryView[],
  facts: Record<string, { sig?: ProductSignalsView; sources?: number | "fail" }>,
  total = rows.length,
) {
  getProductCatalogStrict.mockResolvedValue({ total, rows });
  getProductSignalsStrict.mockImplementation((id: string) => {
    const f = facts[id];
    return f?.sig ? Promise.resolve(f.sig) : Promise.reject(new Error("signals"));
  });
  listProductKnowledgeSources.mockImplementation((id: string) => {
    const s = facts[id]?.sources;
    return s === "fail" || s === undefined ? Promise.reject(new Error("sources")) : Promise.resolve(Array(s).fill({}));
  });
}

function draw() {
  render(
    <MemoryRouter>
      <Products />
    </MemoryRouter>,
  );
}

/** The row a product's name sits in. */
async function row(name: string) {
  return (await screen.findByText(name)).closest("li") as HTMLElement;
}

afterEach(() => {
  vi.clearAllMocks();
});

describe("상품 목록 — 한 상품을 여는 색인", () => {
  it("열 이름이 상세가 그 객체를 부르는 이름과 같다 — 「답변 기준」은 더 이상 없다", async () => {
    seed([product()], { p1: { sig: signals({ reviews: 10 }), sources: 2 } });
    draw();
    await screen.findByText("선바로 전선몰딩");

    for (const label of ["답변 대기", "문제 근거", "문의", "리뷰", "상품 지식"]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
    // 같은 객체가 목록에서는 「답변 기준」, 상세에서는 「상품 지식」이던 두 이름이 하나로 모였다.
    expect(document.body.textContent).not.toContain("답변 기준");
  });

  it("행 전체가 상품을 여는 하나의 컨트롤 — 옆에 [열기]를 또 두지 않는다", async () => {
    seed([product()], { p1: { sig: signals(), sources: 0 } });
    draw();
    const r = await row("선바로 전선몰딩");
    const links = within(r).getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "/products/p1");
  });

  it("답변 대기만 한 단계 위에 선다 — 나머지 수량은 사실로 선다", async () => {
    seed([product()], { p1: { sig: signals({ reviews: 1761, inquiries: 10, unansweredInquiries: 3, issueEvidence: 42 }), sources: 5 } });
    draw();
    const r = await row("선바로 전선몰딩");

    const owed = within(r).getByText("3");
    expect(owed.className).toContain("text-warn");
    // 같은 행의 다른 수량 어느 것도 경고색을 쓰지 않는다.
    for (const value of ["42", "10", "1,761", "5"]) {
      expect(within(r).getByText(value).className).not.toContain("text-warn");
    }
  });

  it("측정된 0은 0이라고 적고, 읽지 못한 것은 「—」다", async () => {
    // 전에는 0이 빈 칸이었다. 빈 칸과 「—」가 한 표에 나란히 서면 빈 칸은 「값이 없다」로도 「아직
    // 모른다」로도 읽히는데, 그 둘은 이 제품이 가장 분명히 갈라 놓기로 한 두 가지다.
    seed([product()], { p1: { sig: signals({ reviews: 416, issueEvidence: 2 }), sources: "fail" } });
    draw();
    const r = await row("선바로 전선몰딩");

    // 답변 대기 0 · 문의 0 — 센 0이고, 그렇게 적힌다. 가장 약한 잉크로.
    const zeros = within(r).getAllByText("0");
    expect(zeros).toHaveLength(2);
    for (const zero of zeros) {
      expect(zero.className).toContain("text-muted");
      expect(zero.className).not.toContain("text-ink");
    }
    expect(within(r).getByText("답변 대기")).toBeInTheDocument();
    // 상품 지식은 읽지 못했다. 못 읽은 수량은 끝까지 0이라고 적지 않는다.
    expect(within(r).getByText("—")).toBeInTheDocument();
    expect(within(r).getByText("상품 지식 읽지 못했습니다")).toBeInTheDocument();
  });

  it("채널과 상품코드는 수량이 아니라 신원이라 이름 아래에 선다", async () => {
    seed([product()], { p1: { sig: signals({}, ["CAFE24"]), sources: 0 } });
    draw();
    const r = await row("선바로 전선몰딩");
    expect(within(r).getByText(/카페24/)).toBeInTheDocument();
    expect(within(r).getByText("6473457702")).toBeInTheDocument();
  });

  it("리스팅이 둘 이상이면 하나를 골라 적지 않고 몇 개인지 말한다", async () => {
    seed([product()], { p1: { sig: signals({}, ["NAVER", "COUPANG"]), sources: 0 } });
    draw();
    const r = await row("선바로 전선몰딩");
    expect(within(r).getByText(/2개 채널/)).toBeInTheDocument();
    expect(within(r).queryByText(/네이버/)).toBeNull();
    expect(within(r).queryByText(/쿠팡/)).toBeNull();
  });

  it("운영 정보를 읽지 못한 행은 숫자를 하나도 그리지 않고 그렇다고 말한다", async () => {
    seed([product()], { p1: {} });
    draw();
    const r = await row("선바로 전선몰딩");
    expect(within(r).getByText("운영 정보를 읽지 못했습니다")).toBeInTheDocument();
    expect(within(r).queryByText("0")).toBeNull();
  });

  it("서버가 보낸 순서를 그대로 그린다 — 화면은 카탈로그를 다시 줄 세우지 않는다", async () => {
    // 일부러 「무게 순이 아닌」 순서로 보낸다. 화면이 제 규칙으로 다시 정렬하면 이 순서가 뒤집힌다.
    // 그 재정렬이 바로, 어느 20개가 페이지에 오르는지와 그 순서를 서로 다른 수량이 정하게 만든 원인이었다.
    seed(
      [
        product({ id: "quiet", name: "가 아무 일도 없는 상품", sku: "1" }),
        product({ id: "owed", name: "나 답변을 기다리는 상품", sku: "2" }),
        product({ id: "loud", name: "다 리뷰가 몰린 상품", sku: "3" }),
      ],
      {
        quiet: { sig: signals(), sources: 0 },
        owed: { sig: signals({ unansweredInquiries: 9, inquiries: 9 }), sources: 0 },
        loud: { sig: signals({ reviews: 1761, issueEvidence: 42 }), sources: 0 },
      },
    );
    draw();
    await screen.findByText("다 리뷰가 몰린 상품");

    const order = Array.from(document.querySelectorAll('[aria-label="상품 목록"] li a')).map((a) =>
      a.getAttribute("href"),
    );
    expect(order).toEqual(["/products/quiet", "/products/owed", "/products/loud"]);
  });

  it("찾은 결과도 다시 줄 세우지 않는다 — 가장 가까운 이름이 먼저다", async () => {
    seed([product()], { p1: { sig: signals(), sources: 0 } }, 294);
    searchProductsStrict.mockResolvedValue([
      product({ id: "near", name: "종이컵보관함", sku: "10" }),
      product({ id: "far", name: "종이컵보관함 수거함 디스펜서", sku: "11" }),
    ]);
    getProductSignalsStrict.mockImplementation((id: string) =>
      Promise.resolve(id === "far" ? signals({ unansweredInquiries: 7 }) : signals()),
    );
    listProductKnowledgeSources.mockResolvedValue([]);
    draw();
    await screen.findByText("선바로 전선몰딩");

    await userEvent.type(screen.getByLabelText("상품 이름 또는 상품코드로 검색"), "종이컵");
    await screen.findByText("종이컵보관함 수거함 디스펜서");

    // 답변 대기 7건이 두 번째 행에 그대로 있다. 무게로 다시 세웠다면 첫 행이었을 것이다.
    const order = Array.from(document.querySelectorAll('[aria-label="상품 목록"] li a')).map((a) =>
      a.getAttribute("href"),
    );
    expect(order).toEqual(["/products/near", "/products/far"]);
  });

  it("화면이 자기 순서와 범위를 스스로 말한다", async () => {
    seed([product(), product({ id: "p2", name: "세모금컵", sku: "24" })], {
      p1: { sig: signals({ unansweredInquiries: 3 }), sources: 0 },
      p2: { sig: signals(), sources: 0 },
    }, 294);
    draw();
    await screen.findByText("세모금컵");

    // 정렬 기준은 목록 위에, 범위는 목록 아래에. 둘 다 같은 상자 안이다.
    // 적힌 순서는 서버가 카탈로그 전체를 줄 세운 바로 그 순서다 — ProductCatalogOrderingContractTest.
    expect(screen.getByText(/→ 문제 근거 → 리뷰 순/)).toBeInTheDocument();
    const extent = screen.getByText(/를 보고 있습니다/);
    expect(extent.textContent).toContain("294개");
    expect(extent.textContent).toContain("2개");
  });

  it("카탈로그 전부가 페이지에 있으면 범위를 말하지 않는다", async () => {
    seed([product()], { p1: { sig: signals(), sources: 0 } });
    draw();
    await screen.findByText("선바로 전선몰딩");
    expect(screen.queryByText(/를 보고 있습니다/)).toBeNull();
  });

  it("찾는 중에는 순서를 말하지 않는다 — 그 행들은 다른 질문의 답이다", async () => {
    seed([product()], { p1: { sig: signals(), sources: 0 } }, 294);
    searchProductsStrict.mockResolvedValue([product({ id: "p9", name: "종이컵보관함" })]);
    getProductSignalsStrict.mockResolvedValue(signals({ reviews: 416 }));
    listProductKnowledgeSources.mockResolvedValue([]);
    draw();
    await screen.findByText("선바로 전선몰딩");

    await userEvent.type(screen.getByLabelText("상품 이름 또는 상품코드로 검색"), "종이컵");
    await screen.findByText("종이컵보관함");
    expect(screen.queryByText(/→ 문제 근거 → 리뷰 순/)).toBeNull();
  });

  it("찾았는데 없을 때는 찾는 칸을 남긴다 — 고칠 말이 그 안에 있다", async () => {
    seed([product()], { p1: { sig: signals(), sources: 0 } });
    searchProductsStrict.mockResolvedValue([]);
    draw();
    await screen.findByText("선바로 전선몰딩");

    await userEvent.type(screen.getByLabelText("상품 이름 또는 상품코드로 검색"), "없는상품");
    await screen.findByText("찾는 상품이 없습니다");
    expect(screen.getByLabelText("상품 이름 또는 상품코드로 검색")).toBeInTheDocument();
  });

  it("상품이 하나도 없으면 비어 있음이 곧 화면이다 — 찾을 것이 없는 자리에 찾는 칸을 두지 않는다", async () => {
    seed([], {}, 0);
    draw();
    await screen.findByText("아직 상품이 없습니다");
    expect(screen.queryByLabelText("상품 이름 또는 상품코드로 검색")).toBeNull();
  });

  it("접근성 위반이 없다", async () => {
    seed([product(), product({ id: "p2", name: "세모금컵", sku: "24" })], {
      p1: { sig: signals({ reviews: 1761, inquiries: 10, unansweredInquiries: 3, issueEvidence: 42 }), sources: 3 },
      p2: { sig: signals({ reviews: 72 }), sources: "fail" },
    }, 294);
    const { container } = render(
      <MemoryRouter>
        <Products />
      </MemoryRouter>,
    );
    await screen.findByText("세모금컵");
    await expectNoAxeViolations(container);
  });
});
