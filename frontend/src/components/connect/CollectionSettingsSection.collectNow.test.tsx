// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { CollectionSettingsSection } from "./CollectionSettingsSection";

/**
 * <b>「지금 수집하기」 한 번으로 그 채널에 실제로 있는 경로가 실행되는가.</b>
 *
 * 고치려는 결함은 화면 쪽에 있었다. 이 버튼이 커넥터 pull 엔드포인트에 직접 묶여 있었기 때문에, 리뷰 API가
 * 없는 두 채널(네이버·쿠팡)에서는 버튼이 아예 그려지지 않았다. 판매자가 본 것은 「이 제품은 네이버 리뷰를
 * 못 가져온다」였고, 그 사이 증명된 화면 읽기 경로는 손잡이 없는 문 뒤에 있었다.
 *
 * 그래서 이 파일이 지키는 것은 두 가지다 — 경로를 서버가 답한 그대로 따른다는 것과, 그 답을 이 컴포넌트가
 * 채널 이름으로 다시 추측하지 않는다는 것.
 */
const collectNow = vi.fn();
const collectNowReadiness = vi.fn();
const screenReadStatus = vi.fn();
const manualSync = vi.fn();
vi.mock("../../lib/apiClient", () => ({
  api: {
    putSchedule: vi.fn(),
    retryRun: vi.fn(),
    manualSync: (...a: unknown[]) => manualSync(...a),
    collectNow: (...a: unknown[]) => collectNow(...a),
    collectNowReadiness: (...a: unknown[]) => collectNowReadiness(...a),
    screenReadStatus: (...a: unknown[]) => screenReadStatus(...a),
    getChannelCapabilityOverview: () => Promise.resolve(null),
    getCredentialDiagnosis: () => Promise.resolve({ status: "OK", remedy: null }),
  },
  getToken: () => null,
}));

/** 커넥터가 리뷰를 못 내준다는 사실 — 화면 읽기 줄에서도 그대로 참이고, 주기 자동 수집이 없는 이유다. */
const REVIEW_NOT_PULLABLE = [
  {
    channelCode: "NAVER",
    connectorClass: "API",
    dataType: "REVIEW",
    supported: false,
    verificationStatus: "UNSUPPORTED",
    notes: null,
  },
] as never;

function mount(onReport = vi.fn(), over: { hostOwnsScreenRead?: boolean } = {}) {
  render(
    <MemoryRouter>
      <CollectionSettingsSection
        accountId="acct-1"
        schedules={[]}
        capabilities={REVIEW_NOT_PULLABLE}
        onChanged={vi.fn()}
        onReport={onReport}
        {...over}
      />
    </MemoryRouter>,
  );
  return onReport;
}

/** 리뷰 줄 전체. 컨트롤은 라벨의 형제 노드에 있으므로 라벨에서 멈추는 로케이터로는 버튼이 보이지 않는다. */
function reviewRow() {
  const section = screen.getByText("수집 설정").closest("section") as HTMLElement;
  return within(section).getByText("리뷰").closest("li") as HTMLElement;
}

async function reviewButton() {
  return waitFor(() => within(reviewRow()).getByRole("button", { name: "지금 수집하기" }));
}

/** 이 줄은 화면 읽기 경로이고 책상은 이 상태다 — 다른 줄(문의·주문)은 API로 답한다. */
function readiness(localAgent: string) {
  collectNowReadiness.mockImplementation(async (_acct: string, dataType: string) =>
    dataType === "REVIEW" ? { path: "SCREEN_READ", localAgent } : { path: "API", localAgent: null },
  );
}

beforeEach(() => {
  collectNow.mockReset();
  collectNowReadiness.mockReset();
  screenReadStatus.mockReset();
  manualSync.mockReset();
  collectNowReadiness.mockResolvedValue({ path: "API", localAgent: null });
});

describe("지금 수집 — 서버가 경로를 고른다", () => {
  it("리뷰 API가 없는 채널에서도 버튼이 있다 — 그게 이번에 고친 것", async () => {
    readiness("READY");
    mount();

    const button = await reviewButton();
    expect(button).not.toBeDisabled();
    // 「미지원」 칩과 동작하는 버튼이 한 줄에 같이 있으면 화면이 스스로를 부정한다.
    expect(within(reviewRow()).queryByText("자동 수집 미지원")).toBeNull();
    // 주기는 여전히 없다: 돌릴 수 있는 스케줄이 생긴 것이 아니라, 누를 수 있는 경로가 보이게 된 것이다.
    expect(reviewRow().querySelector("select")).toBeNull();
    expect(within(reviewRow()).getByText(/판매자 센터 화면에서 읽어옵니다/)).toBeInTheDocument();
  });

  it("누르면 화면 읽기가 돌고, 끝난 결과를 판매자 문장으로 말한다", async () => {
    readiness("READY");
    collectNow.mockResolvedValue({
      path: "SCREEN_READ",
      dataType: "REVIEW",
      run: null,
      screenRead: { jobId: "job-1", state: "RUNNING", observed: null, inserted: null, changed: null, complete: false, startedAt: null, finishedAt: null },
    });
    screenReadStatus.mockResolvedValue({
      jobId: "job-1",
      state: "SUCCESS",
      observed: 52,
      inserted: 7,
      changed: 2,
      complete: true,
      startedAt: null,
      finishedAt: null,
    });
    const onReport = mount();

    await userEvent.click(await reviewButton());

    await waitFor(() => expect(collectNow).toHaveBeenCalled());
    const [accountId, dataType, requestId] = collectNow.mock.calls[0] as [string, string, string];
    expect(accountId).toBe("acct-1");
    expect(dataType).toBe("REVIEW");
    // 이 누름의 식별자. 더블클릭·재요청·새로고침이 두 건이 아니라 한 건으로 모이게 하는 값이고, 서버의
    // clientJobId 형식(영숫자·_·-)을 지켜야 400으로 돌아오지 않는다.
    expect(requestId).toMatch(/^[A-Za-z0-9_-]{1,40}$/);
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("리뷰 수집 완료: 새로 저장 7 · 갱신 2", false),
    );
    // 예전 경로는 더 이상 쓰이지 않는다.
    expect(manualSync).not.toHaveBeenCalled();
  });

  it("도우미가 연결되지 않았으면 누를 수 없고, 무엇을 하면 되는지 말한다", async () => {
    readiness("UNPAIRED");
    mount();

    expect(await reviewButton()).toBeDisabled();
    expect(within(reviewRow()).getByText(/도우미를 한 번 연결하면/)).toBeInTheDocument();
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("이미 수집 중이면 기다리라고 하고, 연결하라고 하지 않는다", async () => {
    readiness("BUSY");
    mount();

    expect(await reviewButton()).toBeDisabled();
    expect(within(reviewRow()).getByText(/이미 수집이 진행 중입니다/)).toBeInTheDocument();
    // 같은 409의 두 뜻을 섞으면, 이미 연결한 판매자를 아무 일도 하지 않는 버튼으로 보낸다.
    expect(within(reviewRow()).queryByText(/연결하면/)).toBeNull();
  });

  it("판매자 센터 로그인이 풀렸으면 로그인을 안내하고, 다시 누를 수는 있게 둔다", async () => {
    readiness("AUTH_REQUIRED");
    mount();

    // 로그인은 판매자가 자기 브라우저에서 방금 했을 수도 있다. 막아 두면 고친 뒤에도 누를 길이 없다.
    expect(await reviewButton()).not.toBeDisabled();
    expect(within(reviewRow()).getByText(/판매자 센터 로그인이 필요합니다/)).toBeInTheDocument();
  });

  it("도우미 미연결·수집 중은 눌렀을 때도 서로 다른 안내가 된다", async () => {
    readiness("READY");
    collectNow.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { code: "HELPER_BUSY", message: "이 컴퓨터에서 이미 확인 작업이 진행 중입니다." } },
    });
    const onReport = mount();

    await userEvent.click(await reviewButton());
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("이미 수집이 진행 중입니다. 끝난 뒤 다시 눌러 주세요.", true),
    );

    collectNow.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { code: "HELPER_NOT_LINKED", message: "연결된 도우미가 없습니다." } },
    });
    await userEvent.click(await reviewButton());
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith(
        "이 컴퓨터의 도우미가 아직 연결되지 않았습니다. 도우미 카드에서 [이 기기 연결]을 한 번 눌러 주세요.",
        true,
      ),
    );
  });

  it("API 경로인 줄은 같은 버튼으로 같은 pull을 돈다", async () => {
    collectNowReadiness.mockResolvedValue({ path: "API", localAgent: null });
    collectNow.mockResolvedValue({
      path: "API",
      dataType: "INQUIRY",
      run: { status: "SUCCESS", successRows: 4, skippedRows: 1, failedRows: 0 },
      screenRead: null,
    });
    const onReport = mount();

    const section = screen.getByText("수집 설정").closest("section") as HTMLElement;
    const inquiryRow = within(section).getByText("문의").closest("li") as HTMLElement;
    await userEvent.click(
      await waitFor(() => within(inquiryRow).getByRole("button", { name: "지금 수집하기" })),
    );

    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("문의 수집 완료: 저장 4 · 건너뜀 1 · 실패 0", false),
    );
    expect(screenReadStatus).not.toHaveBeenCalled();
  });

  it("경로를 묻기 전에는 컨트롤을 그리지 않는다 — 앞 계정의 답으로 이 줄을 설명하지 않기 위해", async () => {
    collectNowReadiness.mockReturnValue(new Promise(() => {}));
    mount();

    expect(screen.getAllByText("수집 지원 정보 확인 중…").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "지금 수집하기" })).toBeNull();
  });
});

describe("수집 입구는 자료마다 하나다", () => {
  it("이 섹션이 화면의 수집 자리이면 버튼은 여기 있다 — 네이버·카페24가 쓰는 모양", async () => {
    // 일반 채널 화면은 자료별 카드를 그리지 않으므로, 「수집 설정」이 그 자료의 수집 자리다. 기본값이
    // 그대로여야 네이버 리뷰의 「지금 수집하기」가 사라지지 않는다.
    readiness("READY");
    mount();

    await waitFor(() => expect(within(reviewRow()).getByTestId("collect-now-REVIEW")).toBeDefined());
    expect(within(reviewRow()).getByText(/판매자 센터 화면에서 읽어옵니다/)).toBeInTheDocument();
  });

  it("호스트가 이미 그 자료의 수집을 들고 있으면, 이 줄은 주기가 없는 이유만 말한다", async () => {
    // 쿠팡 화면이 쓰는 모양. 같은 자료에 같은 버튼이 두 번 보이면 판매자는 둘이 다른 일을 하는 줄로 읽고,
    // 2026-10-07 라이브에서 실제로 다른 쪽을 눌렀다.
    readiness("READY");
    mount(vi.fn(), { hostOwnsScreenRead: true });

    await waitFor(() =>
      expect(within(reviewRow()).getByText(/판매자 센터 화면에서 읽어옵니다/)).toBeInTheDocument(),
    );
    expect(within(reviewRow()).queryByTestId("collect-now-REVIEW")).toBeNull();
    // 그러나 API 경로인 줄의 버튼은 그대로다 — 이 선언은 화면 읽기 자료에 대한 것이다.
    const section = screen.getByText("수집 설정").closest("section") as HTMLElement;
    const inquiryRow = within(section).getByText("문의").closest("li") as HTMLElement;
    expect(within(inquiryRow).getByTestId("collect-now-INQUIRY")).toBeDefined();
  });
});

describe("경로 판단은 서버에만 있다", () => {
  // 수집을 실행하는 코드 전부. primary가 하나로 합쳐졌으므로, 그 하나와 그것을 품은 줄을 같이 지킨다.
  const ACQUISITION_SOURCES = ["CollectionSettingsSection.tsx", "CollectNowAction.tsx"];

  it.each(ACQUISITION_SOURCES)("%s 의 코드에는 채널 이름이 없다", (file) => {
    const source = readFileSync(resolve(__dirname, file), "utf8");
    // 주석은 뺀다 — 왜 이렇게 되어 있는지 적어 둔 문장에는 채널 이름이 나와야 하고(실측이 그 채널에서
    // 있었다), 그것은 분기가 아니다. 지키는 것은 「실행되는 코드가 채널 이름을 읽지 않는다」이다.
    const code = source
      .replace(/\{\/\*[\s\S]*?\*\/\}/g, "")
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/(^|[^:])\/\/.*$/gm, "$1");
    // 채널 이름으로 분기하던 구조가 이번에 걷어낸 결함 그 자체다. 여기에 "NAVER"가 다시 등장하는 순간
    // 네 번째 채널은 코드를 고쳐야 버튼이 생기는 채널이 되고, 우리는 그걸 두 번 겪었다.
    for (const name of ["NAVER", "COUPANG", "CAFE24", "GMARKET", "스마트스토어", "쿠팡", "자사몰"]) {
      expect(code).not.toContain(name);
    }
  });

  it("수집을 시작하는 코드는 /collect-now 하나만 부른다", () => {
    // 같은 자료에 입구가 둘 보였던 결함의 코드 쪽 모양: 수집처럼 보이는 컨트롤이 다른 엔드포인트를
    // 불렀다. 이 두 파일에서 호출되는 수집 API는 collectNow 하나여야 한다.
    for (const file of ACQUISITION_SOURCES) {
      const code = readFileSync(resolve(__dirname, file), "utf8")
        .replace(/\{\/\*[\s\S]*?\*\/\}/g, "")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/(^|[^:])\/\/.*$/gm, "$1");
      expect(code).not.toContain("api.manualSync");
      expect(code).not.toContain("api.startReviewImport");
    }
  });
});
