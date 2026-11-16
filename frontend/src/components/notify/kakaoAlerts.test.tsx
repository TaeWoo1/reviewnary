// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { IntakeAlertDelivery, IntakeAlertDeliveryDetail, IntakeAlertSettings } from "../../lib/intakeAlerts";
import { statusLabel } from "../../lib/intakeAlerts";

const api = vi.hoisted(() => ({
  getIntakeAlertSettings: vi.fn(),
  updateIntakeAlertSettings: vi.fn(),
  listIntakeAlertDeliveries: vi.fn(),
  getIntakeAlertDelivery: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api }));

import { KakaoAlertSettings } from "./KakaoAlertSettings";
import { KakaoMockInbox } from "../../pages/app/KakaoMockInbox";
import { NoticeLanding } from "../../pages/app/NoticeLanding";

function settings(over: Partial<IntakeAlertSettings> = {}): IntakeAlertSettings {
  return {
    enabled: false,
    notifyReviews: true,
    notifyInquiries: true,
    deliveryMode: "EACH",
    digestMinutes: 60,
    quietHoursEnabled: false,
    daysMask: 127,
    startMinute: 540,
    endMinute: 1080,
    phoneMasked: null,
    phoneConsented: false,
    numberStorageAvailable: true,
    watchFrom: null,
    sender: { mode: "MOCK", reason: null },
    limits: { recoveryHours: 48, oldAfterHours: 72, perPassCap: 5, passSeconds: 30 },
    counts: { pending: 0, told: 3, handledBeforeTold: 1, oldNotTold: 12, cancelled: 2 },
    ...over,
  };
}

function delivery(over: Partial<IntakeAlertDelivery> = {}): IntakeAlertDelivery {
  return {
    id: "d1",
    kind: "SINGLE",
    mode: "MOCK",
    status: "MOCK_RECORDED",
    reviewCount: 1,
    inquiryCount: 0,
    urgentCount: 0,
    messageText: "[리뷰너리] 새로 확인된 리뷰가 있어요\n가게: 데모",
    linkPath: "/notice/d1",
    failureCode: null,
    createdAt: "2026-11-16T01:00:00Z",
    finishedAt: "2026-11-16T01:00:01Z",
    ...over,
  };
}

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset());
});

describe("카카오톡 알림 설정", () => {
  it("says it is a mock, that only rows after turning on are told, and the honest limits", async () => {
    api.getIntakeAlertSettings.mockResolvedValue(settings());
    render(
      <MemoryRouter>
        <KakaoAlertSettings />
      </MemoryRouter>,
    );
    expect(await screen.findByText("모의 알림 · 실제 카카오톡으로 보내지 않아요")).toBeInTheDocument();
    expect(screen.getByText(/켜면 그때부터 들어오는 리뷰·문의만 알려요/)).toBeInTheDocument();
    expect(screen.getByText(/늦게 가져온 예전 글 12건\(알리지 않음\)/)).toBeInTheDocument();
    expect(screen.getByText(/48시간이 지난 자료는 다시 찾지/)).toBeInTheDocument();
    expect(screen.getByText(/묶지 않고 5건씩 나눠서/)).toBeInTheDocument();
    expect(screen.getByText(/휴대폰 도착까지 확인한 것은 아니에요/)).toBeInTheDocument();
  });

  it("turning on sends only the switch; a number needs consent before the button works", async () => {
    api.getIntakeAlertSettings.mockResolvedValue(settings());
    api.updateIntakeAlertSettings.mockResolvedValue(settings({ enabled: true, watchFrom: "2026-11-16T00:00:00Z" }));
    render(
      <MemoryRouter>
        <KakaoAlertSettings />
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByRole("button", { name: "알림 켜기" }));
    await waitFor(() => expect(api.updateIntakeAlertSettings).toHaveBeenCalledWith({ enabled: true }));
    fireEvent.change(screen.getByLabelText("휴대폰 번호"), { target: { value: "010-1111-2222" } });
    expect(screen.getByRole("button", { name: "번호 저장" })).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/동의해요/));
    expect(screen.getByRole("button", { name: "번호 저장" })).toBeEnabled();
  });

  it("a blocked live request is said as not sent", async () => {
    api.getIntakeAlertSettings.mockResolvedValue(settings({ sender: { mode: "BLOCKED", reason: "LIVE_OFF" } }));
    render(
      <MemoryRouter>
        <KakaoAlertSettings />
      </MemoryRouter>,
    );
    expect(await screen.findByText(/설정이 끝나지 않아 보내지 않아요/)).toBeInTheDocument();
  });
});

describe("모의 알림함", () => {
  it("labels every mock card as an example and never as delivered", async () => {
    api.listIntakeAlertDeliveries.mockResolvedValue([delivery(), delivery({ id: "d2", status: "UNKNOWN", linkPath: "/notice/d2" })]);
    render(
      <MemoryRouter>
        <KakaoMockInbox />
      </MemoryRouter>,
    );
    expect(await screen.findAllByText("카톡 발송 예시 · 모의 알림")).toHaveLength(2);
    expect(screen.getByText(/카톡으로 보내지 않았어요/)).toBeInTheDocument();
    expect(screen.getByText(/자동으로 다시 보내지 않아요/)).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "확인하기" })[0]).toHaveAttribute("href", "/notice/d1");
  });

  it("accepted is not delivered", () => {
    expect(statusLabel("ACCEPTED", null).text).toContain("도착은 확인하지 않았어요");
    expect(statusLabel("CANCELLED", "CANCELLED_NUMBER_CHANGED").text).toContain("번호가 바뀌었어요");
  });
});

describe("알림 링크로 들어온 화면", () => {
  function detail(items: IntakeAlertDeliveryDetail["items"]): IntakeAlertDeliveryDetail {
    return { delivery: delivery(), items };
  }
  const item = {
    subjectKind: "INQUIRY" as const,
    subjectId: "q1",
    channelCode: "CAFE24",
    reason: "NEW_INQUIRY" as const,
    issueLabel: "배송",
    urgent: false,
    status: "INCLUDED",
    cancelReason: null,
    stillOpen: true,
    targetPath: "/inquiries/q1",
    arrivedAt: "2026-11-16T01:00:00Z",
    writtenAt: "2026-11-15T01:00:00Z",
  };

  function at(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/notice/:deliveryId" element={<NoticeLanding />} />
          <Route path="/inquiries/:itemRef" element={<p>문의 상세</p>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it("one open item opens straight away", async () => {
    api.getIntakeAlertDelivery.mockResolvedValue(detail([item]));
    at("/notice/d1");
    expect(await screen.findByText("문의 상세")).toBeInTheDocument();
  });

  it("an item already handled stays on the list with both times", async () => {
    api.getIntakeAlertDelivery.mockResolvedValue(detail([{ ...item, stillOpen: false }]));
    at("/notice/d1");
    expect(await screen.findByText(/이미 처리됐어요/)).toBeInTheDocument();
    expect(screen.getByText(/리뷰너리에 들어온 때/)).toBeInTheDocument();
    expect(screen.getByText(/고객이 쓴 때/)).toBeInTheDocument();
    expect(screen.getByText(/승인되거나 등록되지 않아요/)).toBeInTheDocument();
  });

  it("another account's notice says so instead of showing anything", async () => {
    api.getIntakeAlertDelivery.mockRejectedValue({ response: { status: 404 } });
    at("/notice/d9");
    expect(await screen.findByText("알림을 열 수 없어요")).toBeInTheDocument();
  });
});
