// @vitest-environment jsdom
//
// 「담당자가 올리는 네이버 리뷰」 on the seller PC: the route is named before anything starts, only the stores shown are
// started, a store added later waits to be started, the last check and the last upload are two times, and each store
// says its own failure and can be stopped.
import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type {
  VendorLinkStatus,
  VendorReviewPullResult,
  VendorReviewStoreView,
  VendorReviewsOverview,
} from "../../lib/types";

const getVendorLink = vi.fn<() => Promise<VendorLinkStatus>>();
const startVendorLink = vi.fn<() => Promise<VendorLinkStatus>>();
const getVendorReviewsOverview = vi.fn<() => Promise<VendorReviewsOverview>>();
const startVendorReviews = vi.fn<(ids: string[]) => Promise<VendorReviewPullResult>>();
const pullVendorReviews = vi.fn<() => Promise<VendorReviewPullResult>>();
const setVendorStorePaused = vi.fn<(id: string, paused: boolean) => Promise<void>>();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getVendorLink: () => getVendorLink(),
    startVendorLink: () => startVendorLink(),
    pollVendorLink: vi.fn(),
    unlinkVendor: vi.fn(),
    getVendorReviewsOverview: () => getVendorReviewsOverview(),
    startVendorReviews: (ids: string[]) => startVendorReviews(ids),
    pullVendorReviews: () => pullVendorReviews(),
    setVendorStorePaused: (id: string, paused: boolean) => setVendorStorePaused(id, paused),
  },
}));

import { VendorReviewsSection } from "./VendorReviewsSection";

const HOUR = 3600 * 1000;
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

function linkStatus(state: VendorLinkStatus["state"]): VendorLinkStatus {
  return {
    state, offReason: null, userCode: null, codeExpiresAt: null, pollIntervalSeconds: null,
    approvalUrl: "http://127.0.0.1:8090/link?code=BCDFGHJK", deviceName: null, linkedAt: null,
  };
}

function store(over: Partial<VendorReviewStoreView>): VendorReviewStoreView {
  return {
    storeId: "s-1", label: "데모 스토어", standing: "NOT_RECEIVING", lastCheckedAt: null, lastUploadAt: null,
    received: 0, failure: null, failedAt: null, ...over,
  };
}

function overview(stores: VendorReviewStoreView[], state: VendorReviewsOverview["state"] = "PULLED"): VendorReviewsOverview {
  return { state, accountEmail: "seller@example.com", organisation: "데모 제조사", stores };
}

const pulled: VendorReviewPullResult = {
  state: "PULLED",
  stores: [{ storeId: "s-1", label: "데모 스토어", bound: true, otherOrganisation: false, paused: false,
    received: 6, inserted: 6, updated: 0, failed: 0 }],
};

function renderSection() {
  return render(<MemoryRouter><VendorReviewsSection /></MemoryRouter>);
}

describe("VendorReviewsSection", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("is not there on an install that cannot link", async () => {
    getVendorLink.mockResolvedValue(linkStatus("OFF"));
    const { container } = renderSection();
    await waitFor(() => expect(getVendorLink).toHaveBeenCalled());
    expect(container.querySelector("[data-testid='vendor-reviews']")).toBeNull();
  });

  it("an unlinked PC offers the link and nothing else", async () => {
    getVendorLink.mockResolvedValue(linkStatus("UNLINKED"));
    renderSection();
    expect(await screen.findByTestId("vendor-reviews-link")).toBeInTheDocument();
    expect(screen.getByText(/아직 reviewnary 서버에 연결되지 않았습니다/)).toBeInTheDocument();
    expect(getVendorReviewsOverview).not.toHaveBeenCalled();
  });

  it("names the route and starts exactly the stores it shows", async () => {
    getVendorLink.mockResolvedValue(linkStatus("LINKED"));
    getVendorReviewsOverview.mockResolvedValueOnce(overview([
      store({ storeId: "s-1", label: "데모 스토어", lastUploadAt: ago(2 * 24 * HOUR) }),
      store({ storeId: "s-2", label: "2호점" }),
      store({ storeId: "s-3", label: "본점", standing: "OTHER_ORGANISATION" }),
    ]));
    getVendorReviewsOverview.mockResolvedValue(overview([store({ standing: "RECEIVING", lastCheckedAt: ago(0) })]));
    startVendorReviews.mockResolvedValue(pulled);
    renderSection();

    const route = await screen.findByTestId("vendor-reviews-route");
    expect(route).toHaveTextContent("서버의 판매자 계정 seller@example.com → 이 PC의 작업 계정 데모 제조사");
    expect(screen.getByTestId("vendor-reviews-start-note")).toHaveTextContent("2곳(데모 스토어, 2호점)");
    expect(screen.getByText("이 PC의 다른 계정이 받는 중")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("vendor-reviews-start"));
    await waitFor(() => expect(startVendorReviews).toHaveBeenCalledWith(["s-1", "s-2"]));
    expect(await screen.findByText("새 리뷰 6건 · 바뀐 리뷰 0건을 받았습니다.")).toBeInTheDocument();
    expect(screen.getByText("받는 중")).toBeInTheDocument();
    expect(screen.queryByText("아직 받지 않음")).toBeNull();
  });

  it("says when it last asked apart from when the delegate last uploaded, and each store's own failure", async () => {
    getVendorLink.mockResolvedValue(linkStatus("LINKED"));
    getVendorReviewsOverview.mockResolvedValue(overview([
      store({ storeId: "s-1", label: "데모 스토어", standing: "RECEIVING", lastCheckedAt: ago(0),
        lastUploadAt: ago(2 * 24 * HOUR), received: 128 }),
      store({ storeId: "s-2", label: "2호점", standing: "RECEIVING", lastCheckedAt: ago(HOUR), received: 3,
        failure: "NOT_STORED", failedAt: ago(HOUR) }),
      store({ storeId: "s-4", label: "새 지점" }),
    ]));
    renderSection();

    const rows = await screen.findAllByTestId("vendor-store");
    expect(within(rows[0]).getByTestId("vendor-store-facts"))
      .toHaveTextContent("서버 확인 방금 전 · 담당자 마지막 업로드 2일 전 · 받은 리뷰 128건");
    expect(within(rows[0]).queryByTestId("vendor-store-failure")).toBeNull();
    expect(within(rows[1]).getByText("받지 못함")).toBeInTheDocument();
    expect(within(rows[1]).getByTestId("vendor-store-failure")).toHaveTextContent("리뷰 일부를 저장하지 못했습니다");
    expect(within(rows[2]).getByText("아직 받지 않음")).toBeInTheDocument();
    expect(within(rows[2]).getByTestId("vendor-store-facts")).toHaveTextContent("담당자 마지막 업로드 아직 없음");
    expect(screen.getByTestId("vendor-reviews-start")).toBeInTheDocument();
  });

  it("stops and starts one store again", async () => {
    getVendorLink.mockResolvedValue(linkStatus("LINKED"));
    getVendorReviewsOverview.mockResolvedValueOnce(overview([store({ standing: "RECEIVING", lastCheckedAt: ago(0) })]));
    getVendorReviewsOverview.mockResolvedValue(overview([store({ standing: "PAUSED", lastCheckedAt: ago(0) })]));
    setVendorStorePaused.mockResolvedValue(undefined);
    renderSection();

    fireEvent.click(await screen.findByRole("button", { name: "받기 멈추기" }));
    await waitFor(() => expect(setVendorStorePaused).toHaveBeenCalledWith("s-1", true));
    expect(await screen.findByText("받기 멈춤")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 받기" }));
    await waitFor(() => expect(setVendorStorePaused).toHaveBeenCalledWith("s-1", false));
  });

  it("an unreachable server keeps what this PC had on screen", async () => {
    getVendorLink.mockResolvedValue(linkStatus("LINKED"));
    getVendorReviewsOverview.mockResolvedValue(overview([
      store({ standing: "RECEIVING", lastCheckedAt: ago(2 * HOUR), received: 128 }),
    ], "UNREACHABLE"));
    renderSection();
    expect(await screen.findByRole("alert")).toHaveTextContent("reviewnary 서버에 닿지 않습니다");
    expect(screen.getByTestId("vendor-store-facts")).toHaveTextContent("서버 확인 2시간 전");
    expect(screen.queryByTestId("vendor-reviews-start")).toBeNull();
    expect(screen.queryByRole("button", { name: "지금 받기" })).toBeNull();
  });
});
