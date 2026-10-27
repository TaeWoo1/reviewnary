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
const uploadKnowledgeWorkbook = vi.fn();
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
    uploadKnowledgeWorkbook: (...a: unknown[]) => uploadKnowledgeWorkbook(...a),
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

  it("a part that failed to load is never drawn as zero — it says so and offers a retry", async () => {
    getKnowledgeCandidates.mockRejectedValueOnce(new Error("down"));
    getKnowledgeInventory.mockRejectedValueOnce(new Error("down"));
    draw();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("불러오지 못했습니다");
    expect(alert).toHaveTextContent("확인 필요");
    expect(alert).toHaveTextContent("운영 기준 · 상품 지식");
    const strip = screen.getByLabelText("이 회사가 가진 지식");
    for (const label of ["운영 기준", "상품 지식", "확인 필요"]) {
      expect(within(strip).getByText(label).parentElement).toHaveTextContent("–");
      expect(within(strip).getByText(label).parentElement).not.toHaveTextContent("0");
    }
    // The documents did load, and say what they are.
    expect(within(strip).getByText("자료").parentElement).toHaveTextContent("1");
    // 「없음」 is what an empty list says; a list that never came says it never came.
    expect(screen.queryByText("확인 필요 없음")).toBeNull();
    expect(within(screen.getByLabelText("상품 지식")).getByText("불러오지 못했습니다")).toBeInTheDocument();
    expect(screen.queryByText("아직 적어 둔 상품 지식이 없습니다")).toBeNull();

    await userEvent.click(within(alert).getByRole("button", { name: "다시 불러오기" }));
    await waitFor(() => expect(screen.queryByText(/불러오지 못했습니다/)).toBeNull());
    expect(within(strip).getByText("확인 필요").parentElement).toHaveTextContent("1");
    expect(within(strip).getByText("상품 지식").parentElement).toHaveTextContent("3");
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
    expect(screen.getByText("주제 8가지 가운데 2가지")).toBeInTheDocument();
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
    await screen.findByText("주제 8가지 가운데 2가지");
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
    // 적는 두 곳으로 가는 길은 각자의 구역 이름 옆에 제 이름으로 선다 — 머리의 「+ 추가」 메뉴는 같은 두
    // 곳으로 가는 두 번째 문이었고, 한 화면에서 같은 곳으로 가는 문이 둘이면 둘 다 읽히지 않는다.
    expect(screen.queryByText("+ 추가")).toBeNull();
    expect(screen.getByRole("link", { name: "상품에서 추가" })).toHaveAttribute("href", "/products");
    expect(screen.getByRole("link", { name: "기준 추가" })).toHaveAttribute("href", "/settings/policies");
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
        customerPhrases: [],
        orgType: "SHIPPING_POLICY",
      }),
    );
  });

  it("a proposed answer to a repeated review problem says so, counts the reviews, and opens holding its sentence", async () => {
    const GUIDANCE: KnowledgeCandidateView = {
      ...GAP,
      id: "c-3",
      subject: "접착 — 실크벽지·고르지 않은 벽",
      content: "실크벽지·고르지 않은 벽에 붙일 때\n폼 양면테이프나 실리콘을 덧대어 고정하시길 권해 드립니다.",
      origin: "ISSUE_GUIDANCE",
      evidenceCount: 37,
    };
    getKnowledgeCandidates.mockResolvedValue([GUIDANCE]);
    draw();
    const inbox = await screen.findByTestId("knowledge-inbox");
    expect(screen.getByText("안내 제안")).toBeInTheDocument();
    // Its count is of the customers who reported the problem, not of answers the seller wrote.
    expect(inbox).toHaveTextContent("같은 문제 리뷰 37건");
    expect(inbox).not.toHaveTextContent("과거 답변");
    await userEvent.click(screen.getByRole("button", { name: "기준 등록" }));
    expect((screen.getByLabelText("고객에게 안내할 내용") as HTMLTextAreaElement).value).toBe(GUIDANCE.content);
    expect(acceptKnowledgeCandidate).not.toHaveBeenCalled();
  });

  it("a proposal opens holding how customers say it, sends it on 기준 등록, and a refused blank is said as the server said it", async () => {
    const GUIDANCE: KnowledgeCandidateView = {
      ...GAP,
      id: "c-4",
      subject: "마감캡 구성",
      content: "한 세트에 【확인: 수량】개 들어 있습니다.",
      origin: "ISSUE_GUIDANCE",
      evidenceCount: 5,
      customerPhrases: ["마감캡", "하나만 왔"],
    };
    getKnowledgeCandidates.mockResolvedValue([GUIDANCE]);
    acceptKnowledgeCandidate.mockRejectedValueOnce(
      Object.assign(new Error("400"), {
        isAxiosError: true,
        response: { status: 400, data: { message: "【확인: …】 부분을 실제 내용으로 바꾼 뒤 등록해 주세요." } },
      }),
    );
    draw();
    await screen.findByTestId("knowledge-inbox");
    await userEvent.click(screen.getByRole("button", { name: "기준 등록" }));
    const phrases = screen.getByLabelText("고객은 이렇게 말해요") as HTMLTextAreaElement;
    expect(phrases.value).toBe("마감캡\n하나만 왔");
    await userEvent.type(phrases, "\n두 개가 필요");
    await userEvent.click(within(screen.getByTestId("knowledge-quick-add")).getByRole("button", { name: "기준 등록" }));
    await waitFor(() =>
      expect(acceptKnowledgeCandidate).toHaveBeenCalledWith(
        "c-4",
        expect.objectContaining({ customerPhrases: ["마감캡", "하나만 왔", "두 개가 필요"] }),
      ),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("【확인: …】 부분을 실제 내용으로 바꾼 뒤 등록해 주세요.");
  });

  it("an uploaded workbook is read first — nothing is applied until 적용", async () => {
    uploadKnowledgeWorkbook
      .mockResolvedValueOnce({
        applied: false,
        changes: [{ sheet: "안내 매뉴얼", row: 3, text: "「실크벽지 부착」 새 안내 등록" }],
        problems: [{ sheet: "안내 매뉴얼", row: 4, text: "【확인: …】이 남아 있어 「사용」으로 등록할 수 없습니다." }],
      })
      .mockResolvedValueOnce({
        applied: true,
        changes: [{ sheet: "안내 매뉴얼", row: 3, text: "「실크벽지 부착」 새 안내 등록" }],
        problems: [],
      });
    draw();
    const block = await screen.findByTestId("knowledge-workbook");
    const file = new File(["x"], "답글 기준.xlsx", {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    });
    await userEvent.upload(within(block).getByLabelText("엑셀 파일 고르기"), file);
    expect(await within(block).findByText("올리면 바뀌는 것 · 1건")).toBeInTheDocument();
    expect(block).toHaveTextContent("반영하지 않는 줄 · 1건");
    expect(uploadKnowledgeWorkbook).toHaveBeenCalledTimes(1);
    expect(uploadKnowledgeWorkbook).toHaveBeenLastCalledWith(file, false);
    await userEvent.click(within(block).getByRole("button", { name: "적용" }));
    await waitFor(() => expect(uploadKnowledgeWorkbook).toHaveBeenLastCalledWith(file, true));
    expect(await within(block).findByText("반영했습니다 · 1건")).toBeInTheDocument();
  });

  it("the workbook preview shows where a change applies and its before and after; a stale row shows the screen's text", async () => {
    uploadKnowledgeWorkbook.mockResolvedValueOnce({
      applied: false,
      changes: [{
        sheet: "답변 템플릿", row: 2, text: "「별점 4~5점 기본 문구」 문구 바꿈", where: "별점이 4~5점인 리뷰에 씁니다.",
        before: "소중한 후기 감사합니다.", after: "마음에 드셨다니 기쁩니다.",
      }],
      problems: [{
        sheet: "안내 매뉴얼", row: 3, text: "「자르기」 안내 고침 — 내려받은 뒤 화면에서 바뀐 줄이라 반영하지 않았습니다.",
        where: "상품 · 일체형 전선몰딩", before: "화면에서 고친 안내입니다.", after: null,
      }],
    });
    draw();
    const block = await screen.findByTestId("knowledge-workbook");
    await userEvent.upload(within(block).getByLabelText("엑셀 파일 고르기"), new File(["x"], "답글 기준.xlsx"));
    const change = (await within(block).findByText("「별점 4~5점 기본 문구」 문구 바꿈")).closest("li") as HTMLElement;
    expect(change).toHaveTextContent("별점이 4~5점인 리뷰에 씁니다.");
    expect(within(change).getByText("지금").parentElement).toHaveTextContent("소중한 후기 감사합니다.");
    expect(within(change).getByText("바뀐 뒤").parentElement).toHaveTextContent("마음에 드셨다니 기쁩니다.");
    const stale = within(block).getByText(/화면에서 바뀐 줄/).closest("li") as HTMLElement;
    expect(within(stale).getByText("화면의 지금 내용").parentElement).toHaveTextContent("화면에서 고친 안내입니다.");
    expect(within(stale).queryByText("바뀐 뒤")).toBeNull();
  });

  it("a long change folds but reads to its end on 전체 보기, both sides at once", async () => {
    const tail = "단, 개봉 후에는 환불이 되지 않습니다.";
    const longText = Array.from({ length: 12 }, (_, i) => `${i + 1}번째 안내 문장입니다.`).join("\n") + "\n" + tail;
    uploadKnowledgeWorkbook.mockResolvedValueOnce({
      applied: false,
      changes: [{ sheet: "안내 매뉴얼", row: 2, text: "「환불」 안내 고침", where: "회사 전체", before: longText, after: longText.replace("되지 않습니다", "됩니다") }],
      problems: [],
    });
    draw();
    const block = await screen.findByTestId("knowledge-workbook");
    await userEvent.upload(within(block).getByLabelText("엑셀 파일 고르기"), new File(["x"], "답글 기준.xlsx"));
    const row = (await within(block).findByText("「환불」 안내 고침")).closest("li") as HTMLElement;
    const after = within(row).getByText("바뀐 뒤").nextElementSibling as HTMLElement;
    expect(after).toHaveAttribute("data-folded", "true");
    // Folded, the changed line may sit below the fold — the control says there is one.
    const more = within(row).getByRole("button", { name: "전체 보기 · 바뀐 줄 1개" });
    expect(more).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(more);
    expect(after).toHaveAttribute("data-folded", "false");
    expect(within(row).getByText("지금").nextElementSibling).toHaveAttribute("data-folded", "false");
    expect(after).toHaveTextContent("단, 개봉 후에는 환불이 됩니다.");
    // Only the line that changed is marked, and only on the side that changed.
    expect(within(after).getAllByTitle("바뀐 줄").map((m) => m.textContent)).toEqual(["단, 개봉 후에는 환불이 됩니다."]);
    expect(within(row).getByText("지금").nextElementSibling?.querySelector("mark")).toBeNull();
    expect(within(row).getByRole("button", { name: "접기" })).toHaveAttribute("aria-expanded", "true");
  });

  it("a short change has nothing to unfold", async () => {
    uploadKnowledgeWorkbook.mockResolvedValueOnce({
      applied: false,
      changes: [{ sheet: "답변 템플릿", row: 2, text: "「가격 리뷰」 문구 바꿈", before: "감사합니다.", after: "고맙습니다." }],
      problems: [],
    });
    draw();
    const block = await screen.findByTestId("knowledge-workbook");
    await userEvent.upload(within(block).getByLabelText("엑셀 파일 고르기"), new File(["x"], "답글 기준.xlsx"));
    await within(block).findByText("「가격 리뷰」 문구 바꿈");
    expect(within(block).queryByRole("button", { name: "전체 보기" })).toBeNull();
  });

  it("a reload that fails after a good one says the list below is the earlier one", async () => {
    draw();
    await screen.findByTestId("knowledge-inbox");
    getKnowledgeCandidates.mockRejectedValueOnce(new Error("down"));
    // Any write reloads the screen; 보류 is the cheapest one.
    await userEvent.click(screen.getByRole("button", { name: "보류" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("불러오지 못했습니다 · 확인 필요");
    expect(alert).toHaveTextContent("아래는 이전에 불러온 내용입니다");
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

  it("확인 필요 두 줄은 같은 상태이므로 같은 무게로 선다 — 한쪽이 골라져 있다고 말하지 않는다", async () => {
    getKnowledgeCandidates.mockResolvedValue([GAP, { ...GAP, id: "c-3", subject: "두 번째" }]);
    draw();
    await screen.findByTestId("knowledge-inbox");
    const enter = screen.getAllByRole("button", { name: "입력" });
    expect(enter).toHaveLength(2);
    // 생김새를 글자로 못 박지 않는다 — 둘이 서로 같다는 것만이 계약이다.
    expect(enter[0].className).toBe(enter[1].className);
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
    expect(screen.getByText("주제 8가지 가운데 0가지")).toBeInTheDocument();
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
