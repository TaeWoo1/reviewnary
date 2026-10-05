// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { CustomerMemory } from "./CustomerMemory";
import { RepeatedIssue } from "./RepeatedIssue";
import { expectNoAxeViolations } from "../../test/axe";
import type { RepeatedIssueContext, ReviewIssueDetailView, ReviewIssueView } from "../../lib/types";

const getReviewIssuesStrict = vi.fn();
const getReviewIssueDetailStrict = vi.fn();
const getRepeatedIssueContextStrict = vi.fn();
const startReviewIssueAction = vi.fn();
const markReviewIssueRemediated = vi.fn();
const getOpportunitiesStrict = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getReviewIssuesStrict: () => getReviewIssuesStrict(),
    getReviewIssueDetailStrict: (id: string) => getReviewIssueDetailStrict(id),
    getRepeatedIssueContextStrict: (id: string) => getRepeatedIssueContextStrict(id),
    startReviewIssueAction: (id: string, note?: string) => startReviewIssueAction(id, note),
    markReviewIssueRemediated: (id: string, note?: string) => markReviewIssueRemediated(id, note),
    getOpportunitiesStrict: (o: unknown) => getOpportunitiesStrict(o),
  },
  getToken: () => null,
}));

function issue(over: Partial<ReviewIssueView> & Pick<ReviewIssueView, "id">): ReviewIssueView {
  return {
    title: "접착력이 약하다는 이야기가 늘고 있어요",
    aspect: "접착",
    problem: "부착 후 떨어짐",
    severity: "HIGH",
    lifecycleState: "NEEDS_REVIEW",
    lifecycleLabelKo: "확인 필요",
    evidenceCount: 4,
    firstEvidenceOn: "2026-06-18",
    lastEvidenceOn: "2026-08-02",
    dominantProductId: null,
    dominantProductName: null,
    dismissed: false,
    extractorKind: "RULE_BASED",
    change: {
      kinds: ["SURGING"],
      labelsKo: ["증가 중"],
      highSurge: true,
      surgeWindowCount: 4,
      surgeBaselineWeekly: 0.6,
    },
    ...over,
  } as ReviewIssueView;
}

const SURGING = issue({ id: "issue-1" });
const IMPROVED = issue({
  id: "issue-2",
  title: "재단 중 파손 이야기가 줄었어요",
  severity: "LOW",
  lifecycleState: "VERIFYING",
  lifecycleLabelKo: "개선 확인 중",
  change: {
    kinds: ["IMPROVED"],
    labelsKo: ["개선됨"],
    highSurge: false,
    surgeWindowCount: 0,
    surgeBaselineWeekly: 0,
  } as ReviewIssueView["change"],
});

const REPEAT_CONTEXT: RepeatedIssueContext = {
  issueId: "surge",
  aspect: "접착",
  evidence: {
    totalEvidence: 19,
    byProduct: [
      {
        productId: "prod-1",
        productName: "전선몰딩 1호",
        evidenceCount: 16,
        productReviews: 1761,
        firstOccurredOn: "2026-06-18",
        lastOccurredOn: "2026-08-02",
      },
      {
        productId: "prod-2",
        productName: "종이컵 보관함",
        evidenceCount: 1,
        productReviews: 416,
        firstOccurredOn: "2026-07-01",
        lastOccurredOn: "2026-07-01",
      },
    ],
    unattributedEvidence: 2,
    ratingDistribution: { rating1: 0, rating2: 0, rating3: 3, rating4: 5, rating5: 10, unrated: 1 },
    firstEvidenceOn: "2026-06-18",
    lastEvidenceOn: "2026-08-02",
  },
  knowledge: {
    productId: "prod-1",
    productName: "전선몰딩 1호",
    productSources: 3,
    productMentions: 1,
    orgSources: 2,
    orgMentions: 0,
    excerpts: ["접착 면의 먼지를 닦고 30초간 눌러 주세요."],
  },
};

const DETAIL: ReviewIssueDetailView = {
  issue: SURGING,
  evidence: [
    {
      reviewId: "rev-loaded",
      unitOrdinal: 1,
      occurredOn: "2026-08-02",
      productId: null,
      productName: "전선몰딩 1호",
      rating: 1,
      quote: "부착 후 며칠 지나니 떨어졌어요.",
    },
    {
      reviewId: "rev-not-loaded",
      unitOrdinal: 1,
      occurredOn: "2026-07-28",
      productId: null,
      productName: null,
      rating: 2,
      quote: "한쪽이 들뜹니다.",
    },
    {
      reviewId: "rev-suppressed",
      unitOrdinal: 2,
      occurredOn: "2026-07-19",
      productId: null,
      productName: null,
      rating: 2,
      quote: null,
    },
  ],
  history: [
    {
      fromState: "OBSERVING",
      toState: "NEEDS_REVIEW",
      toStateLabelKo: "확인 필요",
      actor: "SYSTEM",
      reason: "THRESHOLD_REACHED",
      note: null,
      at: "2026-07-01T00:00:00Z",
    },
  ],
};

function renderMemory(path = "/memory") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/memory" element={<CustomerMemory />} />
        <Route path="/memory/:issueId" element={<RepeatedIssue />} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * 열린 문제의 페이지 — 목록 옆 패널이 아니라 제 주소의 페이지다 (canonical, 2026-10-05).
 *
 * <p>예전에는 `선택한 문제`라는 이름의 패널을 기다렸다. 페이지에는 그 패널이 없고 제목이 `h1`이므로,
 * 제목이 서는 것을 기다린 뒤 읽는 열을 돌려준다.
 */
async function openIssue(): Promise<HTMLElement> {
  await screen.findByRole("heading", { level: 1 });
  return document.querySelector('[data-case-variant="page"]') as HTMLElement;
}

beforeEach(() => {
  getReviewIssuesStrict.mockResolvedValue([SURGING, IMPROVED]);
  getReviewIssueDetailStrict.mockResolvedValue(DETAIL);
  getRepeatedIssueContextStrict.mockResolvedValue(REPEAT_CONTEXT);
  getOpportunitiesStrict.mockResolvedValue([]);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("반복 문제 — 목록은 목록이다 (canonical, 2026-10-05)", () => {
  /**
   * <b>목록 화면에는 열린 문제가 없다.</b> 행은 선택이 아니라 목적지가 됐다 — 문제 하나는 제 페이지에서
   * 열린다. 넓은 화면이라고 해서 아무 문제나 미리 열어 두지 않는다: 열 것을 고르는 것은 판매자다.
   */
  it("stands as a list at every width — no pane, nothing pre-selected", async () => {
    const restore = stubWide(true);
    try {
      renderMemory();
      const list = await screen.findByLabelText("반복 이슈 목록");
      // The three groups are the list's tabs since the 2026-10-03 canonical: a tab states its count even
      // when it is empty, which a heading that simply does not appear cannot do. Unchanged here.
      const tabs = screen.getByRole("group", { name: "상태 필터" });
      expect(within(tabs).getByRole("button", { name: "확인 필요 1" })).toBeInTheDocument();
      expect(within(tabs).getByRole("button", { name: "개선됨 1" })).toBeInTheDocument();
      expect(within(tabs).getByRole("button", { name: "지켜보는 중 0" })).toBeInTheDocument();
      expect(screen.queryByTestId("master-detail-pane")).toBeNull();
      expect(screen.queryByText(/왼쪽에서 이슈를 고르면/)).toBeNull();
      expect(within(list).getAllByRole("link").filter((a) => a.getAttribute("aria-current") === "true")).toHaveLength(0);
      // 결정은 문제의 페이지에서 내린다 — 목록에는 쓸 것이 없다.
      expect(screen.queryByLabelText(/무엇을 하기로 하셨나요/)).toBeNull();
    } finally {
      restore();
    }
  });

  it("shows each issue's state, judgement and evidence count on one line — severity moved to the problem itself", async () => {
    renderMemory();
    const list = await screen.findByLabelText("반복 이슈 목록");
    // The facts are columns now, named once at the head of the list rather than inside every row.
    expect(within(list).getByText("근거")).toBeInTheDocument();
    expect(within(list).getByText("마지막 발생")).toBeInTheDocument();
    const row = within(list).getByRole("link", { name: /접착력이 약하다는/ });
    expect(row).toHaveTextContent("확인 필요");
    expect(row).toHaveTextContent("증가 중");
    expect(row).toHaveTextContent("4건");
    expect(row).toHaveTextContent("2026-08-02");
    // Metadata no longer opens the row: the title is read before the facts about it.
    expect(row).not.toHaveTextContent("심각도");
  });

  it("names the severity in the problem's own header", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    expect(detail).toHaveTextContent("심각도 심각");
  });

  /**
   * <b>이 문제를 정의하는 수 둘</b> (승인된 mockup — Sentry의 Events · Users 자리). 목록에서 이 행을
   * 고른 이유가 제목 옆에 선다. 상품 수는 repeat-context가 돌아왔을 때만 선다 — 못 읽은 수를 0으로
   * 적는 것이 이 화면에서 가장 나쁜 출력이다.
   */
  it("states the two figures that define the problem beside its title", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    const header = detail.querySelector("header") as HTMLElement;
    expect(within(header).getByText("근거")).toBeInTheDocument();
    expect(within(header).getByText("4건")).toBeInTheDocument();
    expect(await within(header).findByText("상품")).toBeInTheDocument();
    expect(within(header).getByText("2곳")).toBeInTheDocument();
  });

  it("states no product count when that read failed", async () => {
    getRepeatedIssueContextStrict.mockRejectedValue(new Error("down"));
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    const header = detail.querySelector("header") as HTMLElement;
    expect(within(header).getByText("근거")).toBeInTheDocument();
    expect(within(header).queryByText("상품")).toBeNull();
    expect(header.textContent ?? "").not.toContain("0곳");
  });

  it("links each row to its own deep link", async () => {
    renderMemory();
    const list = await screen.findByLabelText("반복 이슈 목록");
    expect(within(list).getByRole("link", { name: /접착력이/ })).toHaveAttribute(
      "href",
      "/memory/issue-1",
    );
  });

  /**
   * <b>탭과 분류는 주소에 실려 따라간다.</b> 문제를 열었다 돌아온 판매자가 자기가 보던 목록이 아니라
   * 전체 목록을 받으면, 열어 본 문제마다 필터를 다시 거는 셈이 된다.
   */
  it("carries the open tab and 분류 into the row's address, and offers them back", async () => {
    renderMemory("/memory?group=improved&aspect=접착");
    const list = await screen.findByLabelText("반복 이슈 목록");
    expect(within(list).getByRole("link", { name: /재단 중 파손/ })).toHaveAttribute(
      "href",
      "/memory/issue-2?group=improved&aspect=%EC%A0%91%EC%B0%A9",
    );
  });

  it("walks back to the list the seller left", async () => {
    renderMemory("/memory/issue-1?group=attention&aspect=접착");
    await openIssue();
    expect(screen.getByRole("link", { name: "← 반복 문제" })).toHaveAttribute(
      "href",
      "/memory?group=attention&aspect=%EC%A0%91%EC%B0%A9",
    );
  });
});

describe("고객운영 메모리 — deep link", () => {
  it("opens the requested issue with its evidence and trend", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    expect(within(detail).getByRole("heading", { level: 1 })).toHaveTextContent("접착력이");
    expect(await within(detail).findByText(/부착 후 며칠 지나니 떨어졌어요/)).toBeInTheDocument();
    // The quantified surge line, from the server's own numbers.
    expect(within(detail).getByText(/최근 7일 4건/)).toBeInTheDocument();
  });

  it("says so honestly when the problem could not be read", async () => {
    // 제 주소로 열리는 페이지는 목록에 그 행이 있는지 묻지 않는다 — 읽어 보고, 못 읽으면 못 읽었다고
    // 말한다. 지어낸 문제를 그리느니 돌아갈 길을 준다.
    getReviewIssueDetailStrict.mockRejectedValue(new Error("gone"));
    renderMemory("/memory/nope");
    expect(await screen.findByText("이 문제를 불러오지 못했습니다")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "반복 문제 목록" })).toHaveAttribute("href", "/memory");
  });

  it("surfaces suppressed evidence as a count rather than an empty quote", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    expect(await within(detail).findByText(/인용을 표시할 수 없는 근거가 1건/)).toBeInTheDocument();
  });

  it("shows the recorded state history", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    expect(await within(detail).findByText("기록")).toBeInTheDocument();
  });
});

/**
 * <b>Rewritten when the destination changed.</b> The claim this replaced was 「link a quote only when
 * that row is actually loaded in the inbox」, and it was right about its own destination: an inbox
 * page could only open a row it already held, so an unconditional link there would reliably land on
 * 「찾을 수 없습니다」. The cost was that whether a seller could reach the review behind a quote
 * depended on what a different screen had fetched — on this org most evidence is older than any
 * loaded inbox page, so most quotes were dead ends.
 *
 * The destination is now the review's own processing surface, which resolves its account from the
 * review id with an org-scoped read. It needs nothing loaded, so the condition that made the old
 * claim true is gone and the honest claim is stronger: EVERY quote reaches the review behind it, and
 * it reaches the one surface where that review is judged and answered.
 */
describe("고객운영 메모리 — evidence links back to the review", () => {
  it("links every rendered quote to that review's own processing surface", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    const links = await within(detail).findAllByRole("link", { name: "이 리뷰 처리하기" });
    // One per rendered quote — not one per quote the inbox happened to hold.
    expect(links).toHaveLength(2);
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/reviews/reply/rev-loaded",
      "/reviews/reply/rev-not-loaded",
    ]);
    // And nothing still points at the inbox, which could not open most of these rows.
    expect(within(detail).queryByRole("link", { name: "인박스에서 보기" })).toBeNull();
  });
});

describe("고객운영 메모리 — 어디서 얼마나 반복되나", () => {
  it("shows each product's evidence count beside that product's own review total", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    const section = await within(detail).findByRole("region", { name: "어디서 반복되나" });
    // 상품은 전부 이 띠에 선다 — 900px 열에는 세 줄을 접어 둘 이유가 없고, 「어디서」는 이 화면이
    // 존재하는 이유다. 둘 다 쌍을 찍고 어느 쪽도 비율을 만들지 않는다.
    expect(within(section).getByText(/리뷰 1,761건 중 16건이 이 문제를 말했습니다/)).toBeInTheDocument();
    expect(within(section).getByText(/리뷰 416건 중 1건이 이 문제를 말했습니다/)).toBeInTheDocument();
  });

  /**
   * The workspace's whole failure mode in one assertion. A ratio drawn from these two numbers would
   * describe an examined population nobody measured — the denominator counts reviews the extractor
   * never read.
   */
  it("never prints a rate computed from the pair", async () => {
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("어디서 반복되나");
    expect(section.textContent ?? "").not.toMatch(/%|퍼센트|비율/);
  });

  it("opens the product behind each row", async () => {
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("어디서 반복되나");
    expect(within(section).getByRole("link", { name: /전선몰딩 1호/ }))
      .toHaveAttribute("href", "/products/prod-1");
  });

  it("states the evidence that belongs to no product rather than losing it", async () => {
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("어디서 반복되나");
    expect(within(section).getByText(/확인되지 않은 근거가 2건/)).toBeInTheDocument();
  });

  /**
   * A read that failed renders nothing at all. 「0개 상품」 would be this screen reporting a fact it
   * could not see — and on a workspace whose job is deciding, an invented zero is the worst output.
   */
  it("renders nothing about repetition when that read failed", async () => {
    getRepeatedIssueContextStrict.mockRejectedValue(new Error("down"));
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    // The problem and its evidence still render — the two reads fail apart.
    expect(within(detail).getByRole("region", { name: "근거" })).toBeInTheDocument();
    expect(within(detail).queryByRole("region", { name: "어디서 반복되나" })).toBeNull();
    expect(within(detail).queryByRole("region", { name: "우리가 써 둔 것" })).toBeNull();
  });
});

describe("고객운영 메모리 — 우리가 써 둔 것", () => {
  it("counts what the library holds against what names this problem, and quotes the seller", async () => {
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("우리가 써 둔 것");
    expect(within(section).getByText("등록된 안내 5건 가운데 1건이 이 문제를 다룹니다.")).toBeInTheDocument();
    expect(within(section).getByText(/접착 면의 먼지를 닦고/)).toBeInTheDocument();
    // Something already answers it, so the screen does not ask for work that is done.
    expect(within(section).queryByRole("link", { name: /답변 기준 채우기/ })).toBeNull();
  });

  it("offers the way to fill a gap only when nothing names this problem", async () => {
    getRepeatedIssueContextStrict.mockResolvedValue({
      ...REPEAT_CONTEXT,
      knowledge: { ...REPEAT_CONTEXT.knowledge, productMentions: 0, excerpts: [] },
    });
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("우리가 써 둔 것");
    expect(within(section).getByText(/이 문제를 다루는 내용은 찾지 못했습니다/)).toBeInTheDocument();
    expect(within(section).getByRole("link", { name: /답변 기준 채우기/ }))
      .toHaveAttribute("href", "/knowledge");
  });
});

describe("반복 문제 — the page follows its own decision (canonical, 2026-10-05)", () => {
  /**
   * <b>목록이 옆에 없으므로 따라올 행도 없다.</b> 상태를 옮기고 나면 이 페이지가 자기 상태를 다시 읽고,
   * 머리말의 낱말과 다음에 누를 단추가 서버가 방금 말한 것으로 바뀐다. 눌린 단추가 쥐고 있던 상태로
   * 남아 있으면, 다음 누름은 이미 지나간 전이를 보낸다.
   */
  it("re-reads the problem after a transition — the header word and the next control follow", async () => {
    const acting = { ...SURGING, lifecycleState: "ACTING" as const, lifecycleLabelKo: "조치 중" };
    startReviewIssueAction.mockResolvedValue(acting);
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    expect(detail).toHaveTextContent("확인 필요");

    getReviewIssueDetailStrict.mockResolvedValue({ ...DETAIL, issue: acting });
    fireEvent.click(await screen.findByRole("button", { name: "조치 시작" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "조치 완료로 기록" })).toBeInTheDocument());
    expect(document.querySelector('[data-case-variant="page"]')).toHaveTextContent("조치 중");
    // 같은 문제다 — 결정이 화면을 다른 문제로 바꾸지 않는다.
    expect(document.querySelector('[data-case-variant="page"]')).toHaveTextContent(SURGING.title);
  });
});

describe("고객운영 메모리 — 판단과 조치", () => {
  /**
   * The note is why this section exists. Both transitions have accepted an operator note since the
   * lifecycle was built and no screen ever sent one, so every decision a seller made was recorded as
   * a state change by someone who said nothing about it.
   */
  it("sends the seller's own sentence with the transition", async () => {
    startReviewIssueAction.mockResolvedValue(SURGING);
    renderMemory("/memory/issue-1");
    await openIssue();

    fireEvent.change(screen.getByLabelText(/무엇을 하기로 하셨나요/), {
      target: { value: "접착 테이프 공급처를 바꿉니다." },
    });
    fireEvent.click(screen.getByRole("button", { name: "조치 시작" }));

    await waitFor(() =>
      expect(startReviewIssueAction).toHaveBeenCalledWith("issue-1", "접착 테이프 공급처를 바꿉니다."),
    );
  });

  /**
   * Optional on purpose: a decision without a sentence is still a decision, and demanding prose
   * before a state change makes the record worse by making people skip the change.
   */
  it("records the decision with no note rather than blocking it", async () => {
    startReviewIssueAction.mockResolvedValue(SURGING);
    renderMemory("/memory/issue-1");
    await openIssue();

    fireEvent.click(screen.getByRole("button", { name: "조치 시작" }));

    await waitFor(() => expect(startReviewIssueAction).toHaveBeenCalledWith("issue-1", undefined));
  });

  it("says what reviewnary is waiting for where the seller has no move", async () => {
    getReviewIssueDetailStrict.mockResolvedValue({ ...DETAIL, issue: IMPROVED });
    renderMemory("/memory/issue-2");
    const section = await screen.findByLabelText("판단과 조치");
    expect(within(section).queryByRole("button")).toBeNull();
    expect(within(section).queryByLabelText(/무엇을 하기로 하셨나요/)).toBeNull();
  });

  /**
   * <b>A seller may start work on a problem reviewnary has not raised</b> (product-owner decision,
   * 2026-09-13). Before this the control existed only in NEEDS_REVIEW, which an automatic judgement
   * produces — and on sparse evidence none ever fires, so on the demo org all 25 issues sat in
   * OBSERVING with no way to record a decision at all.
   */
  it("offers 조치 시작 on an observed problem, and still says reviewnary has not raised it", async () => {
    const observing = { ...SURGING, lifecycleState: "OBSERVING" as const, lifecycleLabelKo: "관찰 중" };
    getReviewIssuesStrict.mockResolvedValue([observing, IMPROVED]);
    getReviewIssueDetailStrict.mockResolvedValue({ ...DETAIL, issue: observing });
    startReviewIssueAction.mockResolvedValue(observing);

    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("판단과 조치");
    // Both, not one instead of the other: the seller is acting ahead of reviewnary and can see that.
    expect(within(section).getByText(/먼저 확인을 권할 만큼 근거가 모이지는 않았습니다/)).toBeInTheDocument();

    fireEvent.change(within(section).getByLabelText(/무엇을 하기로 하셨나요/), {
      target: { value: "공급처를 바꿉니다." },
    });
    fireEvent.click(within(section).getByRole("button", { name: "조치 시작" }));
    await waitFor(() =>
      expect(startReviewIssueAction).toHaveBeenCalledWith("issue-1", "공급처를 바꿉니다."),
    );
  });
});

describe("고객운영 메모리 — 어떤 별점에서 나왔나", () => {
  it("reports each star band as a count of evidence", async () => {
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("어떤 별점에서 나왔나");
    expect(within(section).getByText("5점")).toBeInTheDocument();
    expect(within(section).getByText("근거 10건")).toBeInTheDocument();
    expect(within(section).getByText("근거 5건")).toBeInTheDocument();
    expect(within(section).getByText("별점 없음")).toBeInTheDocument();
  });

  /**
   * A problem raised entirely inside good ratings is invisible to every screen that sorts by star.
   * The zero bands are what make it visible, so they are asserted present rather than tolerated.
   */
  it("keeps the bands nobody complained in, and derives no rate from them", async () => {
    getRepeatedIssueContextStrict.mockResolvedValue({
      ...REPEAT_CONTEXT,
      evidence: {
        ...REPEAT_CONTEXT.evidence,
        ratingDistribution: { rating1: 0, rating2: 0, rating3: 3, rating4: 5, rating5: 10, unrated: 0 },
      },
    });
    renderMemory("/memory/issue-1");
    const section = await screen.findByLabelText("어떤 별점에서 나왔나");
    expect(within(section).getByText("1점")).toBeInTheDocument();
    expect(within(section).getAllByText("근거 0건").length).toBeGreaterThanOrEqual(2);
    expect(section.textContent ?? "").not.toMatch(/%|퍼센트|비율|평균/);
  });

  it("renders nothing at all when there is no evidence to spread", async () => {
    getRepeatedIssueContextStrict.mockResolvedValue({
      ...REPEAT_CONTEXT,
      evidence: {
        ...REPEAT_CONTEXT.evidence,
        ratingDistribution: { rating1: 0, rating2: 0, rating3: 0, rating4: 0, rating5: 0, unrated: 0 },
      },
    });
    renderMemory("/memory/issue-1");
    await openIssue();
    expect(screen.queryByLabelText("어떤 별점에서 나왔나")).toBeNull();
  });
});

describe("고객운영 메모리 — lifecycle actions", () => {
  it("offers only the transition the lifecycle allows", async () => {
    renderMemory("/memory/issue-1");
    await openIssue();
    expect(screen.getByRole("button", { name: "조치 시작" })).toBeInTheDocument();
    // There is deliberately no 해결 처리 control at any state — 해결됨 rests on observed quiet
    // weeks, and a button would let an assertion stand in for that evidence.
    expect(screen.queryByRole("button", { name: /해결/ })).toBeNull();
  });

  it("offers no action where the next move belongs to reviewnary", async () => {
    getReviewIssueDetailStrict.mockResolvedValue({ ...DETAIL, issue: IMPROVED });
    renderMemory("/memory/issue-2");
    const detail = await openIssue();
    expect(
      await within(detail).findByText(/조치 이후 리뷰 변화를 지켜보고 있어요/),
    ).toBeInTheDocument();
    expect(within(detail).queryByRole("button")).toBeNull();
  });

  it("never describes the extraction as AI", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    expect(await within(detail).findByText(/규칙 기반 분석으로 모은 이슈 후보/)).toBeInTheDocument();
    expect(detail.textContent).not.toContain("AI");
  });
});

describe("반복 문제 — 돌아왔을 때의 목록 (canonical, 2026-10-05)", () => {
  /**
   * <b>스크롤 위치는 목록이 기억한다.</b> 행이 다른 페이지로 나가게 되면서 목록은 돌아올 때마다 다시
   * 그려진다 — 열일곱 줄짜리 목록에서 열 번째 문제를 보던 판매자가 돌아올 때마다 맨 위로 끌려간다.
   * 스크롤되는 것은 문서가 아니라 이 열이므로 브라우저가 대신 해 주지 못한다.
   */
  it("remembers how far the list was scrolled and restores it on the way back", async () => {
    window.sessionStorage.clear();
    const first = renderMemory();
    await screen.findByLabelText("반복 이슈 목록");
    const column = screen.getByTestId("master-list");
    column.scrollTop = 420;
    fireEvent.scroll(column);
    expect(window.sessionStorage.getItem("master-detail-scroll:memory-list")).toBe("420");
    first.unmount();

    renderMemory();
    await screen.findByLabelText("반복 이슈 목록");
    await waitFor(() => expect(screen.getByTestId("master-list").scrollTop).toBe(420));
  });
});

describe("고객운영 메모리 — empty and failed states", () => {
  it("invites a connection when nothing has been recorded", async () => {
    getReviewIssuesStrict.mockResolvedValue([]);
    renderMemory();
    expect(await screen.findByText("아직 쌓인 기록이 없습니다")).toBeInTheDocument();
  });

  it("says the read failed rather than showing an empty memory", async () => {
    getReviewIssuesStrict.mockRejectedValue(new Error("down"));
    renderMemory();
    expect(await screen.findByText("기록을 불러오지 못했습니다")).toBeInTheDocument();
  });
});

describe("고객운영 메모리 — accessibility", () => {
  it("has no axe violations", async () => {
    const { container } = renderMemory("/memory/issue-1");
    await openIssue();
    await expectNoAxeViolations(container);
  });
});

describe("고객운영 메모리 — the issue's opportunities live beside its evidence (Opportunity Engine v1)", () => {
  it("draws the 개선 기회 section for the selected issue, read by that issue's id", async () => {
    getOpportunitiesStrict.mockResolvedValue([
      {
        issueId: SURGING.id, kind: "FAQ_SUPPLEMENT", kindLabelKo: "FAQ 보완", status: "OPEN", statusLabelKo: "검토 전",
        issueTitle: SURGING.title, aspect: "접착", problem: "탈락", severity: "HIGH", evidenceCount: 12,
        firstEvidenceOn: null, lastEvidenceOn: null, changeLabelsKo: ["급증"], productId: "p-1", productName: "전선몰딩 1호",
        whyKo: ["「접착 탈락」 근거 리뷰 12건."], recommendationKo: "'접착' 관련 안내를 자주 묻는 질문에 추가하는 것을 검토하세요.",
        evidenceTo: `/memory/${SURGING.id}`, knowledge: { scope: "PRODUCT", scopeLabelKo: "이 상품의 상품 지식", type: "USAGE", topicLabelKo: "접착", sources: 2, mentions: 0, excerpts: [] },
        nextActionKo: "FAQ 초안 준비", draft: null, history: [], decidedAt: null,
      },
    ]);
    renderMemory(`/memory/${SURGING.id}`);
    const section = await screen.findByRole("region", { name: "개선 기회" });
    expect(getOpportunitiesStrict).toHaveBeenCalledWith({ issueId: SURGING.id, includeDismissed: true });
    expect(await within(section).findByText(/자주 묻는 질문에 추가/)).toBeTruthy();
    expect(await within(section).findByRole("button", { name: "FAQ 초안 준비" })).toBeTruthy();
    // On the evidence surface itself, the card does not link back to the page it is on.
    expect(within(section).queryByText(/근거 리뷰 12건 보기/)).toBeNull();
  });

  it("says plainly when the issue yields no opportunity", async () => {
    renderMemory(`/memory/${SURGING.id}`);
    const section = await screen.findByRole("region", { name: "개선 기회" });
    expect(await within(section).findByText(/아직 제안할 개선 기회가 없습니다/)).toBeTruthy();
  });
});

describe("반복 문제 — the canonical reading (2026-10-05)", () => {
  /**
   * <b>단추는 하나이고, 그것을 보내는 필드 옆에 있다.</b> pane에서는 바닥에 고정돼 있었다 — 안쪽에서
   * 스크롤되는 열에서는 그래야 했다. 페이지는 스크롤이 페이지의 것이고 읽기가 결정으로 끝나므로,
   * 쓰는 자리와 보내는 자리가 떨어져 있을 이유가 없다. 여전히 하나다: 한 전이에 단추 둘은 볼 곳 둘이다.
   */
  it("keeps one control for the transition, beside the field it submits", async () => {
    renderMemory("/memory/issue-1");
    const decision = await screen.findByLabelText("판단과 조치");
    expect(within(decision).getByRole("button", { name: "조치 시작" })).toBeInTheDocument();
    expect(within(decision).getByLabelText(/무엇을 하기로 하셨나요/)).toBeInTheDocument();
    expect(screen.queryByTestId("pane-footer")).toBeNull();
    expect(screen.getAllByRole("button", { name: /조치/ })).toHaveLength(1);
  });

  it("sends the transition with the sentence written beside it", async () => {
    startReviewIssueAction.mockResolvedValue({ ...SURGING, lifecycleState: "ACTING", lifecycleLabelKo: "조치 중" });
    renderMemory("/memory/issue-1");
    const decision = await screen.findByLabelText("판단과 조치");
    fireEvent.change(within(decision).getByLabelText(/무엇을 하기로 하셨나요/), {
      target: { value: "접착 테이프 공급처를 바꿉니다." },
    });
    fireEvent.click(within(decision).getByRole("button", { name: "조치 시작" }));
    await waitFor(() =>
      expect(startReviewIssueAction).toHaveBeenCalledWith("issue-1", "접착 테이프 공급처를 바꿉니다."),
    );
  });

  /** 다음 걸음이 reviewnary의 것인 상태에는 단추가 없다 — `nextActionKo`가 null을 주는 바로 그 셋. */
  it("offers nothing to press where the seller has no move", async () => {
    getReviewIssueDetailStrict.mockResolvedValue({ ...DETAIL, issue: IMPROVED });
    renderMemory("/memory/issue-2");
    const detail = await openIssue();
    expect(within(detail).queryByRole("button")).toBeNull();
    expect(await screen.findByText(/조치 이후 리뷰 변화를 지켜보고 있어요/)).toBeInTheDocument();
  });

  /**
   * 어떤 상태에 언제부터 있는가 — 기록이 말한 날에서 읽고, 지금에서 계산하지 않는다. 아무것도 기록하지
   * 않은 「…부터」는 화면이 지어낸 역사다. pane에서는 고정된 단추 옆에 있던 문장이고, 페이지에서는
   * 그 상태를 바꾸려는 블록이 들고 있다.
   */
  it("states the state and the day the trail says it began", async () => {
    renderMemory("/memory/issue-1");
    const decision = await screen.findByLabelText("판단과 조치");
    expect(decision).toHaveTextContent("확인 필요 · 2026-07-01부터");
  });

  /**
   * <b>언제 들어왔고 어디서 반복되는가는 한 띠에 나란히 선다</b> (승인된 mockup — Sentry의 Trends &
   * Aggregates). 세로로 쌓으면 같은 질문의 두 축을 함께 보는 일이 스크롤이 된다.
   */
  it("stands the two aggregate axes side by side, above the evidence", async () => {
    renderMemory("/memory/issue-1");
    const detail = await openIssue();
    const when = await within(detail).findByRole("region", { name: "언제 들어왔나" });
    const where = await within(detail).findByRole("region", { name: "어디서 반복되나" });
    const evidence = within(detail).getByRole("region", { name: "근거" });
    // 한 부모 아래 두 열 — 하나가 다른 하나 안에 들어 있지 않다.
    expect(when.parentElement).toBe(where.parentElement);
    expect(when.compareDocumentPosition(evidence) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // 별점 분포는 「언제」 쪽에 있고 상품 분포는 「어디서」 쪽에 있다.
    expect(within(when).getByRole("region", { name: "어떤 별점에서 나왔나" })).toBeInTheDocument();
    expect(within(where).getByText(/리뷰 1,761건 중 16건이 이 문제를 말했습니다/)).toBeInTheDocument();
  });

  /** 열은 하나다 — 리뷰 상세의 canonical baseline과 같은 읽기이고, 안쪽 스크롤은 없다. */
  it("reads as one 900px column with no pane scroller of its own", async () => {
    renderMemory("/memory/issue-1");
    const page = await openIssue();
    expect(page.className).toContain("max-w-[900px]");
    expect(page.querySelector('[class*="overflow-y-auto"]')).toBeNull();
    expect(screen.queryByTestId("master-list")).toBeNull();
  });

  it("filters the list by the group a tab names, and counts every group including the empty one", async () => {
    renderMemory();
    const tabs = await screen.findByRole("group", { name: "상태 필터" });
    expect(within(tabs).getByRole("button", { name: "지켜보는 중 0" })).toBeInTheDocument();
    fireEvent.click(within(tabs).getByRole("button", { name: "개선됨 1" }));
    const list = await screen.findByLabelText("반복 이슈 목록");
    await waitFor(() => expect(within(list).getAllByRole("link")).toHaveLength(1));
    expect(within(list).getByRole("link", { name: /재단 중 파손/ })).toBeInTheDocument();
  });

  /**
   * <b>The 분류 menu reads the issue's own aspect.</b> `ReviewIssueView.aspect` is half the signature the
   * memory groups by; `item_analyses.category` is the per-review keyword tally the 리뷰 screen stopped
   * showing. A screen whose objects are issues may not be filtered by a review-level classifier's words.
   */
  it("offers the issues' own 분류 values and filters on them", async () => {
    getReviewIssuesStrict.mockResolvedValue([SURGING, issue({ id: "issue-3", title: "포장이 찢어져 왔어요", aspect: "포장" })]);
    renderMemory();
    const menu = await screen.findByLabelText("분류");
    expect(within(menu).getByRole("option", { name: "접착" })).toBeInTheDocument();
    expect(within(menu).getByRole("option", { name: "포장" })).toBeInTheDocument();
    fireEvent.change(menu, { target: { value: "포장" } });
    const list = await screen.findByLabelText("반복 이슈 목록");
    await waitFor(() => expect(within(list).getAllByRole("link")).toHaveLength(1));
    expect(within(list).getByRole("link", { name: /포장이 찢어져/ })).toBeInTheDocument();
  });

  /**
   * The trend is a count of the dates the detail read returned. It may not become a second opinion about
   * the direction — that judgement is the server's, and this pane already prints it in words above.
   */
  it("draws the evidence month by month and claims no rate or direction", async () => {
    renderMemory("/memory/issue-1");
    const chart = await screen.findByRole("img", { name: /월별 근거 건수/ });
    expect(chart.getAttribute("aria-label") ?? "").toContain("가장 많았던 달 2건");
    // 평균 is allowed here and only here: 「이전 8주 평균」 is the server's own measured baseline for a
    // surge it declared. What the chart may not add is a share of anything.
    const signal = await screen.findByLabelText("변화와 신호");
    expect(signal.textContent ?? "").not.toMatch(/%|퍼센트|비율/);
  });

  /** Representative evidence, not eighteen quotes above a decision. The rest is one disclosure away. */
  it("stands two customer sentences in the reading and keeps the rest behind one disclosure", async () => {
    getReviewIssueDetailStrict.mockResolvedValue({
      ...DETAIL,
      evidence: [
        ...DETAIL.evidence,
        { reviewId: "rev-4", unitOrdinal: 0, occurredOn: "2026-07-02", productId: null, productName: null, rating: 2, quote: "네 번째 이야기." },
      ],
    });
    renderMemory("/memory/issue-1");
    const evidence = await screen.findByRole("region", { name: "근거" });
    const quotes = within(evidence).getAllByText(/^“.*”$/);
    expect(quotes).toHaveLength(3);
    // 접힘 뒤에 남는 것은 인용뿐이다 — 상품은 위 띠에 전부 서 있다.
    expect(within(evidence).getByText(/근거 4건 모두 보기/)).toBeInTheDocument();
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
