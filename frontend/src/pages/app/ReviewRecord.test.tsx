// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ReviewRecord, rangeLabel } from "./ReviewRecord";
import { expectNoAxeViolations } from "../../test/axe";
import type { ChannelReviewDetailView, ChannelReviewPageView, ReviewRecordPageView } from "../../lib/types";
import type { ReviewAccount } from "../../lib/reviewAccounts";

const getChannelReviewsStrict = vi.fn();
const getChannelReviewStrict = vi.fn();
const getReplyWork = vi.fn();
const decisions = vi.fn<() => Promise<{ total: number; rows: unknown[] }>>(async () => ({ total: 0, rows: [] }));
const getDecisionContext = vi.fn<(...args: unknown[]) => Promise<unknown>>();
const getDecisionLog = vi.fn<(...args: unknown[]) => Promise<unknown>>();
const getReviewReplyPrep = vi.fn();
const recordBehavior = vi.fn<(...args: unknown[]) => Promise<void>>(async () => undefined);
const correctTriage = vi.fn<(...args: unknown[]) => Promise<unknown>>();
const recordAction = vi.fn<(...args: unknown[]) => Promise<void>>(async () => undefined);
const withdrawCorrection = vi.fn<(...args: unknown[]) => Promise<void>>(async () => undefined);

vi.mock("../../lib/apiClient", () => ({
  api: {
    // The ONE record read; the fixtures below are written as the channel page they always were and served
    // through `asRecord` — the organisation's record over one channel is that channel's record (ReviewRecordIT).
    getReviewRecordStrict: async (params: unknown) => asRecord(await getChannelReviewsStrict("acc-1", params)),
    getReviewWorkspace: (reviewId: string) => getChannelReviewStrict("acc-1", reviewId),
    getReviewDecisionContext: (reviewId: string) => getDecisionContext(reviewId),
    getReviewDecisionLog: (reviewId: string) => getDecisionLog(reviewId),
    getReviewWorkStrict: async () => ({ attentionTotal: 0, attention: [], committed: [] }),
    getCustomerOperationsDecisions: async () => decisions(),
    // Only reached by the case reading of the pane, which this file does not test the inside of.
    getOperationsCase: async () => { throw new Error("not under test"); },
    startChannelReviewLocateRun: vi.fn(),
    recordChannelReviewTriageBehavior: (...args: unknown[]) => recordBehavior(...args),
    correctChannelReviewTriage: (...args: unknown[]) => correctTriage(...args),
    withdrawChannelReviewTriageCorrection: (...args: unknown[]) => withdrawCorrection(...args),
    recordChannelReviewTriageAction: (...args: unknown[]) => recordAction(...args),
    getReplyWork: (...args: unknown[]) => getReplyWork(...args),
    getReviewReplyPrep: (...args: unknown[]) => getReviewReplyPrep(...args),
  },
  getToken: () => "token",
}));

const PAGE: ChannelReviewPageView = {
  page: 0,
  size: 20,
  total: 2,
  newCount: 1,
  lastImportAt: "2026-08-14T05:00:00Z",
  lastImportComplete: true,
  aiPilotEnabled: false,
  channel: { channelCode: "COUPANG", aiTriage: true, originalLocate: "LOCATE_RUN", replySupported: false, replyFlowExists: false },
  triageSummary: {
    needsAttention: 1,
    watch: 0,
    fyi: 1, aiAttention: 0,
    repeatedCategories: [{ category: "설치", count: 11 }],
  },
  items: [
    {
      id: "r1",
      writtenOn: "2026-08-11",
      rating: 5,
      negative: false,
      preview: "배송도 빠르고 포장도 꼼꼼했어요",
      productName: "무선 이어폰",
      productId: "15411270785",
      vendorItemId: "81234567890",
      mediaCount: 2,
      textless: false,
      isNew: true,
      triage: { tier: "FYI", reason: "5점", tags: [], recommendedAction: null },
    aiMark: null,
    sellerCorrection: null,
    },
    {
      id: "r2",
      writtenOn: "2026-08-01",
      rating: 1,
      negative: true,
      preview: "생각보다 크기가 작아서 아쉬웠습니다",
      productName: "무선 이어폰",
      productId: "15411270785",
      vendorItemId: null,
      mediaCount: 0,
      textless: false,
      isNew: false,
      triage: {
        tier: "NEEDS_ATTENTION",
        reason: "1점 · 설치 · 같은 분류 11건",
        tags: ["설치"],
        recommendedAction: "같은 분류의 상품평이 반복됩니다. 상품·포장 상태를 확인해 보세요.",
      },
    aiMark: null,
    sellerCorrection: null,
    },
  ],
};

/**
 * The decision context the pane reads — the same GET 리뷰 처리 makes, and the record writes nothing with it.
 * Empty of repeats and of knowledge by default, so a test that cares about one says so itself.
 */
const CONTEXT = {
  reviewId: "r1",
  decisionRef: "review:r1",
  currentDecision: null,
  channelCode: "COUPANG",
  productId: "p-1",
  productName: "무선 이어폰",
  repeatedProblems: [],
  productSignal: { reviews: 416, negativeReviews: 2 },
  knowledge: { productSources: 0, orgSources: 0, productTitles: [], openAsks: 0 },
};

/** A standing seller correction, as the read carries it. */
function correctionView(tier: "NEEDS_ATTENTION" | "WATCH" | "FYI") {
  return {
    reviewId: "r1",
    correctedTier: tier,
    reasonCode: null,
    systemTier: "NEEDS_ATTENTION" as const,
    systemSource: "RULES" as const,
    correctedAt: "2026-09-11T00:00:00Z",
    changeCount: 1,
  };
}

const DETAIL: ChannelReviewDetailView = {
  id: "r1",
  sellerAccountId: "acc-1",
  replyUnavailableReason: null,
  writtenOn: "2026-08-11",
  rating: 5,
  negative: false,
  body: "배송도 빠르고 포장도 꼼꼼했어요. 다음에도 구매할게요.",
  bodyRedacted: false,
  productName: "무선 이어폰",
  mediaCount: 2,
  textless: false,
  isNew: true,
  triage: { tier: "FYI", reason: "5점", tags: [], recommendedAction: null },
  aiMark: null,
  sellerCorrection: null,
  locateTarget: {
    productId: "15411270785",
    vendorItemId: "81234567890",
    writtenOn: "2026-08-11",
    rating: 5,
  },

  replyWork: null,
};

/** Reports the router's current location so a test can assert what the URL says. */
function LocationProbe() {
  const { pathname, search } = useLocation();
  return <output data-testid="location">{`${pathname}${search}`}</output>;
}

const TARGETS = [
  { account: { id: "acc-1" }, channel: { code: "COUPANG", nameKo: "쿠팡" }, label: "쿠팡" },
] as unknown as ReviewAccount[];

/** A channel page, read as the organisation's record over that one channel. */
function asRecord(page: ChannelReviewPageView): ReviewRecordPageView {
  const code = page.channel.channelCode;
  return {
    page: page.page,
    size: page.size,
    total: page.total,
    newCount: page.newCount,
    aiPilotEnabled: page.aiPilotEnabled,
    channels: [code],
    triageSummary: page.triageSummary,
    items: page.items.map((review) => ({ channelCode: code, channelNameKo: null, review })),
    channelFacts: [
      {
        channelCode: code,
        channelNameKo: null,
        accountId: "acc-1",
        capability: page.channel,
        lastImportAt: page.lastImportAt,
        lastImportComplete: page.lastImportComplete,
      },
    ],
    outsideVisibleChannels: 0,
  };
}

function renderPage(path = "/reviews") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/reviews"
          element={
            <>
              <ReviewRecord targets={TARGETS} />
              <LocationProbe />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

/** The list and the detail side by side, as the record is drawn at 1200px and up. */
function stubWide(): () => void {
  const original = window.matchMedia;
  window.matchMedia = ((query: string) => ({
    matches: true,
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

let restoreWide: () => void = () => undefined;
afterEach(() => restoreWide());

beforeEach(() => {
  vi.clearAllMocks();
  restoreWide = stubWide();
  getChannelReviewsStrict.mockResolvedValue(PAGE);
  getChannelReviewStrict.mockResolvedValue(DETAIL);
  decisions.mockResolvedValue({ total: 0, rows: [] });
  getDecisionContext.mockResolvedValue(CONTEXT);
  getDecisionLog.mockResolvedValue([]);
  getReplyWork.mockResolvedValue({
    sellerAccountId: "acc-1",
    channel: "NAVER",
    coverage: "COVERED",
    todo: [],
    recentlyReported: [],
  });
});

/*
  <b>pane이 없어졌으므로 pane의 계약도 여기 있지 않다</b> (리뷰 canonical mockup, 2026-10-05).

  <p>이 파일에 있던 두 describe — 「reply work on the 리뷰 screen (A6)」와 「the decision workspace in the
  record's pane」 — 는 440px pane이 읽어 주던 것을 지켰다. 그 pane은 사라졌고, 같은 읽기를 같은 컴포넌트가
  `/reviews/reply/:id`에서 더 넓게 한다. 반복 신호 · 이 상품에 대해 우리가 아는 것 · 기록 · 초안은 전부
  {@code ReviewReplyTask.test.tsx}가 이미 지키고 있으므로 옮겨 적지 않았다. pane에만 있던 셋 —
  채널의 답변 등록 상태, 「리뷰 원문 보기」, AI pilot의 REVIEW_OPENED silver — 은 그 화면으로 따라갔고,
  계약도 거기 있다.
*/

describe("deep-link seams the home relies on", () => {
  it("?tier=NEEDS_ATTENTION opens the list under that filter — the same filter whose total the home tile shows", async () => {
    renderPage("/reviews?tier=NEEDS_ATTENTION");
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    expect(getChannelReviewsStrict).toHaveBeenCalledWith("acc-1", expect.objectContaining({ tier: "NEEDS_ATTENTION" }));
  });

  it("ignores an unknown tier value rather than sending it to the server, and scrubs it from the URL", async () => {
    renderPage("/reviews?tier=WHATEVER");
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    expect(getChannelReviewsStrict).toHaveBeenCalledWith("acc-1", expect.objectContaining({ tier: "NEEDS_ATTENTION" }));
    expect(screen.getByTestId("location")).toHaveTextContent("/reviews");
    expect(screen.getByTestId("location").textContent).not.toContain("tier=");
  });

  it("the URL is the open group, both ways: pressing a group header writes ?tier and asks for that tier", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    await userEvent.click(screen.getByRole("button", { name: /^▸?\s*지켜보기/ }));
    expect(screen.getByTestId("location")).toHaveTextContent("/reviews?tier=WATCH");
    expect(getChannelReviewsStrict).toHaveBeenLastCalledWith("acc-1", expect.objectContaining({ tier: "WATCH" }));
  });

  /**
   * <b>행은 고르는 것이 아니라 가는 것이다</b> (리뷰 canonical mockup, 2026-10-05). The row used to write
   * `?review` and open a 440px pane beside the list. There is no pane: the row is the way into the review's
   * own workspace, which is where every one of those readings already lives and is the only place they can
   * be changed.
   */
  it("a row leads to that review's workspace, not to a pane beside the list", async () => {
    renderPage();
    const row = (await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요")).closest("a")!;
    expect(row).toHaveAttribute("href", "/reviews/reply/r1?from=record");
    expect(getChannelReviewStrict).not.toHaveBeenCalled();
  });

  it("orders the groups as the workflow does — 확인 필요, 지켜보기, 참고, and offers no 전체", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    const list = screen.getByRole("region", { name: "목록" });
    const labels = within(list).getAllByRole("button").map((b) => b.textContent?.replace(/[▾▸]/g, "").replace(/\s*[\d,]+$/, "").trim());
    expect(labels).toEqual(["확인 필요", "지켜보기", "참고"]);
  });

  /** 확인 필요 is where a seller's attention belongs, so it is what the screen opens on when nothing says otherwise. */
  it("opens on 확인 필요 when the URL names no group", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    expect(getChannelReviewsStrict).toHaveBeenCalledWith("acc-1", expect.objectContaining({ tier: "NEEDS_ATTENTION" }));
  });
});

describe("the channel review record", () => {
  it("lists what was collected, and marks what the last import brought in", async () => {
    renderPage();

    expect(await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요")).toBeInTheDocument();
    expect(screen.getByText("총 2개")).toBeInTheDocument();
    expect(screen.getByText("새로 들어온 1개")).toBeInTheDocument();
    // One row came in with the last import — the list marks exactly that one.
    expect(within(screen.getByRole("region", { name: "목록" })).getAllByText("새 리뷰")).toHaveLength(1);
  });

  it("offers no way to reply, because the channel has none", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    for (const label of ["답변", "답글", "초안", "등록하기"]) {
      expect(screen.queryByRole("button", { name: new RegExp(label) })).toBeNull();
    }
  });

  it("asks the backend for the complaints first when the seller chooses that order", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    await userEvent.selectOptions(screen.getByLabelText("정렬"), "lowest");

    await waitFor(() =>
      expect(getChannelReviewsStrict).toHaveBeenLastCalledWith(
        "acc-1",
        expect.objectContaining({ sort: "lowest" }),
      ),
    );
  });


});

describe("a review the buyer rated without writing", () => {
  it("says what it is, rather than implying reviewnary lost the text", async () => {
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      items: [{ ...PAGE.items[0]!, preview: null, textless: true }],
    });
    renderPage();

    expect(await screen.findByText("별점만 남긴 리뷰")).toBeInTheDocument();
    expect(screen.queryByText(/표시할 수 있는 본문이 없습니다/)).toBeNull();
  });

});

describe("the page refuses to imply what it does not know", () => {
  it("warns in words when the last import did not reach the end of the list", async () => {
    getChannelReviewsStrict.mockResolvedValue({ ...PAGE, lastImportComplete: false });
    renderPage();

    expect(await screen.findByText(/목록 끝까지 확인되지 않은 상태로 끝났습니다/)).toBeInTheDocument();
  });

  it("does not warn when the import covered the list", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    expect(screen.queryByText(/목록 끝까지 확인되지 않은/)).toBeNull();
  });

  it("shows nothing rather than an invented list when the read fails", async () => {
    getChannelReviewsStrict.mockRejectedValue(new Error("backend down"));
    renderPage();

    // Before a page has loaded there is no channel, so the product word (리뷰), not Coupang's.
    expect(await screen.findByText("리뷰를 불러오지 못했습니다")).toBeInTheDocument();
    expect(screen.queryByText("배송도 빠르고 포장도 꼼꼼했어요")).toBeNull();
  });

  it("says the record is empty and how to fill it, rather than looking broken", async () => {
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      total: 0,
      newCount: 0,
      items: [],
      triageSummary: { needsAttention: 0, watch: 0, fyi: 0, repeatedCategories: [] },
    });
    renderPage();

    expect(await screen.findByText("아직 수집된 리뷰가 없습니다")).toBeInTheDocument();
  });
});

describe("no buyer appears, because none is stored", () => {
  it("renders nothing from a field it does not know about, even if one arrives", async () => {
    // The backend has no author field and refuses one on the wire; this is the last line of the same
    // rule — a page that spread its response into the DOM would render whatever turned up.
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      items: [{ ...PAGE.items[0], author: "김서연" }, PAGE.items[1]],
    });
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    expect(document.body.textContent).not.toContain("김서연");
  });
});

/**
 * A seller with more 상품평 than one screenful used to be shown the first 20 under a "총 22개" chip, with
 * nothing on the page saying a second page existed and no control that could reach it.
 */
describe("a list longer than one screen can be walked", () => {
  const LONG = { ...PAGE, total: 42, size: 20 };

  it("says which slice of the list is on screen", () => {
    // <b>The denominator left this label</b> (product-owner decision, 2026-10-01). It was the pressed tier
    // chip's own number, ~40px above — 「전체 4599」 and then 「… · 총 4599개」 — so the one thing a seller
    // reads this hint for, WHERE they are, was the quieter half of it. The total is not lost and did not
    // move far: it is on the chip, where it is also a control.
    expect(rangeLabelOf({ ...LONG, page: 1 })).toBe("21–22번째");
    // Derived from what the RESPONSE said, so a server that clamped the size cannot be misdescribed.
    expect(rangeLabelOf({ ...LONG, page: 0, size: 2 })).toBe("1–2번째");
    expect(rangeLabelOf(null)).toBe("0개 표시 중");
  });

  it("asks the backend for the next page when the seller asks for it", async () => {
    getChannelReviewsStrict.mockResolvedValue(LONG);
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    await userEvent.click(screen.getByRole("button", { name: "다음" }));

    await waitFor(() =>
      expect(getChannelReviewsStrict).toHaveBeenLastCalledWith("acc-1", {
        // 확인 필요 우선 is the default as of Review Triage v1 — the question the seller opens this
        // screen with is "what first", and the newest row was answering a different one.
        sort: "attention",
        tier: "NEEDS_ATTENTION",
        page: 1,
        size: 20,
      }),
    );
  });

  it("offers no paging at all when the whole list already fits", async () => {
    getChannelReviewsStrict.mockResolvedValue(PAGE);
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    expect(screen.queryByRole("button", { name: "다음" })).toBeNull();
  });

  /**
   * Two controls now change the query, so two reads can be in flight; the slower one landing second used to
   * install rows that neither the pressed sort button nor the page label described.
   */
  it("ignores a superseded response, so the rows always match the controls", async () => {
    const slowPage3 = { ...LONG, page: 2, items: [{ ...PAGE.items[0]!, id: "stale", preview: "지나간 응답" }] };
    const fastLowest = { ...LONG, page: 0, items: [{ ...PAGE.items[1]!, id: "fresh", preview: "새 응답" }] };
    let releaseSlow: (v: unknown) => void = () => {};
    getChannelReviewsStrict
      .mockResolvedValueOnce(LONG)
      .mockImplementationOnce(() => new Promise((res) => { releaseSlow = () => res(slowPage3); }))
      .mockResolvedValueOnce(fastLowest);

    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    await userEvent.click(screen.getByRole("button", { name: "다음" }));
    await userEvent.selectOptions(screen.getByLabelText("정렬"), "lowest");
    await screen.findByText("새 응답");
    releaseSlow(null);

    // The overtaken page-3 read resolves last and must write nothing.
    await waitFor(() => expect(screen.queryByText("지나간 응답")).toBeNull());
    expect(screen.getByText("새 응답")).toBeInTheDocument();
  });

  it("labels the page from the response, so the label cannot describe rows that are not there", () => {
    // The pager label and the range label are both read off the response; taken from local state the first
    // would advance the instant the button was pressed, over rows still describing the previous page.
    expect(rangeLabelOf({ ...LONG, page: 2 })).toBe("41–42번째");
  });

  it("returns to the first page when the order changes, rather than keeping a position that no longer means the same thing", async () => {
    getChannelReviewsStrict.mockResolvedValue(LONG);
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    await userEvent.click(screen.getByRole("button", { name: "다음" }));
    await waitFor(() => expect(getChannelReviewsStrict).toHaveBeenLastCalledWith("acc-1", expect.objectContaining({ page: 1 })));

    await userEvent.selectOptions(screen.getByLabelText("정렬"), "lowest");

    await waitFor(() =>
      expect(getChannelReviewsStrict).toHaveBeenLastCalledWith("acc-1", {
        sort: "lowest",
        tier: "NEEDS_ATTENTION",
        page: 0,
        size: 20,
      }),
    );
  });
});

/**
 * Review Triage v1 — the list saying what to look at first, and why.
 *
 * The test that matters here is {@code the tags never re-rank anything}: the tier arrives from the
 * backend, and the frontend must render it rather than re-derive one from the tags beside it.
 * `contracts/review-eval/naver/v1/RUBRIC.md` §5 forbids surfacing an unmeasured text detector, and
 * the frontend is exactly where such a thing would be added by accident — a `tags.includes("파손")`
 * check that bumps a row's colour reads like polish and is the gated thing.
 */
describe("triage", () => {
  /**
   * <b>분류는 묶음이 말하고, 이유는 리뷰가 말한다</b> (리뷰 canonical mockup, 2026-10-05). The tier used to be
   * a chip on every row inside a list already filtered to that tier — the same word, twenty times, under a
   * tab that said it once. The group header says it once, with the record's own count beside it.
   */
  it("says which tier a review is in on the group header, not twenty times on the rows", async () => {
    renderPage();
    const list = await screen.findByRole("region", { name: "목록" });
    expect(within(list).getByRole("button", { name: /확인 필요\s*1/ })).toBeInTheDocument();
    const row = screen.getByText("배송도 빠르고 포장도 꼼꼼했어요").closest("li")!;
    expect(within(row).queryByText("확인 필요")).toBeNull();
    expect(within(row).queryByText("참고")).toBeNull();
  });


  /**
   * <b>조직 전체 자동분류 집계는 이 화면의 것이 아니다</b> (product-owner decision, 2026-10-03).
   *
   * <p>「같은 자동 분류가 많은 리뷰 — 배송 946 · 설치 218 · 가격 114」 stood above the list with its criterion
   * folded under it. It is a statement about the whole record, not about any row, and it stood where the
   * first customer sentence belongs. The per-review half of the same fact is untouched and is asserted in
   * the test above: the row's own triage reason still says 「1점 · 설치 · 같은 분류 11건」 in the pane.
   */
  it("keeps the org-wide classification tally off the list — the local fact on the review stays", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    expect(screen.queryByText("같은 자동 분류가 많은 리뷰")).toBeNull();
    expect(screen.queryByText(/설치 11건/)).toBeNull();
    // Nothing above the rows: the first thing under the filter is the list itself.
    expect(screen.queryByText(/자동 분류한 것이라 정확하지 않을 수 있습니다/)).toBeNull();
  });

  it("asks the backend to narrow to a tier, and keeps that filter across a sort change", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    await userEvent.click(screen.getByRole("button", { name: /^확인 필요 1$/ }));
    await waitFor(() =>
      expect(getChannelReviewsStrict).toHaveBeenLastCalledWith(
        "acc-1",
        expect.objectContaining({ tier: "NEEDS_ATTENTION", page: 0 }),
      ),
    );

    await userEvent.selectOptions(screen.getByLabelText("정렬"), "newest");
    await waitFor(() =>
      expect(getChannelReviewsStrict).toHaveBeenLastCalledWith(
        "acc-1",
        // The filter must survive: a seller who narrowed and then re-sorted wants the newest of
        // what they narrowed to, not the filter silently dropped with its chip still lit.
        expect.objectContaining({ sort: "newest", tier: "NEEDS_ATTENTION" }),
      ),
    );
  });

  it("does not report an empty filter as an empty record", async () => {
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      items: [],
      // The record still holds two reviews; the operator just narrowed to a tier holding none.
      triageSummary: { needsAttention: 0, watch: 0, fyi: 2, repeatedCategories: [] },
    });
    renderPage();
    await waitFor(() => expect(getChannelReviewsStrict).toHaveBeenCalled());

    await userEvent.click(screen.getByRole("button", { name: /확인 필요\s*0/ }));

    expect(await screen.findByText(/확인 필요에 해당하는 리뷰가 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText("아직 수집된 리뷰가 없습니다")).toBeNull();
  });

  it("renders the rows in the order the backend sent them", async () => {
    // The chip test below covers the LABEL. This covers the ranking, which is the other half of
    // "must never be used here to re-rank" — a client-side `.sort()` on tag count passed the whole
    // suite before this existed. The fixture is built so tag-count order is the REVERSE of the
    // server's order, so any re-rank from body-derived material flips it.
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      items: [
        { ...PAGE.items[0], id: "first", preview: "서버가 먼저 준 줄", triage: { ...PAGE.items[0].triage, tags: [] } },
        {
          ...PAGE.items[1],
          id: "second",
          preview: "서버가 나중에 준 줄",
          triage: { ...PAGE.items[1].triage, tags: ["설치", "품질", "배송"] },
        aiMark: null,
        sellerCorrection: null,
        },
      ],
    });
    renderPage();
    await screen.findByText("서버가 먼저 준 줄");

    const previews = screen
      .getAllByText(/서버가 (먼저|나중에) 준 줄/)
      .map((n) => n.textContent);
    expect(previews).toEqual(["서버가 먼저 준 줄", "서버가 나중에 준 줄"]);
  });

  it("counts the whole record in the header, even while a filter narrows the list", async () => {
    // page.total narrows with the filter; newCount and the tier counts stay channel-wide. Rendering
    // the filtered total beside them put "총 1개" next to "새로 들어온 1개" — two totals on one line.
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      total: 1,
      newCount: 1,
      items: [PAGE.items[1]],
      triageSummary: { needsAttention: 1, watch: 3, fyi: 18, repeatedCategories: [] },
    });
    renderPage();
    await screen.findByText("생각보다 크기가 작아서 아쉬웠습니다");

    // The WHOLE record, which is not the filtered count — that distinction is this test's point.
    expect(screen.getByText("총 22개")).toBeInTheDocument();
    expect(screen.queryByText("총 1개")).toBeNull();
    // …and the range label under the list still describes the slice actually on screen.
    expect(screen.getByText("1–1번째")).toBeInTheDocument();
  });

  it("renders the counts the backend sent, never ones re-derived from the tags", async () => {
    // A 1★ row whose body-derived tags scream 파손, and whose tier says 참고. If the frontend ever
    // re-ranks from tags, 확인 필요 stops reading 0 — which is the whole point.
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      total: 1,
      triageSummary: { needsAttention: 0, watch: 0, fyi: 1, repeatedCategories: [] },
      items: [
        {
          ...PAGE.items[1],
          triage: { tier: "FYI" as const, reason: "1점 · 품질", tags: ["품질", "파손"], recommendedAction: null },
          aiMark: null,
          sellerCorrection: null,
        },
      ],
    });
    renderPage("/reviews?tier=FYI");
    await screen.findByText("생각보다 크기가 작아서 아쉬웠습니다");

    const list = screen.getByRole("region", { name: "목록" });
    expect(within(list).getByRole("button", { name: /참고\s*1/ })).toBeInTheDocument();
    expect(within(list).getByRole("button", { name: /확인 필요\s*0/ })).toBeInTheDocument();
  });
});

describe("the AI pilot's mark and the feedback spine (RUBRIC v2 §13.7)", () => {
  const MARK = {
    classifierVersion: "llm-triage/v1+openai:gpt-5-2025-08-07+triage-prompt/v4+schema/v1+tdefault+out4000+effort:low+additive-guard/v1",
    reasonCode: "PRAISE_WITH_CONCESSION",
    predictedAt: "2026-08-17T00:00:00Z",
  };

  it("renders 판매자 확인 필요 on the row BESIDE the rules tier the group header carries", async () => {
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      aiPilotEnabled: true,
      triageSummary: { ...PAGE.triageSummary, needsAttention: 2, aiAttention: 1 },
      items: [{ ...PAGE.items[0], aiMark: MARK }, PAGE.items[1]],
    });
    renderPage();
    const row = (await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요")).closest("li")!;

    // The second judgement is NOT the tier's word (RUBRIC v2 §13.7 — the seller must be able to tell the
    // two apart), and the mechanism is named in neither: 「AI」 is a fact about how reviewnary is built,
    // not about this review. The rules tier is the group this row sits in, said once on its header.
    expect(within(row).getByText("판매자 확인 필요")).toBeInTheDocument();
    expect(within(row).queryByText(/AI/)).toBeNull();
    const list = screen.getByRole("region", { name: "목록" });
    expect(within(list).getByRole("button", { name: /확인 필요\s*2/ })).toBeInTheDocument();
  });

  it("an org NOT opted in gets the pre-pilot list: no silver, even if a mark arrived", async () => {
    // The backend sends no marks for such an org; if one did arrive, the silver must still be absent,
    // because aiPilotEnabled — not the presence of marks — is the switch.
    getChannelReviewsStrict.mockResolvedValue({ ...PAGE, aiPilotEnabled: false, items: [{ ...PAGE.items[0], aiMark: MARK }, PAGE.items[1]] });
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    expect(recordBehavior).not.toHaveBeenCalled();
  });


  it("shows nothing about AI on a row without a mark", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    expect(screen.queryByText("판매자 확인 필요")).toBeNull();
    expect(screen.queryByText(/내용을 읽어 보니/)).toBeNull();
  });





  it("a corrected row says so in the queue, and does not move", async () => {
    // Requirement 6, on the list side. The chip is quiet on purpose: 확인 필요 is emphasised because
    // the worklist is ORDERED by it, and a correction reorders nothing.
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      items: [{ ...PAGE.items[0], sellerCorrection: correctionView("FYI") }, PAGE.items[1]],
    });
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    expect(screen.getByText("판매자 수정 참고")).toBeInTheDocument();
    const rows = within(screen.getByRole("region", { name: "목록" })).getAllByRole("link").map((b) => b.textContent ?? "");
    expect(rows.findIndex((t) => t.includes("배송도 빠르고 포장도 꼼꼼했어요")))
      .toBeLessThan(rows.findIndex((t) => t.includes(PAGE.items[1].preview ?? "")));
  });


  it("reports exposure and opening as silver, only for rows something raised, and never fails the list on it", async () => {
    recordBehavior.mockRejectedValue(new Error("down"));
    getChannelReviewsStrict.mockResolvedValue({
      ...PAGE,
      aiPilotEnabled: true,
      items: [{ ...PAGE.items[0], aiMark: MARK }, PAGE.items[1]],
    });
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    // Row 1 carries a mark → AI_ATTENTION_SHOWN (a claim the server verifies). Row 2 is a rules
    // 확인 필요 with no mark → nothing: a rendered rules row is not an event in contract v1.
    await waitFor(() => expect(recordBehavior).toHaveBeenCalledWith("acc-1", [
      { reviewId: "r1", kind: "AI_ATTENTION_SHOWN" },
    ]));
    // The recorder is DOWN, and the list is still there.
    expect(screen.getByText("배송도 빠르고 포장도 꼼꼼했어요")).toBeInTheDocument();
    expect(screen.queryByText(/불러오지 못했습니다/)).toBeNull();
  });
});

/**
 * <b>리뷰 canonical mockup — Linear Issues list</b> (product-owner decision, 2026-10-05).
 *
 * <p>목록의 계약은 네 줄이다. 행은 한 줄이고, 맨 앞이 고객의 문장이며, 그것만 잉크색이다. 나머지는
 * 오른쪽 한 덩어리의 주석이고, 묶음 머리가 분류를 말한다. 행 사이의 선은 없다 — 한 줄짜리 문장들이
 * 이미 목록이고, 선은 거기에 읽을 것이 하나 더 있다는 뜻이 아니었다.
 */
describe("리뷰 목록 — the Linear grouping (2026-10-05)", () => {
  it("the customer's sentence is the only ink on the row, and it comes first", async () => {
    renderPage();
    const sentence = await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    const row = sentence.closest("a")!;

    expect(sentence.className).toContain("text-ink");
    expect(sentence.className).toContain("text-base");
    // The sentence is the first child of the row; everything else trails it.
    expect(row.firstElementChild).toBe(sentence);
    // And the whole right-hand cluster is one weak block, not a column of competing ranks.
    const meta = row.lastElementChild as HTMLElement;
    expect(meta.className).toContain("text-xs");
    expect(meta.className).toContain("text-muted");
    // The rating is the compact glyph in the row; the sentence a screen reader hears is unchanged.
    expect(meta.textContent).toContain("★5");
    expect(within(meta).getByLabelText(/5점/)).toBeInTheDocument();
  });

  it("draws no rule between rows — the list is the sentences", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    const list = screen.getByRole("region", { name: "목록" });
    expect(list.innerHTML).not.toContain("divide-y");
  });

  it("keeps the open group's header in view while its rows scroll past", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    const header = screen.getByRole("button", { name: /확인 필요\s*1/ });
    expect(header.className).toContain("sticky");
  });

  it("a closed group draws its name and its count, and no rows", async () => {
    renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");
    const list = screen.getByRole("region", { name: "목록" });
    const watch = within(list).getByRole("button", { name: /지켜보기/ });
    expect(watch).toHaveAttribute("aria-expanded", "false");
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
  });
});

describe("accessibility", () => {
  it("has no axe violations", async () => {
    const { container } = renderPage();
    await screen.findByText("배송도 빠르고 포장도 꼼꼼했어요");

    await expectNoAxeViolations(container);
  });
});

/** The record's range label, as the channel record's used to be tested — through the org page it is computed on. */
function rangeLabelOf(page: ChannelReviewPageView | null): string {
  return rangeLabel(page ? asRecord(page) : null);
}
