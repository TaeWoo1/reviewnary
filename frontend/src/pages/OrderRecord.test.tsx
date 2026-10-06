// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { OrderRecord } from "./OrderRecord";
import { api } from "../lib/apiClient";
import type { OrderRecordDetail } from "../lib/types";

vi.mock("../lib/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lib/apiClient")>()),
  api: { getChannelsStrict: vi.fn(), getOrderRecordStrict: vi.fn() },
}));

const ACCOUNT = "11111111-1111-1111-1111-111111111111";

function mount(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/orders/:channelCode/:accountId/:parentOrderId" element={<OrderRecord />} />
      </Routes>
    </MemoryRouter>,
  );
}

const naver: OrderRecordDetail = {
  channelCode: "NAVER",
  accountId: ACCOUNT,
  parentOrderId: "2026381795878643",
  lineCount: 2,
  totalAmount: 47_800,
  rawStatusCode: "PAYED",
  statusVaries: false,
  paymentLabelKo: "결제 완료",
  cancellationLabelKo: null,
  fulfillmentLabelKo: null,
  paidAt: "2026-09-03T07:24:00Z",
  lastSeenAt: "2026-09-04T12:24:09Z",
  readState: "OBSERVED_FRESHNESS_UNPROVEN",
  lines: [
    { externalOrderId: "2026863083932901", paymentAmount: 11_400, rawStatusCode: "PAYED", confirmedStatusLabelKo: "결제 완료", paidAt: "2026-09-03T07:24:00Z" },
    { externalOrderId: "2026863083932911", paymentAmount: 36_400, rawStatusCode: "PAYED", confirmedStatusLabelKo: "결제 완료", paidAt: "2026-09-03T07:24:00Z" },
  ],
  statusHistory: [
    { fromStatusCode: null, fromLabelKo: null, toStatusCode: "PAYED", toLabelKo: "결제 완료", observedAt: "2026-09-03T07:24:41Z", recordedAt: "2026-09-04T12:24:09Z", lineCount: 2 },
  ],
  inquiries: [
    {
      inquiryId: "inq-1", channelCode: "NAVER", title: "언제 발송하나요?",
      body: "아직 배송이 안되네요 언제 발송 되나요?", status: "UNANSWERED",
      receivedAt: "2026-09-05T01:00:00Z", sourceOrderRef: "2026863083932911",
      productId: "prod-1", productName: "선바로 일체형 전선몰딩",
    },
  ],
};

const coupang: OrderRecordDetail = {
  ...naver,
  channelCode: "COUPANG",
  parentOrderId: "31971202913784",
  lineCount: 1,
  totalAmount: 11_000,
  rawStatusCode: "DELIVERING",
  paymentLabelKo: null,
  lines: [{ externalOrderId: "714091568735681", paymentAmount: 11_000, rawStatusCode: "DELIVERING", confirmedStatusLabelKo: null, paidAt: "2026-08-21T15:53:00Z" }],
  statusHistory: [
    { fromStatusCode: null, fromLabelKo: null, toStatusCode: "ACCEPT", toLabelKo: null, observedAt: null, recordedAt: "2026-08-22T17:15:00Z", lineCount: 1 },
    { fromStatusCode: "ACCEPT", fromLabelKo: null, toStatusCode: "INSTRUCT", toLabelKo: null, observedAt: null, recordedAt: "2026-08-23T01:43:00Z", lineCount: 1 },
  ],
  inquiries: [],
};

beforeEach(() => {
  vi.setSystemTime(new Date("2026-10-06T03:00:00Z"));
  vi.mocked(api.getChannelsStrict).mockResolvedValue([
    { id: "ch-1", code: "NAVER", nameKo: "네이버 스마트스토어" },
    { id: "ch-2", code: "COUPANG", nameKo: "쿠팡" },
  ] as never);
  vi.mocked(api.getOrderRecordStrict).mockReset().mockResolvedValue(naver);
});

describe("주문 상세 — 결제 단위 하나", () => {
  it("주소의 네 조각으로 읽고, 확인한 축만 우리 말로 적는다", async () => {
    mount(`/orders/NAVER/${ACCOUNT}/2026381795878643`);
    expect(await screen.findByRole("heading", { level: 1, name: "2026381795878643" })).toBeInTheDocument();
    expect(api.getOrderRecordStrict).toHaveBeenCalledWith("NAVER", ACCOUNT, "2026381795878643");

    const axes = screen.getByTestId("order-axes");
    expect(axes).toHaveTextContent("결제결제 완료PAYED");
    // 저장된 행은 「취소되지 않음」을 증명하지 못한다 — 그 자리의 말은 「확인되지 않음」 하나다.
    expect(axes).toHaveTextContent("취소확인되지 않음");
    expect(axes).toHaveTextContent("배송확인되지 않음");
    expect(axes).not.toHaveTextContent("취소되지 않음");
    const lastSeen = screen.getByTestId("order-last-seen");
    expect(lastSeen).toHaveTextContent("마지막 확인 2026-09-04");
    // 색은 서버의 판정이 정한다 — 최신이 증명되지 않은 읽기는 조용히 지나가지 않는다.
    expect(lastSeen.className).toContain("text-warn");
  });

  it("쿠팡 상태는 번역되지 않고, 채널이 시각을 주지 않은 이력은 「—」다", async () => {
    vi.mocked(api.getOrderRecordStrict).mockResolvedValue(coupang);
    mount(`/orders/COUPANG/${ACCOUNT}/31971202913784`);
    await screen.findByRole("heading", { level: 1, name: "31971202913784" });

    expect(screen.getByTestId("order-axes")).toHaveTextContent("결제확인되지 않음");
    expect(screen.getByText("쿠팡 상태값의 뜻을 확인한 적이 없습니다")).toBeInTheDocument();

    const history = within(screen.getByTestId("order-history")).getAllByRole("listitem");
    expect(history).toHaveLength(2);
    expect(history[0]).toHaveTextContent("ACCEPT");
    expect(screen.getAllByTestId("event-observed").every((cell) => cell.textContent === "—")).toBe(true);
  });

  it("줄이 하나면 합계 행을 그리지 않는다", async () => {
    vi.mocked(api.getOrderRecordStrict).mockResolvedValue(coupang);
    mount(`/orders/COUPANG/${ACCOUNT}/31971202913784`);
    await screen.findByTestId("order-lines");
    expect(screen.queryByTestId("order-total")).not.toBeInTheDocument();
  });

  it("줄이 여럿이면 합계가 서고, 줄마다 같은 값은 머리로 올라간다", async () => {
    mount(`/orders/NAVER/${ACCOUNT}/2026381795878643`);
    expect(await screen.findByTestId("order-total")).toHaveTextContent("₩47,800");
    expect(screen.getByText(/2줄 모두/)).toBeInTheDocument();
    // 같은 코드가 줄마다 반복되지 않는다 — 머리에 한 번.
    const lines = within(screen.getByTestId("order-lines")).getAllByRole("listitem");
    expect(lines[0]).not.toHaveTextContent("PAYED");
  });

  it("문의는 채널이 지목한 것만, 0이면 0이라고 적는다", async () => {
    mount(`/orders/NAVER/${ACCOUNT}/2026381795878643`);
    expect(await screen.findByRole("link", { name: "언제 발송하나요?" })).toHaveAttribute("href", "/inquiries/inq-1");
    expect(screen.getByText("미답변")).toBeInTheDocument();
    // 지목된 상품주문 줄에만 표시가 붙는다.
    const lines = within(screen.getByTestId("order-lines")).getAllByRole("listitem");
    expect(within(lines[1]).getByText("문의")).toBeInTheDocument();
    expect(within(lines[0]).queryByText("문의")).not.toBeInTheDocument();
  });

  it("가리킨 문의가 없으면 0이라고 적는다 — 빈 자리로 두지 않는다", async () => {
    vi.mocked(api.getOrderRecordStrict).mockResolvedValue(coupang);
    mount(`/orders/COUPANG/${ACCOUNT}/31971202913784`);
    expect(await screen.findByText("이 주문을 가리킨 문의가 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "문의 0" })).toBeInTheDocument();
  });

  it("열리지 않는 주소는 숫자를 그리지 않는다 — 다른 계정의 행으로 보충되지 않는다", async () => {
    vi.mocked(api.getOrderRecordStrict).mockRejectedValue(new Error("404"));
    mount(`/orders/NAVER/${ACCOUNT}/NO-SUCH-ORDER`);
    expect(await screen.findByText("주문을 찾지 못했습니다")).toBeInTheDocument();
    expect(screen.queryByTestId("order-lines")).not.toBeInTheDocument();
  });
});
