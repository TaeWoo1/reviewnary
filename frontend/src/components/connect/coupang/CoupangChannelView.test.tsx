// @vitest-environment jsdom
/**
 * 쿠팡 채널 화면의 계약: <b>두 장의 카드, 각 카드에 다음 걸음 하나.</b>
 *
 * 여기서 단언하는 대부분은 「무엇이 없는가」다 — 이 패키지가 고친 것이 대체로 그것이기 때문이다:
 * 첫 화면의 경쟁 CTA, 정상 상태의 진단 줄, 같은 채널을 반대로 설명하던 배지, 될 수 없는 수집을
 * 제안하던 컨트롤, 그리고 개발자가 쓴 영문 주석.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { CoupangChannelView } from "./CoupangChannelView";
import { expectNoAxeViolations } from "../../../test/axe";
import type {
  ChannelCapabilityOverview,
  ConnectionInfoView,
  ConnectionStatusView,
  SyncRunView,
} from "../../../lib/types";

const getReviewAcquisitionReadiness = vi.fn();
const getChannelCapabilityOverview = vi.fn();
const getChannelReviewsStrict = vi.fn();
const collectNowReadiness = vi.fn();
const onReport = vi.fn();
const collectNow = vi.fn();
const screenReadStatus = vi.fn();

/**
 * 서버가 이 채널에 실제로 답하는 모양: 리뷰는 판매자 센터 화면 읽기, 문의·주문은 공식 API.
 * `desk`는 그 화면 읽기를 지금 할 수 있는지다.
 */
function readiness(desk: string | null = "READY") {
  collectNowReadiness.mockImplementation(async (_acct: string, dataType: string) =>
    dataType === "REVIEW" ? { path: "SCREEN_READ", localAgent: desk } : { path: "API", localAgent: null },
  );
}

vi.mock("../../../lib/apiClient", () => ({
  api: {
    getReviewAcquisitionReadiness: (...a: unknown[]) => getReviewAcquisitionReadiness(...a),
    getChannelCapabilityOverview: (...a: unknown[]) => getChannelCapabilityOverview(...a),
    getChannelReviewsStrict: (...a: unknown[]) => getChannelReviewsStrict(...a),
    manualSync: vi.fn(),
    // 모든 수집 줄과 리뷰 카드가 그려지기 전에 「어느 경로냐」를 서버에 묻는다. 답하지 않으면 줄은
    // 「확인 중…」에 머무르고, 이 파일의 단정들은 아무 컨트롤도 보지 못한다.
    collectNowReadiness: (...a: unknown[]) => collectNowReadiness(...a),
    collectNow: (...a: unknown[]) => collectNow(...a),
    screenReadStatus: (...a: unknown[]) => screenReadStatus(...a),
  },
}));

const overview = (autoCollectSupported: boolean): ChannelCapabilityOverview => ({
  channelCode: "COUPANG",
  channelNameKo: "쿠팡",
  connectorClass: "API",
  autoCollectSupported,
  dataTypes: [],
  unsupportedScopes: [],
});

/** 서버가 실제로 돌려주는 쿠팡의 모양: 문의·주문은 API로 되고, 리뷰는 안 된다. */
const CAPABILITIES = [
  { channelCode: "COUPANG", connectorClass: "API", dataType: "INQUIRY", supported: true, verificationStatus: "NEEDS_VERIFICATION", notes: null },
  { channelCode: "COUPANG", connectorClass: "API", dataType: "ORDER_SUMMARY", supported: true, verificationStatus: "CONFIRMED", notes: null },
  { channelCode: "COUPANG", connectorClass: "API", dataType: "REVIEW", supported: false, verificationStatus: "UNSUPPORTED", notes: "No review-retrieval endpoint in the official seller API." },
];

const screenRead = (over: Partial<SyncRunView> = {}): SyncRunView =>
  ({
    id: "r1",
    sellerAccountId: "acc-1",
    channelId: "ch",
    dataType: "REVIEW",
    trigger: "ACTION_WINDOW",
    attempt: 1,
    rateLimited: false,
    nextRetryAt: null,
    jobType: "AGENT_HANDOFF",
    uploadType: "REVIEW",
    status: "SUCCESS",
    totalRows: 9,
    successRows: 9,
    skippedRows: 0,
    failedRows: 0,
    errorMessage: null,
    startedAt: "2026-09-14T00:00:00Z",
    finishedAt: "2026-09-14T00:00:00Z",
    method: "SELLER_CENTER_READ",
    coverage: null,
    ...over,
  }) as SyncRunView;

function view(over: Partial<Parameters<typeof CoupangChannelView>[0]> = {}) {
  return render(
    <MemoryRouter>
      <CoupangChannelView
        accountId="acc-1"
        channelCode="COUPANG"
        title="쿠팡"
        status={{ state: "CONNECTED", consecutiveFailures: 0 } as unknown as ConnectionStatusView}
        connectionInfo={{ authType: "API_KEY" } as unknown as ConnectionInfoView}
        infoLoading={false}
        infoError={false}
        credentialTemplate={null}
        templateError={false}
        schedules={[]}
        capabilities={CAPABILITIES}
        runs={[screenRead()]}
        onReport={onReport}
        onChanged={() => undefined}
        {...over}
      />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  getReviewAcquisitionReadiness.mockResolvedValue({ state: "READY", channelCode: "COUPANG" });
  getChannelCapabilityOverview.mockResolvedValue(overview(true));
  getChannelReviewsStrict.mockResolvedValue({ total: 1204 });
  collectNow.mockReset();
  screenReadStatus.mockReset();
  onReport.mockReset();
  readiness("READY");
});
afterEach(() => vi.clearAllMocks());

describe("쿠팡 채널 화면 — 두 가지 방법, 그뿐", () => {
  it("판매자가 이해해야 하는 두 가지가 카드 둘로 선다", async () => {
    view();
    expect(await screen.findByTestId("coupang-review-card")).toBeInTheDocument();
    expect(await screen.findByTestId("coupang-api-card")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "리뷰 수집" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "문의·주문 자동 수집" })).toBeInTheDocument();
  });

  it("리뷰 카드는 판매자가 하는 일로 자기를 설명하고, API 키를 요구하지 않는다고 말한다", async () => {
    view();
    const card = await screen.findByTestId("coupang-review-card");
    expect(within(card).getByText(/로그인된 쿠팡 판매자 화면에서 상품평을 가져옵니다/)).toBeInTheDocument();
    expect(within(card).getByText(/API 키는 필요하지 않습니다/)).toBeInTheDocument();
  });

  it("리뷰 카드의 수집 컨트롤은 「지금 수집하기」 하나이고, 그것이 /collect-now로 간다", async () => {
    // 2026-10-07 라이브에서 고친 것. 이 카드의 가장 강한 컨트롤이 안내 carrier로 가는 링크였고, 리뷰를
    // 가져오려던 판매자가 그것을 눌렀다. canonical 경로는 하나뿐이어야 한다.
    collectNow.mockResolvedValue({
      path: "SCREEN_READ",
      dataType: "REVIEW",
      run: null,
      screenRead: { jobId: "j1", state: "RUNNING", observed: null, inserted: null, changed: null, complete: false, startedAt: null, finishedAt: null },
    });
    // 화면 읽기는 도우미가 받아 가는 일이라, 끝났는지는 되물어야 안다.
    screenReadStatus.mockResolvedValue({
      jobId: "j1", state: "SUCCESS", observed: 12, inserted: 3, changed: 1, complete: true, startedAt: null, finishedAt: null,
    });
    view();
    const card = await screen.findByTestId("coupang-review-card");
    await waitFor(() => expect(within(card).getByText(/가져온 상품평 1,204개/)).toBeInTheDocument());

    const button = await waitFor(() => within(card).getByTestId("collect-now-REVIEW"));
    expect(button).toHaveTextContent("지금 수집하기");
    expect(button).not.toBeDisabled();
    // 옛 입구는 이 상태에서 화면에 없다 — 고를 것이 없어야 고르지 않는다.
    expect(within(card).queryByRole("link", { name: "리뷰 수집 연결하기" })).toBeNull();
    expect(within(card).queryByRole("link", { name: "지금 가져오기" })).toBeNull();

    fireEvent.click(button);
    await waitFor(() => expect(collectNow).toHaveBeenCalled());
    const [accountId, dataType] = collectNow.mock.calls[0] as [string, string];
    expect(accountId).toBe("acc-1");
    expect(dataType).toBe("REVIEW");
    await waitFor(() => expect(onReport).toHaveBeenCalledWith("리뷰 수집 완료: 새로 저장 3 · 갱신 1", false));
  });

  it("도우미가 없으면 누를 수 없고, 안내 흐름은 복구 동작으로만 남는다", async () => {
    readiness("UNPAIRED");
    getReviewAcquisitionReadiness.mockResolvedValue({ state: "HELPER_NOT_LINKED", channelCode: "COUPANG" });
    view();
    const card = await screen.findByTestId("coupang-review-card");

    await waitFor(() => expect(within(card).getByTestId("collect-now-REVIEW")).toBeDisabled());
    expect(within(card).getByText(/도우미를 한 번 연결하면/)).toBeInTheDocument();
    // 복구 동작은 같은 주소로 가지만, 이름에 「수집」이 없고 가장 강한 컨트롤도 아니다.
    const recovery = within(card).getByRole("link", { name: "이 Mac 연결하기" });
    expect(recovery).toHaveAttribute("href", "/connect/channels/acc-1/review-collection");
    expect(recovery.className).not.toContain("bg-brand-700");
  });

  it("판매자 센터 로그인이 풀리면 로그인 복구를 내밀고, 다시 누를 수는 있게 둔다", async () => {
    readiness("AUTH_REQUIRED");
    view();
    const card = await screen.findByTestId("coupang-review-card");

    await waitFor(() =>
      expect(within(card).getByText("판매자 센터 로그인이 필요합니다. 로그인한 뒤 다시 수집해 주세요.")).toBeInTheDocument(),
    );
    // 로그인은 판매자가 자기 브라우저에서 방금 했을 수도 있다. 막아 두면 고친 뒤에도 누를 길이 없다.
    expect(within(card).getByTestId("collect-now-REVIEW")).not.toBeDisabled();
    expect(within(card).getByRole("link", { name: "판매자 센터 로그인" })).toBeInTheDocument();
  });

  it("이미 수집 중이면 기다리라고 하고, 연결하라고 하지 않는다", async () => {
    readiness("BUSY");
    view();
    const card = await screen.findByTestId("coupang-review-card");

    await waitFor(() => expect(within(card).getByTestId("collect-now-REVIEW")).toBeDisabled());
    expect(within(card).getByText(/이미 수집이 진행 중입니다/)).toBeInTheDocument();
    expect(within(card).queryByRole("link", { name: "이 Mac 연결하기" })).toBeNull();
  });

  it("같은 자료에 수집 버튼이 두 번 보이지 않는다", async () => {
    // 카드가 리뷰의 primary를 들고 있으므로, 「수집 주기」 안의 리뷰 줄은 주기가 없는 이유만 말한다.
    view();
    await screen.findByTestId("coupang-review-card");
    await waitFor(() => expect(screen.getAllByTestId("collect-now-REVIEW")).toHaveLength(1));
  });

  it("정상 상태에 도우미·실행 프로그램 같은 진단은 없다", async () => {
    view();
    await screen.findByTestId("coupang-review-card");
    expect(screen.queryByText(/도우미/)).toBeNull();
    expect(screen.queryByTestId("helper-status")).toBeNull();
  });

  const solidIn = (el: HTMLElement) =>
    [...el.querySelectorAll("a,button")].filter(
      (c) => c.className.includes("bg-brand-700") && !c.closest("details"),
    );

  it("한 시점에 가장 강한 컨트롤은 하나다", async () => {
    view();
    const review = await screen.findByTestId("coupang-review-card");
    const api = await screen.findByTestId("coupang-api-card");
    // 둘 다 연결된 화면에서 지금 할 일은 상품평을 한 번 더 가져오는 것뿐이고, 나머지는 보조다.
    await waitFor(() => expect(solidIn(review).map((c) => c.textContent)).toEqual(["지금 수집하기"]));
    expect(solidIn(api)).toHaveLength(0);
  });

  it("끝나지 않은 연결이 있으면 그것이 이 화면의 다음 걸음이다", async () => {
    view({ connectionInfo: null });
    const api = await screen.findByTestId("coupang-api-card");
    const review = await screen.findByTestId("coupang-review-card");
    expect(solidIn(api).map((c) => c.textContent)).toEqual(["API 연결하기"]);
    expect(solidIn(review)).toHaveLength(0);
  });

  it("커넥터가 꺼진 배포에서는 문의·주문 카드가 존재하지 않는다", async () => {
    getChannelCapabilityOverview.mockResolvedValue(overview(false));
    view();
    await screen.findByTestId("coupang-review-card");
    await waitFor(() => expect(screen.queryByTestId("coupang-api-card")).toBeNull());
    // 그리고 자격을 받을 자리도 없다.
    expect(screen.queryByText("연결에 필요한 정보")).toBeNull();
  });

  it("지운 화면들은 돌아오지 않는다", async () => {
    view();
    await screen.findByTestId("coupang-review-card");
    for (const gone of ["요약", "수집 가능 데이터", "수집된 리뷰·문의", "다음 조치"]) {
      expect(screen.queryByText(gone, { exact: true })).toBeNull();
    }
    // capability registry의 영문 note가 판매자 문장으로 나가던 자리.
    expect(screen.queryByText(/official seller API/)).toBeNull();
  });

  it("될 수 없는 수집을 제안하지 않는다 — 기간 수집에 리뷰가 없다", async () => {
    view();
    await screen.findByTestId("coupang-api-card");
    const fold = screen.getByText("지난 기간 가져오기").closest("details");
    expect(fold).not.toBeNull();
    // 쿠팡 리뷰에는 API 기간 수집 경로가 없다(`supported:false`). 예전 화면은 ✓리뷰를 켠 채로 제공했다.
    expect(within(fold as HTMLElement).queryByRole("button", { name: /리뷰/ })).toBeNull();
    expect(within(fold as HTMLElement).getByRole("button", { name: /문의/ })).toBeInTheDocument();
  });

  it("「API 연결하기」는 실제로 연결 정보를 연다 — 앵커가 아니라", async () => {
    view({ connectionInfo: null });
    const cta = await screen.findByTestId("coupang-api-cta");
    const fold = screen.getByText("연결 정보").closest("details") as HTMLDetailsElement;
    expect(fold.open).toBe(false);
    fireEvent.click(cta);
    await waitFor(() => expect(fold.open).toBe(true));
  });

  it("실행 기록은 접혀 있다", async () => {
    view();
    await screen.findByTestId("coupang-review-card");
    const fold = screen.getByText("지난 실행 기록").closest("details");
    expect(fold).not.toBeNull();
    expect(fold).not.toHaveAttribute("open");
  });

  it("접근성 위반 0", async () => {
    const { container } = view();
    await screen.findByTestId("coupang-api-card");
    await expectNoAxeViolations(container);
  });
});
