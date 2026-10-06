// @vitest-environment jsdom
// The channel row's way into the 상품평 record: when it appears, what it says, and the two ways it
// is allowed to change. Layout cannot be measured in jsdom, so the responsiveness check is
// structural — the row's actions wrap, and nothing hides the entry at a breakpoint.
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ChannelList } from "./ChannelList";
import type {
  ChannelResponse,
  ConnectionStatusView,
  SellerAccountResponse,
} from "../../lib/types";

vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return { ...actual, useNavigate: () => vi.fn() };
});

const COUPANG: ChannelResponse = {
  id: "coupang-ch",
  code: "COUPANG",
  nameKo: "쿠팡",
  status: "AVAILABLE",
  dataBadges: [],
  lastSyncedAt: null,
  actionLabel: "관리",
  support: {
    fileUploadSupported: false,
    fileUploadDataTypes: [],
    autoCollectSupported: true,
    autoCollectDataTypes: ["ORDER_SUMMARY"],
    connectionCheckSupported: true,
    credentialSetupSupported: true, screenReadReviews: false,
  },
} as ChannelResponse;

const NAVER: ChannelResponse = { ...COUPANG, id: "naver-ch", code: "NAVER", nameKo: "네이버" };

const ACCOUNT: SellerAccountResponse = {
  id: "acc-cp",
  channelId: "coupang-ch",
  channelNameKo: "쿠팡",
  alias: null,
  connectionStatus: "CONNECTED",
  lastSyncedAt: "2026-08-15T00:00:00Z",
  fileUpload: false,
};

function health(over: Partial<ConnectionStatusView> = {}): ConnectionStatusView {
  return {
    sellerAccountId: "acc-cp",
    state: "CONNECTED",
    lastSuccessAt: "2026-08-15T00:00:00Z",
    consecutiveFailures: 0,
    lastError: null,
    lastSyncedAt: "2026-08-15T00:00:00Z",
    nextScheduledAt: null,
    expiry: null,
    ...over,
  } as ConnectionStatusView;
}

function renderList(options: {
  channels?: ChannelResponse[];
  accounts?: SellerAccountResponse[];
  health?: ConnectionStatusView;
  reviewCounts?: Map<string, number>;
} = {}) {
  return render(
    <MemoryRouter>
      <ChannelList
        channels={options.channels ?? [COUPANG]}
        accounts={options.accounts ?? [ACCOUNT]}
        health={new Map([["acc-cp", options.health ?? health()]])}
        statusLoading={false}
        reviewCounts={options.reviewCounts}
        onStartReviewSetup={() => undefined}
          onNotice={vi.fn()}
      />
    </MemoryRouter>,
  );
}

describe("ChannelList — the connection state word (A5)", () => {
  it("shows 연결됨 on a healthy connected row and 연결 필요 with 연결하기 when there is no account", () => {
    renderList();
    expect(screen.getByTestId("connection-state")).toHaveTextContent("연결됨");
    renderList({ channels: [NAVER], accounts: [] });
    const pills = screen.getAllByTestId("connection-state");
    expect(pills[pills.length - 1]).toHaveTextContent("연결 필요");
    expect(screen.getByRole("button", { name: "연결하기" })).toBeInTheDocument();
  });

  it("a failing row says what its timestamp IS — the last success, and that nothing has landed since", () => {
    // 「오류」 next to 「마지막 수집 1일 전」 were two sentences that cancel each other out: the time is the
    // last SUCCESS, and on the measured org there had been seven attempts since that produced nothing.
    // The column is now named for what is true of every row (「마지막 수집 성공」), so the row adds only the
    // half the column cannot carry. No vendor message is surfaced — the connectors' own strings carry
    // gateway codes and HTTP statuses.
    renderList({ health: health({ state: "DEGRADED", consecutiveFailures: 7, lastError: "…" }) });
    expect(screen.getByText(/마지막 성공 .*그 뒤로 수집되지 않았습니다/)).toBeInTheDocument();
    expect(screen.queryByText("마지막 수집")).toBeNull();
    expect(screen.queryByText(/GW\.|HTTP/)).toBeNull();
  });

  it("a healthy row carries the time alone — nothing is said about a gap that is not there", () => {
    renderList();
    // 「1주 전」이 아니라 실제 시각(주문·리포트와 같은 표기).
    expect(screen.getByText(/\d+월 \d+일 \d{2}:\d{2}/)).toBeInTheDocument();
    expect(screen.queryByText(/그 뒤로 수집되지 않았습니다/)).toBeNull();
  });

  /**
   * <b>수집 시각은 실제 시각이다</b> (2026-10-07). 「마지막 수집 1주 전」은 어제 읽은 것과 여드레 전에
   * 읽은 것을 같은 말로 덮는다 — 그 차이가 이 화면을 여는 이유인데도. 주문·리포트가 쓰는 그 표기다.
   */
  it("prints when collection last succeeded, not how long ago it feels", () => {
    renderList();
    expect(screen.getByText("8월 15일 09:00")).toBeInTheDocument();
    expect(screen.queryByText(/주 전|일 전|시간 전|개월 전/)).toBeNull();
  });

  /**
   * <b>세 가지 「없음」을 섞지 않는다.</b> 연결되지 않은 채널은 수집 시각 자리에 아무것도 적지 않고,
   * 연결됐지만 한 번도 성공하지 못한 채널은 「수집 이력 없음」이다. 둘을 한 단어로 적으면 어느 쪽도
   * 참이 아니다 — 연결한 적 없는 채널을 「수집 이력 없음」이라고 부르면 고칠 것이 있다는 뜻으로 읽힌다.
   */
  it("keeps 연결되지 않음 and 읽은 적 없음 apart in the collection column", () => {
    // 계정이 없는 채널: 이 열에는 할 말이 없다.
    renderList({ channels: [NAVER], accounts: [] });
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByText("수집 이력 없음")).toBeNull();

    // 연결은 됐고 한 번도 성공하지 못한 채널: 그 사실을 제 말로 적는다.
    renderList({ health: health({ lastSyncedAt: null, lastSuccessAt: null }) });
    expect(screen.getAllByText("수집 이력 없음").length).toBeGreaterThan(0);
  });

  it("shows 오류 with 확인하기 when collection is failing", () => {
    renderList({ health: health({ consecutiveFailures: 1 }) });
    expect(screen.getByTestId("connection-state")).toHaveTextContent("오류");
    expect(screen.getByRole("button", { name: "확인하기" })).toBeInTheDocument();
    expect(screen.getByText(/최근 수집에서 오류가 있었습니다/)).toBeInTheDocument();
  });
});

describe("ChannelList — the 상품평 entry", () => {
  it("appears on a review-record channel with an account, carrying the count", () => {
    renderList({ reviewCounts: new Map([["acc-cp", 22]]) });
    expect(screen.getByRole("link", { name: "쿠팡 상품평 22개 보기" })).toHaveAttribute(
      "href",
      "/reviews/acc-cp",
    );
  });

  it("names the channel to a screen reader, and still reads the count on screen", () => {
    renderList({ reviewCounts: new Map([["acc-cp", 22]]) });
    const link = screen.getByRole("link", { name: "쿠팡 상품평 22개 보기" });
    // WCAG 2.5.3: the accessible name must contain the visible label, so this prefixes, never replaces.
    expect(link).toHaveTextContent("상품평 22개 보기");
  });

  it("appears without a count when none was supplied", () => {
    renderList();
    expect(screen.getByRole("link", { name: "쿠팡 상품평 보기" })).toBeInTheDocument();
  });

  it("is absent on a channel that keeps no record, even with a connected account", () => {
    // The account is present ON PURPOSE. With `accounts: []` this assertion would hold whatever the
    // channel predicate said, and deleting `hasReviewRecord(...)` from the row would break no test.
    renderList({
      channels: [{ ...NAVER, code: "GMARKET", nameKo: "G마켓" }],
      accounts: [{ ...ACCOUNT, id: "acc-gm", channelId: "naver-ch", channelNameKo: "G마켓" }],
    });
    expect(screen.getByRole("button", { name: "연결 관리" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /상품평|리뷰/ })).toBeNull();
  });

  it("speaks the product's word on NAVER — 리뷰, not Coupang's 상품평", () => {
    renderList({
      channels: [NAVER],
      accounts: [{ ...ACCOUNT, id: "acc-nv", channelId: "naver-ch", channelNameKo: "네이버" }],
      reviewCounts: new Map([["acc-nv", 3]]),
    });
    expect(screen.getByRole("link", { name: "네이버 리뷰 3개 보기" })).toHaveAttribute(
      "href",
      "/reviews/acc-nv",
    );
  });

  it("is a text link beside the row's facts — one primary control per row, and it is the state's", () => {
    renderList({ reviewCounts: new Map([["acc-cp", 22]]) });
    // Reviewnary Product UI Redesign v1 (docs/reviewnary_design.md §7 채널 연결): a row carries ONE
    // primary action, decided by the connection state. The record's way in stays, with its count,
    // as a link — never solid, never hidden.
    const link = screen.getByRole("link", { name: "쿠팡 상품평 22개 보기" });
    expect(link.className).not.toContain("bg-brand-700");
    expect(screen.getByRole("button", { name: "연결 관리" })).toBeInTheDocument();
  });

  it("steps back when collection is failing, without going away", () => {
    renderList({
      reviewCounts: new Map([["acc-cp", 22]]),
      health: health({ consecutiveFailures: 2, lastError: "AUTH" }),
    });
    const link = screen.getByRole("link", { name: "쿠팡 상품평 22개 보기" });
    // Still there — the 상품평 collected before the break are still the seller's. Just not the
    // brightest thing on a row that is asking to be repaired.
    expect(link).toBeInTheDocument();
    expect(link.className).not.toContain("bg-brand-700");
    expect(screen.getByRole("button", { name: "확인하기" })).toBeInTheDocument();
  });

  it("wraps on a narrow row instead of hiding at a breakpoint", () => {
    renderList({ reviewCounts: new Map([["acc-cp", 22]]) });
    const link = screen.getByRole("link", { name: "쿠팡 상품평 22개 보기" });
    // The entry sits in the row's wrapping facet line: at a narrow width it falls under the name
    // rather than being clipped or pushed off the edge.
    expect(link.parentElement!.className).toContain("flex-wrap");
    // Nothing in the chain from the entry up to the row is display-toggled by viewport width —
    // the one failure this unit exists to prevent is a way in that is present but unseen.
    for (let node: HTMLElement | null = link; node; node = node.parentElement) {
      for (const cls of node.className.split(/\s+/)) {
        expect(cls).not.toMatch(/^(?:\w+:)?(?:hidden|invisible|sr-only)$/);
      }
      if (node.tagName === "LI") break;
    }
  });
});
