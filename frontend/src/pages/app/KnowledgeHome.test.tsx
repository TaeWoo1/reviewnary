// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { expectNoAxeViolations } from "../../test/axe";
import { KnowledgeHome } from "./KnowledgeHome";
import type {
  KnowledgeCandidateView,
  KnowledgeDocumentView,
  KnowledgeInventoryView,
  KnowledgeSummaryView,
} from "../../lib/types";

const getKnowledgeDocuments = vi.fn();
const getKnowledgeCandidates = vi.fn();
const getKnowledgeSummary = vi.fn();
const getKnowledgeInventory = vi.fn();
const proposeKnowledgeCandidates = vi.fn();
const acceptKnowledgeCandidate = vi.fn();
const dismissKnowledgeCandidate = vi.fn();
const setKnowledgeDocumentActive = vi.fn();
const importKnowledgeDocument = vi.fn();
const getProductKnowledgeStrict = vi.fn();
const getLearnedKnowledge = vi.fn();
const learnFromHistory = vi.fn();
vi.mock("../../lib/apiClient", () => ({
  api: {
    getKnowledgeDocuments: (...a: unknown[]) => getKnowledgeDocuments(...a),
    getKnowledgeCandidates: (...a: unknown[]) => getKnowledgeCandidates(...a),
    getKnowledgeSummary: (...a: unknown[]) => getKnowledgeSummary(...a),
    getKnowledgeInventory: (...a: unknown[]) => getKnowledgeInventory(...a),
    proposeKnowledgeCandidates: (...a: unknown[]) => proposeKnowledgeCandidates(...a),
    acceptKnowledgeCandidate: (...a: unknown[]) => acceptKnowledgeCandidate(...a),
    dismissKnowledgeCandidate: (...a: unknown[]) => dismissKnowledgeCandidate(...a),
    setKnowledgeDocumentActive: (...a: unknown[]) => setKnowledgeDocumentActive(...a),
    importKnowledgeDocument: (...a: unknown[]) => importKnowledgeDocument(...a),
    getProductKnowledgeStrict: (...a: unknown[]) => getProductKnowledgeStrict(...a),
    getLearnedKnowledge: (...a: unknown[]) => getLearnedKnowledge(...a),
    learnFromHistory: (...a: unknown[]) => learnFromHistory(...a),
  },
  getToken: () => null,
}));

const CANDIDATE: KnowledgeCandidateView = {
  id: "c-1",
  scope: "ORG",
  productId: null,
  productName: null,
  subject: "부착 전 표면의 먼지와 기름기를 제거해 주세요.",
  content: "부착 전 표면의 먼지와 기름기를 제거해 주세요.",
  origin: "REPEATED_ANSWER",
  evidenceCount: 18,
  state: "OPEN",
  sourceId: null,
  createdAt: "2026-09-03T00:00:00Z",
};

/** A drafting gap: its stored text is the QUESTION, and that is the whole point of this suite. */
const GAP: KnowledgeCandidateView = {
  id: "c-2",
  scope: "PRODUCT",
  productId: "p-1",
  productName: "실리콘 주방매트",
  subject: "미끄럼 방지",
  content: "「미끄럼 방지」에 대해 고객에게 안내할 공식 기준이 필요합니다.",
  origin: "DRAFT_GAP",
  evidenceCount: 0,
  state: "OPEN",
  sourceId: null,
  createdAt: "2026-09-03T00:00:00Z",
};

const DOCUMENT: KnowledgeDocumentView = {
  sourceId: "s-1",
  scope: "ORG",
  productId: null,
  productName: null,
  fileName: "제품 사용설명서.pdf",
  title: "제품 사용설명서",
  kind: "GENERAL_CS_FAQ",
  active: true,
  passages: 12,
  uploadedBy: "demo",
  uploadedAt: "2026-09-03T00:00:00Z",
};

const SUMMARY: KnowledgeSummaryView = {
  productKnowledge: 38,
  operatingRules: 6,
  documents: 4,
  pastAnswers: 23,
  products: 142,
  needsConfirmation: 1,
};

/**
 * 서버가 보낸 순서 그대로의 한 판 — 일부러 「틀려 보이게」 2건 쓰인 것이 21건 쓰인 것보다 앞에 있다.
 * 화면이 같은 기준으로 다시 줄을 세우면 이 fixture에서 바로 깨진다.
 */
const INVENTORY: KnowledgeInventoryView = {
  rules: [
    {
      id: "r-1",
      knowledgeType: "SHIPPING_POLICY",
      title: "배송 기준",
      documentName: "배송교환정책.pdf",
      active: true,
      citations: 4,
      lastUsedAt: "2026-09-23T00:00:00Z",
    },
    {
      id: "r-2",
      knowledgeType: "EXCHANGE_REFUND_POLICY",
      title: "교환·반품 기준",
      documentName: null,
      active: true,
      citations: 0,
      lastUsedAt: null,
    },
  ],
  productKnowledge: [
    {
      id: "k-1",
      productId: "p-1",
      productName: "실리콘 주방매트",
      productReachable: true,
      sourceType: "FAQ",
      title: "자주 묻는 질문 - 미끄럼",
      documentName: null,
      active: true,
      citations: 2,
      lastUsedAt: "2026-09-07T00:00:00Z",
    },
    {
      id: "k-2",
      productId: "p-2",
      productName: "전선몰딩 2호",
      productReachable: true,
      sourceType: "USAGE",
      title: "부착 방법",
      documentName: "제품 사용설명서.txt",
      active: true,
      citations: 21,
      lastUsedAt: "2026-09-05T00:00:00Z",
    },
    {
      id: "k-3",
      productId: "p-3",
      // 이름도 없고 열 화면도 없는 줄 — 지워졌거나 제조된 상품에 붙은 실제 지식이다.
      productName: null,
      productReachable: false,
      sourceType: "POLICY",
      title: "교환 기준 메모",
      documentName: null,
      active: true,
      citations: 0,
      lastUsedAt: null,
    },
  ],
  productKnowledgeTotal: 3,
  products: 142,
  productsWithKnowledge: 2,
  orphanRuleCitations: 3,
};

beforeEach(() => {
  getKnowledgeDocuments.mockReset().mockResolvedValue([DOCUMENT]);
  getKnowledgeCandidates.mockReset().mockResolvedValue([CANDIDATE]);
  getKnowledgeSummary.mockReset().mockResolvedValue(SUMMARY);
  getKnowledgeInventory.mockReset().mockResolvedValue(INVENTORY);
  getLearnedKnowledge.mockReset().mockResolvedValue({
    learned: { sources: [], channels: [], historyReads: [], canLearnHistory: false },
    lastRun: null,
  });
  proposeKnowledgeCandidates.mockReset().mockResolvedValue([CANDIDATE]);
  acceptKnowledgeCandidate.mockReset().mockResolvedValue({ ...CANDIDATE, state: "ACCEPTED" });
  dismissKnowledgeCandidate.mockReset().mockResolvedValue({ ...CANDIDATE, state: "DISMISSED" });
  setKnowledgeDocumentActive.mockReset().mockResolvedValue({ ...DOCUMENT, active: false });
  importKnowledgeDocument.mockReset().mockResolvedValue(DOCUMENT);
  getProductKnowledgeStrict.mockReset().mockResolvedValue({ variants: [] });
});

function draw() {
  return render(<MemoryRouter><KnowledgeHome /></MemoryRouter>);
}

/** The rows of one of the tables, by the box's accessible name. */
async function rowsOf(label: string) {
  draw();
  const box = await screen.findByLabelText(label);
  return within(box).getAllByRole("listitem");
}

describe("지식", () => {
  it("머리에는 이 회사가 가진 지식만 선다 — 해 온 일은 한 칸도 섞이지 않는다", async () => {
    draw();
    const strip = await screen.findByLabelText("이 회사가 가진 지식");
    for (const [label, value] of [["운영 기준", "2"], ["상품 지식", "3"], ["자료", "1"], ["확인 필요", "1"]]) {
      expect(within(strip).getByText(label).parentElement).toHaveTextContent(value);
    }
    // 과거 응답 23건은 가진 지식이 아니라 해 온 일이다. 머리의 띠에 서지 않는다.
    expect(strip).not.toHaveTextContent("과거 응답");
    expect(strip).not.toHaveTextContent("23");
    // 판매자가 가르친 적 없는 수가 가장 큰 활자이던 자리는 비었다.
    expect(screen.queryByText(/142개 상품 정보/)).toBeNull();
    expect(screen.getByRole("heading", { level: 1, name: "지식" })).toBeInTheDocument();
  });

  it("적혀 있지 않은 주제가 빈 줄로 선다 — 비어 있다는 사실이 이 화면의 절반이다", async () => {
    const rows = await rowsOf("운영 기준");
    // 여덟 주제 전부가 줄을 가진다. 적힌 둘만 그리면 비어 있다는 사실이 화면에서 사라진다.
    expect(rows).toHaveLength(8);
    expect(rows[0]).toHaveTextContent("배송");
    expect(rows[0]).toHaveTextContent("배송 기준");
    const tax = rows.find((row) => row.textContent?.startsWith("세금계산서"));
    expect(tax).toBeDefined();
    expect(tax).toHaveTextContent("적혀 있는 기준 없음");
    expect(tax).not.toHaveTextContent("직접 작성");
    expect(screen.getByText(/8가지 가운데 2가지가 적혀 있습니다/)).toBeInTheDocument();
  });

  it("지금 목록에 없는 기준을 인용한 답변 근거는 수로만 적힌다 — 어떤 기준이었는지는 말하지 않는다", async () => {
    draw();
    const hint = await screen.findByText(/목록에 없는 기준을 인용한 답변 근거/);
    expect(hint).toHaveTextContent("3건");
    // 이름을 되찾는 것은 외래 키가 받쳐 주지 않는 주장이다 — 화면은 수만 말한다.
    expect(hint.textContent).not.toContain("세금계산서");
    expect(hint.textContent).not.toContain("org-policy");
  });

  it("고아 인용이 없으면 그 문장은 아예 서지 않는다", async () => {
    getKnowledgeInventory.mockResolvedValue({ ...INVENTORY, orphanRuleCitations: 0 });
    draw();
    await screen.findByText(/8가지 가운데 2가지가 적혀 있습니다/);
    expect(screen.queryByText(/목록에 없는 기준을 인용한/)).toBeNull();
  });

  it("「답변 근거」는 저장된 근거의 수다 — 발송이라고 말하는 낱말이 화면에 없다", async () => {
    const rows = await rowsOf("상품 지식");
    expect(rows[1]).toHaveTextContent("21");
    for (const word of ["발송", "전송", "고객이 읽", "조회", "승인"]) {
      expect(document.body.textContent).not.toContain(word);
    }
  });

  it("서버가 보낸 순서를 그대로 그린다 — 화면이 같은 기준을 다시 해석하지 않는다", async () => {
    const rows = await rowsOf("상품 지식");
    // 각 줄의 제목만 — 서버가 준 순서 그대로, 많이 쓰인 것이 위로 올라오지 않은 채.
    expect(rows.map((row) => within(row).getAllByText(/./)[0].textContent)).toEqual([
      "자주 묻는 질문 - 미끄럼",
      "부착 방법",
      "교환 기준 메모",
    ]);
  });

  it("0은 빈 칸이고, 쓰인 적 없는 지식도 목록에서 빠지지 않는다", async () => {
    const rows = await rowsOf("상품 지식");
    const unused = rows[2];
    // 화면에 0이라는 글자는 없고, 읽어 주는 말로만 0건이라고 말한다.
    expect(within(unused).getByText("답변 근거 0건")).toBeInTheDocument();
    expect(within(unused).queryByText("0")).toBeNull();
    expect(screen.getByText(/은 아직 답변 근거로 쓰인 적이 없고/)).toBeInTheDocument();
  });

  it("열리지 않을 문은 그리지 않고, 이름은 그래도 부른다", async () => {
    const rows = await rowsOf("상품 지식");
    expect(rows[2]).toHaveTextContent("상품을 확인할 수 없음");
    expect(within(rows[2]).queryByRole("link")).toBeNull();
    // 이름이 있는 줄은 그 상품의 화면으로 간다 — 지식이 편집되는 유일한 자리다.
    expect(within(rows[0]).getByRole("link")).toHaveAttribute("href", "/products/p-1");
  });

  it("이름은 있지만 열 화면이 없는 상품도 이름으로 불린다 — 이름과 문은 다른 질문이다", async () => {
    getKnowledgeInventory.mockResolvedValue({
      ...INVENTORY,
      productKnowledge: [
        { ...INVENTORY.productKnowledge[0], productName: "전선몰딩 1호 (합성 샘플)", productReachable: false },
      ],
      productKnowledgeTotal: 1,
    });
    const rows = await rowsOf("상품 지식");
    expect(rows[0]).toHaveTextContent("전선몰딩 1호 (합성 샘플)");
    expect(rows[0]).not.toHaveTextContent("상품을 확인할 수 없음");
    expect(within(rows[0]).queryByRole("link")).toBeNull();
  });

  it("상품 몇 개에 지식이 있는지를 바닥이 말한다 — 상품 한 곳에서는 끝내 보이지 않는 빈자리", async () => {
    draw();
    const foot = await screen.findByText(/에 상품 지식이 있습니다/);
    expect(foot).toHaveTextContent("142개");
    expect(foot).toHaveTextContent("2개");
  });

  it("한 페이지가 전부가 아닐 때는 보고 있는 만큼만 말한다", async () => {
    getKnowledgeInventory.mockResolvedValue({ ...INVENTORY, productKnowledgeTotal: 90 });
    draw();
    const foot = await screen.findByText(/을 보고 있습니다/);
    expect(foot).toHaveTextContent("90건");
    expect(foot).toHaveTextContent("3건");
    // 세어 보지 않은 나머지를 두고 「쓰인 적 없는 n건」이라고 말하지 않는다.
    expect(screen.queryByText(/은 아직 답변 근거로 쓰인 적이 없고/)).toBeNull();
  });

  it("출처는 손으로 쓴 것과 넘긴 파일을 구분해 말한다", async () => {
    const rows = await rowsOf("상품 지식");
    expect(rows[0]).toHaveTextContent("직접 작성");
    expect(rows[1]).toHaveTextContent("자료 · 제품 사용설명서.txt");
  });

  it("carries no second chat and no ask-link of its own — the shell's panel is the conversation", async () => {
    const { container } = draw();
    await screen.findByLabelText("이 회사가 가진 지식");
    expect(screen.queryByRole("link", { name: /물어보기/ })).toBeNull();
    expect(container.querySelectorAll("textarea")).toHaveLength(0);
    // The two writing screens are under one control, as links.
    await userEvent.click(screen.getByText("+ 추가"));
    expect(screen.getByRole("link", { name: "상품 지식" })).toHaveAttribute("href", "/products");
    expect(screen.getByRole("link", { name: "운영 기준" })).toHaveAttribute("href", "/settings/policies");
  });

  it("shows what was noticed with the seller's own count, and never promotes it", async () => {
    draw();
    const inbox = await screen.findByTestId("knowledge-inbox");
    expect(inbox).toHaveTextContent("부착 전 표면의 먼지와 기름기를 제거해 주세요.");
    // The fact that makes it worth a glance is a COUNT of the seller's own answers — not a score.
    expect(inbox).toHaveTextContent("과거 답변 18건");
    expect(screen.getByText("기준 후보")).toBeInTheDocument();
    // Nothing was written by rendering it.
    expect(acceptKnowledgeCandidate).not.toHaveBeenCalled();
  });

  it("the seller's press is what turns a candidate into knowledge, and it writes what they wrote", async () => {
    draw();
    await screen.findByTestId("knowledge-inbox");
    getKnowledgeCandidates.mockResolvedValue([]);
    await userEvent.click(screen.getByRole("button", { name: "기준 등록" }));
    // A repeated sentence is the SELLER's own, so the editor opens holding it.
    expect(await screen.findByTestId("knowledge-quick-add")).toBeInTheDocument();
    expect((screen.getByLabelText("고객에게 안내할 내용") as HTMLTextAreaElement).value)
      .toBe("부착 전 표면의 먼지와 기름기를 제거해 주세요.");
    await userEvent.click(screen.getByRole("button", { name: "기준 등록" }));
    await waitFor(() =>
      expect(acceptKnowledgeCandidate).toHaveBeenCalledWith("c-1", {
        title: "부착 전 표면의 먼지와 기름기를 제거해 주세요.",
        content: "부착 전 표면의 먼지와 기름기를 제거해 주세요.",
        variantId: null,
        orgType: "SHIPPING_POLICY",
      }),
    );
  });

  it("a drafting gap is a QUESTION — the editor opens empty and the question is never saved as an answer", async () => {
    getKnowledgeCandidates.mockResolvedValue([GAP]);
    draw();
    const inbox = await screen.findByTestId("knowledge-inbox");
    expect(inbox).toHaveTextContent("「미끄럼 방지」에 대해 고객에게 안내할 공식 기준이 필요합니다.");
    expect(screen.getByText("정보 부족")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "입력" }));
    const body = (await screen.findByLabelText("고객에게 안내할 내용")) as HTMLTextAreaElement;
    // EMPTY. Accepting a gap unedited used to file the question itself as the company's knowledge.
    expect(body.value).toBe("");
    // And with nothing written there is nothing to save.
    expect(screen.getByRole("button", { name: "기준 등록" })).toBeDisabled();

    await userEvent.type(body, "실리콘 매트는 물기를 닦은 평평한 바닥에서 밀리지 않습니다.");
    await userEvent.click(screen.getByRole("button", { name: "기준 등록" }));
    await waitFor(() =>
      expect(acceptKnowledgeCandidate).toHaveBeenCalledWith("c-2", expect.objectContaining({
        content: "실리콘 매트는 물기를 닦은 평평한 바닥에서 밀리지 않습니다.",
      })),
    );
  });

  it("「보류」 dismisses without writing anything", async () => {
    draw();
    await screen.findByTestId("knowledge-inbox");
    getKnowledgeCandidates.mockResolvedValue([]);
    await userEvent.click(screen.getByRole("button", { name: "보류" }));
    await waitFor(() => expect(dismissKnowledgeCandidate).toHaveBeenCalledWith("c-1"));
    expect(acceptKnowledgeCandidate).not.toHaveBeenCalled();
  });

  it("a document says what it is, where it applies, when it came and who brought it — no internal vocabulary", async () => {
    draw();
    const documents = await screen.findByTestId("knowledge-documents");
    expect(documents).toHaveTextContent("제품 사용설명서.pdf");
    expect(documents).toHaveTextContent("공통 안내");
    expect(documents).toHaveTextContent("회사 전체");
    expect(documents).toHaveTextContent("2026-09-03");
    expect(documents).toHaveTextContent("demo");
    // 탭이 아니라 제 구역 — 자료는 위 두 표에 출처로 이름이 적히므로 같은 화면에 함께 선다.
    expect(screen.queryByRole("tab")).toBeNull();
    // The seller's screen never says chunk, embedding, source id, score — or a stored constant.
    for (const word of ["chunk", "embedding", "sourceId", "score", "s-1", "GENERAL_CS_FAQ"]) {
      expect(document.body.textContent).not.toContain(word);
    }
  });

  it("채널에서 읽어 온 것은 참고용이라고 제 자리에서 말한다", async () => {
    draw();
    expect(await screen.findByTestId("learned-knowledge")).toBeInTheDocument();
    expect(screen.getByText(/공식 기준이 아니라, 답변을 만들 때 참고만 합니다/)).toBeInTheDocument();
  });

  it("a document that produced nothing says so, and 확인 필요 offers the way out", async () => {
    getKnowledgeDocuments.mockResolvedValue([{ ...DOCUMENT, passages: 0 }]);
    getKnowledgeCandidates.mockResolvedValue([]);
    draw();
    const inbox = await screen.findByTestId("knowledge-inbox");
    expect(inbox).toHaveTextContent("내용 없음");
    expect(screen.getByText("자료 문제")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "사용 중지" }).length).toBeGreaterThan(0);
  });

  it("retiring is offered as retiring — the row stays and the wording says so", async () => {
    draw();
    const documents = await screen.findByTestId("knowledge-documents");
    await userEvent.click(within(documents).getByRole("button", { name: "사용 중지" }));
    await waitFor(() => expect(setKnowledgeDocumentActive).toHaveBeenCalledWith("s-1", false));
    // Never 삭제: the citations that stood on it must keep resolving.
    expect(document.body.textContent).not.toContain("삭제");
  });

  it("proposing looks through past answers and reports what it found", async () => {
    getKnowledgeCandidates.mockResolvedValue([]);
    proposeKnowledgeCandidates.mockResolvedValue([]);
    draw();
    await screen.findByText("확인 필요 없음");
    await userEvent.click(screen.getByRole("button", { name: "과거 답변에서 찾기" }));
    expect(await screen.findByText("반복 문장 없음")).toBeInTheDocument();
  });

  it("아직 아무것도 적지 않은 회사에는 빈 표가 아니라 적는 법이 선다", async () => {
    getKnowledgeCandidates.mockResolvedValue([]);
    getKnowledgeDocuments.mockResolvedValue([]);
    getKnowledgeInventory.mockResolvedValue({
      rules: [], productKnowledge: [], productKnowledgeTotal: 0, products: 142, productsWithKnowledge: 0,
      orphanRuleCitations: 0,
    });
    draw();
    expect(await screen.findByText("아직 적어 둔 상품 지식이 없습니다")).toBeInTheDocument();
    expect(screen.getByText(/이미 쓰고 계신 자료를 올리면/)).toBeInTheDocument();
    // 운영 기준은 비어 있어도 여덟 줄이 선다 — 무엇을 적어야 하는지가 곧 그 표다.
    expect(within(screen.getByLabelText("운영 기준")).getAllByRole("listitem")).toHaveLength(8);
    expect(screen.getByText(/8가지 가운데 0가지가 적혀 있습니다/)).toBeInTheDocument();
  });

  it("a first day offers no control that can only find nothing", async () => {
    getKnowledgeCandidates.mockResolvedValue([]);
    getKnowledgeDocuments.mockResolvedValue([]);
    getKnowledgeSummary.mockResolvedValue({
      productKnowledge: 0, operatingRules: 0, documents: 0, pastAnswers: 0, products: 0,
      needsConfirmation: 0,
    });
    draw();
    await screen.findByText("확인 필요 없음");
    expect(screen.queryByRole("button", { name: "과거 답변에서 찾기" })).toBeNull();
  });

  it("the upload asks what the file is, and the refusal is the backend's own sentence", async () => {
    importKnowledgeDocument.mockImplementation(() =>
      new Promise((_r, reject) =>
        setTimeout(() => reject({
          isAxiosError: true,
          response: { status: 400, data: { message: "이 형식은 읽을 수 없습니다. PDF · DOCX · TXT · MD · CSV 파일을 읽을 수 있습니다." } },
        }), 0)),
    );
    draw();
    await screen.findByTestId("knowledge-documents");
    await userEvent.click(screen.getByRole("button", { name: "+ 자료" }));
    // The one question the file cannot answer, asked once — never a review of its passages.
    expect(await screen.findByLabelText("어떤 자료인가요")).toBeInTheDocument();
    // The input is visually hidden behind its own button, so the change is fired directly rather
    // than through a pointer interaction userEvent would refuse on a display:none element.
    const input = screen.getByLabelText("자료 파일") as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(["x"], "manual.hwp")] } });
    expect(await screen.findByText(/이 형식은 읽을 수 없습니다/)).toBeInTheDocument();
  });

  it("has no accessibility violations", async () => {
    const { container } = draw();
    await screen.findByLabelText("상품 지식");
    await expectNoAxeViolations(container);
  });
});
