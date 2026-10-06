// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { Orders } from "./Orders";
import { api } from "../lib/apiClient";
import type { OrderRecordListResponse } from "../lib/types";

vi.mock("../lib/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lib/apiClient")>()),
  api: {
    getChannelsStrict: vi.fn(),
    getOrderRecordsStrict: vi.fn(),
    getOrdersSummaryStrict: vi.fn(),
  },
}));

function Probe() {
  const l = useLocation();
  return <p data-testid="url">{l.pathname + l.search}</p>;
}

function mount(path = "/orders") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/orders" element={<><Orders /><Probe /></>} />
        <Route path="/orders/:channelCode/:accountId/:parentOrderId" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  );
}

const ACCOUNT = "11111111-1111-1111-1111-111111111111";
const COUPANG_ACCOUNT = "22222222-2222-2222-2222-222222222222";

const records = (over: Partial<OrderRecordListResponse> = {}): OrderRecordListResponse => ({
  extent: {
    paymentUnitCount: 343,
    orderLineCount: 573,
    totalAmount: 9_130_010,
    periodFrom: "2026-08-16",
    periodTo: "2026-09-05",
    linkedInquiryCount: 2,
  },
  reads: [
    { channelCode: "NAVER", accountId: ACCOUNT, state: "OBSERVED_FRESHNESS_UNPROVEN", orderLineCount: 393, lastSeenAt: "2026-09-05T11:30:00Z" },
    { channelCode: "COUPANG", accountId: COUPANG_ACCOUNT, state: "NOT_CONNECTED", orderLineCount: 0, lastSeenAt: null },
  ],
  rows: [
    {
      channelCode: "NAVER", accountId: ACCOUNT, parentOrderId: "2026090531476931",
      lineCount: 5, totalAmount: 14_100, rawStatusCode: "PAYED", statusVaries: false,
      confirmedStatusLabelKo: "결제 완료", paidAt: "2026-09-05T10:57:00Z", lastSeenAt: "2026-09-05T11:30:00Z",
    },
    {
      channelCode: "COUPANG", accountId: COUPANG_ACCOUNT, parentOrderId: "17102755913898",
      lineCount: 1, totalAmount: 11_900, rawStatusCode: "ACCEPT", statusVaries: false,
      confirmedStatusLabelKo: null, paidAt: "2026-09-05T05:12:00Z", lastSeenAt: "2026-09-05T11:30:00Z",
    },
  ],
  hasMore: false,
  ...over,
});

const summary = (orders: number) => ({
  totalOrders7d: orders,
  totalSales7d: orders * 1000,
  trend: [],
  channelShare: orders === 0 ? [] : [{ channelNameKo: "네이버 스마트스토어", salesAmount: orders * 1000, percent: 100 }],
});

beforeEach(() => {
  vi.setSystemTime(new Date("2026-10-06T03:00:00Z"));
  vi.mocked(api.getChannelsStrict).mockResolvedValue([
    { id: "ch-1", code: "NAVER", nameKo: "네이버 스마트스토어" },
    { id: "ch-2", code: "COUPANG", nameKo: "쿠팡" },
  ] as never);
  vi.mocked(api.getOrderRecordsStrict).mockReset().mockResolvedValue(records());
  vi.mocked(api.getOrdersSummaryStrict).mockReset().mockResolvedValue(summary(0) as never);
});

describe("주문 — 결제 단위 기록 목록", () => {
  it("한 줄이 결제 단위 하나이고, 그 줄은 네 조각짜리 주소로 간다", async () => {
    mount();
    const rows = within(await screen.findByTestId("orders-records")).getAllByRole("listitem");
    expect(rows).toHaveLength(2);

    await userEvent.click(screen.getByRole("link", { name: /2026090531476931/ }));
    await waitFor(() =>
      expect(screen.getByTestId("url")).toHaveTextContent(`/orders/NAVER/${ACCOUNT}/2026090531476931`),
    );
  });

  it("뜻을 확인한 코드만 우리 말이고, 쿠팡의 raw는 그대로 선다", async () => {
    mount();
    const list = await screen.findByTestId("orders-records");
    expect(within(list).getByText("결제 완료")).toBeInTheDocument();
    expect(within(list).getByText("ACCEPT")).toBeInTheDocument();
    expect(within(list).queryByText("주문 접수")).not.toBeInTheDocument();
  });

  it("읽은 범위가 숫자 위에 서고, 읽지 못한 연결의 0은 숫자가 아니다", async () => {
    mount();
    const reads = within(await screen.findByTestId("orders-reads")).getAllByRole("listitem");
    expect(reads[0]).toHaveTextContent("네이버 스마트스토어");
    expect(reads[0]).toHaveTextContent("최신 여부 미확인");
    expect(reads[0]).toHaveTextContent("393줄");
    // 연결되지 않은 채널의 0줄은 「없었다」가 아니다 — 숫자 자리에 숫자가 서지 않는다.
    expect(reads[1]).toHaveTextContent("연결 안 됨");
    expect(reads[1]).not.toHaveTextContent("0줄");
    expect(reads[1]).toHaveTextContent("—");
    // 마지막으로 읽은 시각이 오래됐으면 숫자 위에서 그렇게 말한다.
    expect(screen.getByText(/이후로 새로 읽은 주문이 없습니다/)).toBeInTheDocument();
  });


  it("읽은 0과 한 건씩 들고 있지 않은 0은 다른 말이다", async () => {
    vi.mocked(api.getOrderRecordsStrict).mockResolvedValue(
      records({
        reads: [
          // 읽었고 없었다 — 측정된 0.
          { channelCode: "NAVER", accountId: ACCOUNT, state: "ZERO", orderLineCount: 0, lastSeenAt: "2026-09-05T11:30:00Z" },
          // 읽기는 했지만 한 건씩 들고 있는 것이 없다(카페24: 일자 집계만). 「0줄」이라고 적으면 그 채널의
          // 주문이 0건이라는 말이 되는데, 이 읽기는 그것을 증명한 적이 없다.
          { channelCode: "CAFE24", accountId: "33333333-3333-3333-3333-333333333333", state: "OBSERVED_FRESHNESS_UNPROVEN", orderLineCount: 0, lastSeenAt: "2026-09-05T11:35:00Z" },
        ],
      }),
    );
    mount();
    const reads = within(await screen.findByTestId("orders-reads")).getAllByRole("listitem");
    expect(reads[0]).toHaveTextContent("0줄");
    expect(reads[1]).toHaveTextContent("없음");
    expect(reads[1]).not.toHaveTextContent("0줄");
  });

  it("상품주문 한 줄짜리 주문도 「1줄」이라고 적는다 — 빈 칸은 읽지 못한 값의 자리다", async () => {
    mount();
    const rows = within(await screen.findByTestId("orders-records")).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("5줄");
    expect(rows[1]).toHaveTextContent("1줄");
  });

  it("부분 응답은 서버가 말한 대로만 말한다", async () => {
    vi.mocked(api.getOrderRecordsStrict).mockResolvedValue(records({ hasMore: false }));
    const view = mount();
    await screen.findByTestId("orders-records");
    expect(screen.queryByTestId("orders-has-more")).not.toBeInTheDocument();

    view.unmount();
    vi.mocked(api.getOrderRecordsStrict).mockResolvedValue(records({ hasMore: true }));
    mount();
    expect(await screen.findByTestId("orders-has-more")).toHaveTextContent("343");
  });

  it("고른 기간이 비어 있으면 0을 그리지 않고, 읽어 둔 기간을 말한다", async () => {
    vi.mocked(api.getOrdersSummaryStrict).mockImplementation(async (p) =>
      (p?.from === "2026-08-16" ? summary(343) : summary(0)) as never,
    );
    mount();
    const line = await screen.findByTestId("orders-sales-line");
    expect(line).toHaveTextContent("집계된 주문이 없습니다");
    expect(line).toHaveTextContent("마지막으로 집계된 날은 9월 5일입니다");
    // 비어 있을 때 보여 주는 채널별 매출은 읽어 둔 기간의 것이고, 그렇게 적힌다.
    expect(await screen.findByTestId("orders-channel-share")).toHaveTextContent("네이버 스마트스토어");
    expect(screen.getByText(/채널별 매출 · 8월 16일 – 9월 5일/)).toBeInTheDocument();
  });

  it("기간 버튼은 URL에 적히고, 매출만 다시 읽는다", async () => {
    mount();
    await screen.findByTestId("orders-records");
    await userEvent.click(screen.getByRole("button", { name: "최근 7일" }));
    await waitFor(() => expect(screen.getByTestId("url")).toHaveTextContent("days=7"));
    expect(api.getOrderRecordsStrict).toHaveBeenCalledTimes(1);
  });

  it("읽지 못하면 숫자를 그리지 않는다", async () => {
    vi.mocked(api.getOrderRecordsStrict).mockRejectedValue(new Error("down"));
    mount();
    expect(await screen.findByText(/주문 기록을 불러오지 못했습니다/)).toBeInTheDocument();
    expect(screen.queryByTestId("orders-extent")).not.toBeInTheDocument();
  });
});
