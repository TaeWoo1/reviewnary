// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ReportsV2 } from "./ReportsV2";
import { expectNoAxeViolations } from "../../test/axe";
import type { AgentReportView, ChannelCoverageRowView, ReportCounter, ReportRead } from "../../lib/types";

const getCurrentAgentReport = vi.fn();
const getAgentReport = vi.fn();
const listAgentReports = vi.fn();
const regenerateAgentReport = vi.fn();
const getChannelCoverageStrict = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getCurrentAgentReport: (kind: string) => getCurrentAgentReport(kind),
    getAgentReport: (id: string) => getAgentReport(id),
    listAgentReports: (kind: string) => listAgentReports(kind),
    regenerateAgentReport: (kind: string, start: string | null) => regenerateAgentReport(kind, start),
    getChannelCoverageStrict: () => getChannelCoverageStrict(),
  },
  getToken: () => null,
}));

const ISSUE = "11111111-1111-4111-8111-111111111111";

/**
 * 측정된 상태 그대로 (2026-10-06, 데모 org).
 *
 * <p>기간은 9월 28일 ~ 10월 4일인데 문의·리뷰의 마지막 성공 수집은 9월 26일, 주문은 9월 5일이고 아홉
 * 줄 전부 미증명이었다. 그 위에서 화면은 「받은 문의 0건 · 이전 기간보다 4건 줄음」을 적고 있었다.
 */
function coverage(over: Partial<ChannelCoverageRowView> = {}): ChannelCoverageRowView {
  return {
    channelCode: "NAVER",
    channelNameKo: "네이버 스마트스토어",
    dataType: "REVIEW",
    state: "OBSERVED_FRESHNESS_UNPROVEN",
    supported: true,
    verificationStatus: null,
    connected: true,
    connectionStatus: "CONNECTED",
    routineEnabled: true,
    routinePausedBy: null,
    lastSuccessfulSyncAt: "2026-09-26T18:31:17Z",
    rows: 25,
    openRows: 3,
    newestObservedAt: "2026-09-17T22:14:54Z",
    ...over,
  };
}

/** 생성 시점에 아무 채널도 자격을 갖추지 못한 읽기 — 숫자도 근거도 같은 시각에 얼어 있다. */
const UNREAD_READS: ReportRead[] = ["REVIEW", "INQUIRY", "ORDER_SUMMARY"].map((dataType, i) => ({
  dataType,
  labelKo: ["리뷰", "문의", "주문"][i],
  lastReadAt: dataType === "ORDER_SUMMARY" ? "2026-09-05T11:30:02Z" : "2026-09-26T18:31:17Z",
  measured: false,
  included: [],
  excluded: [
    {
      channelCode: "NAVER",
      channelNameKo: "네이버 스마트스토어",
      state: "OBSERVED_FRESHNESS_UNPROVEN",
      lastReadAt: "2026-09-26T18:31:17Z",
      reasonKo: "최근 자동 수집이 성공하지 못해 이 기간을 확인하지 못했습니다",
    },
  ],
}));

/** 모든 채널이 말한 읽기. */
const FULL_READS: ReportRead[] = ["REVIEW", "INQUIRY", "ORDER_SUMMARY"].map((dataType, i) => ({
  dataType,
  labelKo: ["리뷰", "문의", "주문"][i],
  lastReadAt: "2026-10-05T01:00:00Z",
  measured: true,
  included: [
    {
      channelCode: "NAVER",
      channelNameKo: "네이버 스마트스토어",
      state: "OBSERVED_FRESH",
      lastReadAt: "2026-10-05T01:00:00Z",
      reasonKo: null,
    },
  ],
  excluded: [],
}));

const UNREAD_COVERAGE: ChannelCoverageRowView[] = [
  coverage({ dataType: "REVIEW" }),
  coverage({ dataType: "INQUIRY" }),
  coverage({ dataType: "ORDER_SUMMARY", lastSuccessfulSyncAt: "2026-09-05T11:30:02Z" }),
];

/** 같은 채널이 세 종류 모두 기간이 닫힌 뒤까지 읽었고, 읽었고 없었던 자리는 ZERO다. */
const READ_COVERAGE: ChannelCoverageRowView[] = [
  coverage({ dataType: "REVIEW", state: "OBSERVED_FRESH", lastSuccessfulSyncAt: "2026-10-05T01:00:00Z" }),
  coverage({ dataType: "INQUIRY", state: "OBSERVED_FRESH", lastSuccessfulSyncAt: "2026-10-05T01:00:00Z" }),
  coverage({ dataType: "ORDER_SUMMARY", state: "ZERO", lastSuccessfulSyncAt: "2026-10-05T01:00:00Z" }),
];

/** 저장된 수치 하나. `current: null` = 서버가 이 창에서 센 채널이 하나도 없었다. */
function counter(over: Partial<ReportCounter> & { id: string; labelKo: string }): ReportCounter {
  return {
    periodic: true,
    current: null,
    previous: null,
    delta: null,
    to: null,
    unit: "건",
    dataType: "REVIEW",
    excludedChannels: 3,
    unproven: false,
    ...over,
  };
}

/**
 * 2026-10-06 측정 그대로의 주간 판 — 다만 이제 서버가 그 창을 세지 못했다고 <b>저장</b>한다.
 * 전에는 같은 자리에 {@code current: 0}, {@code previous: 4}, {@code delta: -4}가 들어 있었다.
 */
const REPORT: AgentReportView = {
  id: "rep-1",
  kind: "WEEKLY",
  kindLabelKo: "주간",
  periodStart: "2026-09-28",
  periodEnd: "2026-10-04",
  periodLabelKo: "2026년 9월 28일 ~ 10월 4일",
  version: 1,
  generatedAt: "2026-10-06T11:25:49Z",
  facts: {
    period: {
      kind: "WEEKLY",
      kindLabelKo: "주간",
      start: "2026-09-28",
      end: "2026-10-04",
      labelKo: "2026년 9월 28일 ~ 10월 4일",
      previousStart: "2026-09-21",
      previousEnd: "2026-09-27",
    },
    counters: [
      counter({ id: "c-reviews", labelKo: "받은 리뷰" }),
      counter({ id: "c-negative-reviews", labelKo: "부정 리뷰" }),
      counter({ id: "c-inquiries", labelKo: "받은 문의", dataType: "INQUIRY" }),
      counter({ id: "c-orders", labelKo: "주문", dataType: "ORDER_SUMMARY" }),
      counter({ id: "c-revenue", labelKo: "매출", dataType: "ORDER_SUMMARY", unit: "원" }),
      counter({
        id: "c-unanswered-now",
        labelKo: "현재 답변이 필요한 문의",
        periodic: false,
        current: 27,
        to: "/inquiries?status=UNANSWERED",
        dataType: "INQUIRY",
        excludedChannels: 0,
      }),
    ],
    salesByChannel: [],
    reads: UNREAD_READS,
    issues: [],
    opportunities: [
      {
        id: `o-${ISSUE}-OPERATING_POLICY_SUPPLEMENT`,
        issueId: ISSUE,
        kind: "OPERATING_POLICY_SUPPLEMENT",
        kindLabelKo: "운영 기준 보완",
        status: "OPEN",
        statusLabelKo: "검토 전",
        issueTitle: "배송 지연",
        productId: null,
        productName: null,
        recommendationKo: "'배송' 기준을 더 구체적으로 적거나 고객에게 안내되는 시점을 검토하세요.",
        nextActionKo: "운영 기준 초안 준비",
        to: `/memory/${ISSUE}`,
      },
    ],
    nextSteps: [],
    generatedAt: "2026-10-06T11:25:49Z",
  },
  summary: {
    lines: [
      {
        text: "2026년 9월 28일 ~ 10월 4일의 수집 결과를 확인하지 못해, 이 기간에 무엇이 들어왔는지 알 수 없습니다.",
        kind: "LIMIT",
        factIds: ["c-reviews", "c-inquiries"],
      },
    ],
  },
  narrative: null,
  narrativeStatus: "NOT_GENERATED",
  narrativeNoteKo: null,
};

/** 모든 채널이 말한 기간: 0도 사실이고, 비교도 선다. 매출은 이 판에 얼어 있다. */
const BUSY: AgentReportView = {
  ...REPORT,
  id: "rep-busy",
  facts: {
    ...REPORT.facts,
    counters: [
      counter({ id: "c-reviews", labelKo: "받은 리뷰", current: 25, previous: 17, delta: 8, excludedChannels: 0 }),
      counter({ id: "c-negative-reviews", labelKo: "부정 리뷰", current: 0, previous: 0, delta: 0, excludedChannels: 0 }),
      counter({ id: "c-inquiries", labelKo: "받은 문의", dataType: "INQUIRY", current: 9, previous: 8, delta: 1, excludedChannels: 0 }),
      counter({ id: "c-orders", labelKo: "주문", dataType: "ORDER_SUMMARY", current: 272, previous: 317, delta: -45, excludedChannels: 0 }),
      counter({
        id: "c-revenue",
        labelKo: "매출",
        dataType: "ORDER_SUMMARY",
        unit: "원",
        current: 3_884_590,
        previous: 5_721_102,
        delta: 3_884_590 - 5_721_102,
        excludedChannels: 0,
      }),
      REPORT.facts.counters[5],
    ],
    salesByChannel: [
      { id: "s-NAVER", channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", amount: 2_731_000 },
      { id: "s-CAFE24", channelCode: "CAFE24", channelNameKo: "카페24 자사몰", amount: 1_153_590 },
    ],
    reads: FULL_READS,
    issues: [
      {
        id: `i-${ISSUE}`,
        issueId: ISSUE,
        title: "접착 부족",
        severity: "NORMAL",
        severityLabelKo: "보통",
        current: 4,
        previous: 1,
        delta: 3,
        changeLabelsKo: ["증가 중"],
        productId: "p1",
        productName: "선바로 몰딩",
        to: `/memory/${ISSUE}`,
        measured: true,
      },
    ],
  },
};

/** 같은 반복 문제가, 리뷰를 읽지 못한 판에 실려 있을 때. */
const UNMEASURED_ISSUES: AgentReportView = {
  ...REPORT,
  id: "rep-unmeasured",
  facts: {
    ...REPORT.facts,
    issues: [{ ...BUSY.facts.issues[0], measured: false }],
  },
};

/** 관문이 생기기 전에 저장된 판 — 2026-10-06 11:25의 실제 행 모양. */
const LEGACY: AgentReportView = {
  ...REPORT,
  id: "rep-legacy",
  facts: {
    ...REPORT.facts,
    counters: [
      { id: "c-reviews", labelKo: "받은 리뷰", periodic: true, current: 0, previous: null, delta: null, to: null },
      { id: "c-negative-reviews", labelKo: "부정 리뷰", periodic: true, current: 0, previous: null, delta: null, to: null },
      { id: "c-inquiries", labelKo: "받은 문의", periodic: true, current: 0, previous: 4, delta: -4, to: null },
      { id: "c-orders", labelKo: "주문", periodic: true, current: 0, previous: null, delta: null, to: null },
      {
        id: "c-unanswered-now",
        labelKo: "현재 답변이 필요한 문의",
        periodic: false,
        current: 27,
        previous: null,
        delta: null,
        to: "/inquiries?status=UNANSWERED",
      },
    ],
    salesByChannel: [],
    // 관문 이전의 판에는 읽은 범위가 없다 — 오늘의 수집 상태로 보완하지 않는다.
    reads: [],
  },
  narrativeStatus: "READY",
};

function renderReports(url = "/reports") {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <ReportsV2 />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  getCurrentAgentReport.mockResolvedValue(REPORT);
  getAgentReport.mockResolvedValue(REPORT);
  listAgentReports.mockResolvedValue([]);
  getChannelCoverageStrict.mockResolvedValue(UNREAD_COVERAGE);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("운영 리포트 — 읽은 범위가 숫자 위에 선다", () => {
  it("읽지 못한 기간의 0은 숫자가 아니라 「확인되지 않음」이고, 그 위에서 비교도 서지 않는다", async () => {
    const { container } = renderReports();
    await screen.findByTestId("report-reviews");

    expect(screen.getByRole("heading", { level: 1, name: "운영 리포트" })).toBeInTheDocument();
    expect(getCurrentAgentReport).toHaveBeenCalledWith("WEEKLY");

    // 읽은 범위가 먼저 서고, 종류마다 마지막 확인과 이 기간의 판정을 적는다.
    const reads = screen.getByTestId("report-reads");
    expect(reads).toHaveTextContent("리뷰");
    expect(reads).toHaveTextContent("주문");
    expect(screen.getAllByText("확인 못 함")).toHaveLength(3);

    // 관측된 결함 그 자체: 0건도, 「4건 줄음」도 서지 않는다.
    expect(screen.getAllByText("확인되지 않음").length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText(/4건 줄음/)).toBeNull();
    expect(screen.getByTestId("report-no-change")).toHaveTextContent("이 기간의 수집 결과를 확인하지 못했습니다");

    // 기간과 무관한 지금 수치는 읽기와 무관하게 사실이고, 열 것이 있으므로 문이다.
    expect(screen.getByRole("link", { name: "만든 시점 답변이 필요했던 문의" })).toHaveAttribute(
      "href",
      "/inquiries?status=UNANSWERED",
    );
    // 기간이 없을 뿐 시점이 없는 것은 아니다 — 그 시점을 적는다.
    expect(screen.getByTestId("report-inquiries")).toHaveTextContent("10월 6일 20:25 기준");
    expect(screen.getByTestId("report-inquiries")).not.toHaveTextContent("지금 수치");

    // 반복 문제가 비어 있어도 「없었다」고 말하지 않는다 — 근거인 리뷰를 읽지 못했기 때문이다.
    expect(screen.getByTestId("report-no-issues")).toHaveTextContent("반복 문제가 없었는지 확인할 수 없습니다");
    // 요약 문장은 저장된 것이고, 읽지 못한 창에 대해 「0건」을 말하지 않는다.
    expect(REPORT.summary.lines[0].text).toContain("무엇이 들어왔는지 알 수 없습니다");
    await expectNoAxeViolations(container);
  });

  it("AI 운영 요약은 화면에 서지 않는다 — 스냅샷이 그 문장을 들고 와도", async () => {
    renderReports();
    await screen.findByTestId("report-reviews");
    expect(screen.queryByText("AI 운영 요약")).toBeNull();
    expect(screen.queryByText("문의 감소와 배송 지연 기준 보완 제안")).toBeNull();
    expect(screen.queryByTestId("report-narrative")).toBeNull();
    // 서버도 더 이상 만들지 않는다 — 저장된 판이 그렇게 말한다.
    expect(REPORT.narrativeStatus).toBe("NOT_GENERATED");
    expect(REPORT.narrative).toBeNull();
  });

  it("모든 채널이 말한 기간이면 0은 측정된 0이고, 변화는 그제야 선다", async () => {
    getChannelCoverageStrict.mockResolvedValue(READ_COVERAGE);
    getCurrentAgentReport.mockResolvedValue(BUSY);
    renderReports();
    await screen.findByTestId("report-changes");

    expect(screen.getAllByText("전부 포함")).toHaveLength(3);
    expect(screen.queryByText("확인되지 않음")).toBeNull();
    // 부정 리뷰 0건은 이제 사실이다.
    expect(screen.getByTestId("report-reviews")).toHaveTextContent("부정 리뷰");
    const changes = screen.getByTestId("report-changes");
    expect(changes).toHaveTextContent("8건 늘음");
    expect(changes).toHaveTextContent("45건 줄음");
    // 움직이지 않은 줄은 「달라진 것」이 아니다.
    expect(changes).not.toHaveTextContent("부정 리뷰");
    // 반복 문제는 자기 객체로 가는 문이다.
    expect(screen.getByRole("link", { name: /접착 부족/ })).toHaveAttribute("href", `/memory/${ISSUE}`);
  });

  it("매출은 이 판의 사실이다 — 금액도 채널별도 스냅샷에서 온다", async () => {
    getChannelCoverageStrict.mockResolvedValue(READ_COVERAGE);
    getCurrentAgentReport.mockResolvedValue(BUSY);
    renderReports();
    const sales = await screen.findByTestId("report-sales");

    expect(sales).toHaveTextContent("3,884,590");
    expect(sales).toHaveTextContent("5,721,102");
    expect(screen.getByTestId("report-sales-channels")).toHaveTextContent("네이버 스마트스토어");
    expect(screen.getByTestId("report-sales-channels")).toHaveTextContent("카페24 자사몰");
    // 매출의 변화는 원으로 말한다 — 단위는 사실에 붙어 있다.
    expect(screen.getByTestId("report-changes")).toHaveTextContent("₩1,836,512 줄음");
  });

  it("반복 문제가 늘었다는 말은 근거 창이 측정됐을 때만 한다", async () => {
    getCurrentAgentReport.mockResolvedValue(UNMEASURED_ISSUES);
    renderReports();
    const issues = await screen.findByTestId("report-issues");
    // 저장된 스냅샷은 delta 3을 들고 있지만, 서버가 그 창을 측정하지 못했다고 함께 적어 두었다.
    expect(UNMEASURED_ISSUES.facts.issues[0].delta).toBe(3);
    expect(issues).not.toHaveTextContent("3 늘음");
    expect(issues).toHaveTextContent("보통");
    expect(screen.getByLabelText("반복 문제")).toHaveTextContent("아래 건수는 이 판이 읽은 범위까지입니다");
  });

  /**
   * <b>관문 이전에 저장된 판</b> — 데모 org의 실제 주간 행이다. 숫자는 지워지지 않지만, 그것이 어디까지
   * 읽은 것인지 그 판은 기록하지 않았다. 바로 그 행이 「받은 문의 0건 · 이전 기간보다 4건 줄음」이다.
   */
  it("옛 판의 숫자는 남기되 측정값으로 세우지 않고, 비교는 그 위에 서지 않는다", async () => {
    getCurrentAgentReport.mockResolvedValue(LEGACY);
    renderReports();
    await screen.findByTestId("report-reviews");

    expect(screen.getByLabelText("읽은 범위")).toHaveTextContent("수집 범위를 확인하기 전에 만들어졌습니다");
    expect(screen.getByTestId("report-no-read-range")).toHaveTextContent("다시 만들면");
    // 오늘의 수집 상태로 과거 판을 보완하지 않으므로, 읽은 범위 표 자체가 서지 않는다.
    expect(screen.queryByTestId("report-reads")).toBeNull();
    // 숫자는 그대로 있고, 그 옆에 무엇이 없는지 적힌다.
    expect(screen.getByTestId("report-inquiries")).toHaveTextContent("수집 범위 기록 없음");
    // 저장된 delta는 −4지만 비교는 서지 않는다.
    expect(LEGACY.facts.counters[2].delta).toBe(-4);
    expect(screen.queryByTestId("report-changes")).toBeNull();
    expect(screen.getByTestId("report-no-change")).toHaveTextContent("수집 범위를 확인하기 전에 만들어졌습니다");
  });

  it("매출은 기간이 비면 0원을 그리지 않는다 — 수집이 멈춘 날 가장 쉬운 거짓말이다", async () => {
    renderReports();
    const sales = await screen.findByTestId("report-sales");
    expect(sales).toHaveTextContent("확인되지 않음");
    expect(sales).not.toHaveTextContent("₩0");
    expect(screen.queryByTestId("report-sales-channels")).toBeNull();
  });
});

describe("운영 리포트 — 읽기는 하나이고, 실패해도 골격은 선다", () => {
  it("스냅샷을 못 불러와도 제목은 선다", async () => {
    getCurrentAgentReport.mockRejectedValue(new Error("boom"));
    renderReports();
    await screen.findByTestId("report-unavailable");
    expect(screen.getByRole("heading", { level: 1, name: "운영 리포트" })).toBeInTheDocument();
    // 읽은 범위도 그 스냅샷의 것이므로 함께 서지 않는다 — 오늘의 수집 상태를 대신 그리지 않는다.
    expect(screen.queryByTestId("report-reads")).toBeNull();
  });

  /**
   * <b>이 화면은 라이브 수집 상태를 묻지 않는다</b> (2026-10-06). 읽은 범위가 스냅샷에 얼어 있으므로
   * 물을 이유가 없고, 물으면 멈춰 있는 숫자 위에서 근거만 혼자 움직인다.
   */
  it("읽기는 스냅샷 하나뿐 — 수집 상태 endpoint를 호출하지 않는다", async () => {
    renderReports();
    await screen.findByTestId("report-reads");
    expect(getChannelCoverageStrict).not.toHaveBeenCalled();
    expect(screen.getByTestId("report-sales")).toBeInTheDocument();
  });

  it("같은 판을 다시 열면 읽은 범위도 숫자와 함께 그대로다", async () => {
    renderReports("/reports?id=rep-1");
    const reads = await screen.findByTestId("report-reads");
    // 저장된 값 그대로 — 9월 26일, 주문은 9월 5일.
    expect(reads).toHaveTextContent("9월 27일 03:31");
    expect(reads).toHaveTextContent("9월 5일 20:30");
    expect(screen.getAllByText("확인 못 함")).toHaveLength(3);
    expect(getChannelCoverageStrict).not.toHaveBeenCalled();
  });
});

describe("운영 리포트 — 판(version)과 이동", () => {
  it("새 판만 새 읽은 범위를 가진다 — 다시 만들기 전에는 저장된 것 그대로", async () => {
    const v2: AgentReportView = {
      ...REPORT,
      id: "rep-2",
      version: 2,
      generatedAt: "2026-10-07T01:00:00Z",
      facts: { ...REPORT.facts, reads: FULL_READS },
    };
    regenerateAgentReport.mockResolvedValue(v2);
    getAgentReport.mockImplementation((id: string) => Promise.resolve(id === "rep-2" ? v2 : REPORT));
    renderReports();
    await screen.findByTestId("report-reads");
    expect(screen.getAllByText("확인 못 함")).toHaveLength(3);

    fireEvent.click(screen.getByRole("button", { name: "지금 자료로 다시 만들기" }));
    await waitFor(() => expect(screen.getAllByText("전부 포함")).toHaveLength(3));
    expect(screen.getByTestId("report-reads")).toHaveTextContent("10월 5일 10:00");
  });

  it("기간 종류는 URL로 바꾸고, 월간 스냅샷을 읽는다", async () => {
    renderReports();
    await screen.findByTestId("report-reviews");
    fireEvent.click(screen.getByRole("button", { name: "월간" }));
    await waitFor(() => expect(getCurrentAgentReport).toHaveBeenCalledWith("MONTHLY"));
  });

  it("다시 만들기는 명시적인 조작일 때만, 그리고 새 판으로", async () => {
    const v2 = { ...REPORT, id: "rep-2", version: 2 };
    regenerateAgentReport.mockResolvedValue(v2);
    getAgentReport.mockImplementation((id: string) => Promise.resolve(id === "rep-2" ? v2 : REPORT));
    renderReports();
    await screen.findByTestId("report-reads");
    expect(regenerateAgentReport).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "지금 자료로 다시 만들기" }));
    await waitFor(() => expect(regenerateAgentReport).toHaveBeenCalledWith("WEEKLY", "2026-09-28"));
    expect(await screen.findByText(/2번째/)).toBeInTheDocument();
  });

  it("저장된 판은 id로 열고 새로 만들지 않는다", async () => {
    renderReports("/reports?id=rep-1");
    await screen.findByTestId("report-reviews");
    expect(getAgentReport).toHaveBeenCalledWith("rep-1");
    expect(getCurrentAgentReport).not.toHaveBeenCalled();
  });

  /**
   * <b>리포트는 읽고 나서 묻고 싶어지는 자리다</b> (pilot QA, 2026-09-06). 가는 것은 어느 화면인가뿐이다.
   */
  it("다른 화면과 같은 대화를 열고, 리포트의 글은 넘기지 않는다", async () => {
    const { container } = renderReports();
    await screen.findByTestId("report-reviews");
    const launcher = screen.getByRole("link", { name: /이 내용으로 물어보기/ });
    const href = launcher.getAttribute("href") ?? "";
    expect(href).toContain("from=report");
    expect(href).not.toMatch(/goal=|productId=|id=rep-/);
    expect(container.querySelectorAll("textarea")).toHaveLength(0);
  });
});
